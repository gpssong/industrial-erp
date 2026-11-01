#!/bin/bash
# =============================================================================
# erp-failover.sh — 阿里云待命站: 主站(.150)故障时自动接管
#
# 部署位置: 阿里云 /usr/local/bin/erp-failover.sh
# 配置:     /etc/erp-failover.env (600) + /opt/erp-system/.env
# 调度:     systemd timer erp-failover.timer (每 30s)
#
# 正常态: aliyun MySQL 只读, 作为 .150 的从库; backend 连隧道(172.18.0.1:13306)
# 接管态: 停从库 → 本地可写 → backend 连本地 erp-mysql → DNS 切阿里云
#
# 子命令:
#   tick            定时探测(默认入口); 连续 3 次失败并复核后自动接管
#                   复核时会先清掉占着 13306 的死隧道会话再等 30s 重连,
#                   避免"只是隧道断"被误判成主站故障; 切 DNS 前用隧道来源 IP 刷新家宽地址
#   status          打印状态
#   promote         手工立即接管 (用于演练)
#   enforce         接管态下重放不变量(写权限/数据源/容器存活)
#   freeze          冻结写入并停 backend (回切第一步)
#   dump            冻结后导出全库到 backup/failback/ 并写校验旁注
#   finish F P      回切最后一步: 以 .150 位点重建复制 + 回读隧道数据源 + DNS 切回
#   finish-tail     只做收尾(复制已恢复): 只读 + 隧道数据源 + 重建 backend + DNS 切回
#   fingerprint     打印全库行数指纹(对账用)
# =============================================================================
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

CONF=/etc/erp-failover.env
[ -f "$CONF" ] || { echo "missing $CONF" >&2; exit 2; }
# shellcheck disable=SC1090
. "$CONF"

ENV_FILE="$COMPOSE_DIR/.env"
STATE_DIR=/var/lib/erp-failover
LOG=$STATE_DIR/failover.log
LOCK=$STATE_DIR/.lock
THRESHOLD="${PROBE_FAIL_THRESHOLD:-3}"
RECOVER_ALERT_EVERY=1800

mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR"
[ -f /etc/erp-health.env ] && . /etc/erp-health.env
ALERT_WEBHOOK="${ALERT_WEBHOOK:-${WEBHOOK:-}}"
DNS_SCRIPT=/usr/local/bin/erp-dns-switch.sh
PUBLIC_IP="${ERP_PUBLIC_IP:-47.96.188.129}"

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }
say() { printf '%s\n' "$*"; }

notify() {
    log "ALERT: $*"
    say "ALERT: $*" >&2
    [ -n "$ALERT_WEBHOOK" ] || return 0
    local esc body
    esc=$(printf '%s' "$*" | sed 's/\\/\\\\/g; s/"/\\"/g')
    body="{\"msgtype\":\"text\",\"text\":{\"content\":\"[ERP接管] $esc\"}}"
    curl -s -m 8 -H 'Content-Type: application/json' -d "$body" "$ALERT_WEBHOOK" >/dev/null 2>&1 \
        && log "告警已发送" || log "告警发送失败"
}

# root 执行 (读写权限不受 read_only 限制, 仅用于运维动作)
q() {
    docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -uroot -N -B -e "$1" 2>&1 | tr -d '\r'
}
# 应用账号执行 (无 SUPER, read_only 生效 → 真正代表业务能否写入)
qapp() {
    docker exec -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -h127.0.0.1 -u"$DB_USER" -N -B -e "$1" 2>&1 | tr -d '\r'
}
# 纵向输出 (\G) 专用: 不能带 -N/-B, 批处理模式会忽略 \G 退化成制表符横表,
# 导致 'Replica_IO_Running: ...' 解析不到 (2026-11-01 回切踩过)
qv() {
    docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -uroot -e "$1" 2>&1 | tr -d '\r'
}

state() { head -n1 "$STATE_DIR/state" 2>/dev/null | awk '{print $1}'; }
set_state() { printf '%s %s %s\n' "$1" "$(date '+%F %T')" "${2:-}" > "$STATE_DIR/state"; }

heal_tunnel() {
    # .150 的隧道客户端断开后, 阿里云 sshd 有几率留着死会话继续占 13306,
    # 新连接 ExitOnForwardFailure 直接失败 → 隧道永远回不来 → 探测失败 → 误接管
    # (2026-11-01 二次误接管就是这条链). 会话已无 ESTAB 连接时清掉, 让 .150 重连.
    ss -tn state established '( sport = :13306 )' 2>/dev/null | grep -q ESTAB && return 0
    local pid
    for pid in $(ps -eo pid,args | awk '/sshd: erp-tunnel/ && !/awk/ {print $1}'); do
        log "heal: 清理无连接的隧道会话 pid=$pid"
        kill "$pid" 2>/dev/null
    done
}

refresh_home_ip() {
    # 家宽公网 IP 会变, 静态 DNS_TARGET_HOME 一定会过期 (2026-11-01 回切指向死地址).
    # 隧道 SSH 连接的对端 IP 就是家宽当前公网 IP: 先定位 sshd: erp-tunnel 会话 pid,
    # 再取该 pid 上本地 :22 那条连接的 peer. (13306 的 ESTAB 是 backend 走转发, 不能用)
    local ip pids
    pids=$(ps -eo pid,args | awk '/sshd: erp-tunnel/ && !/awk/ {print $1}' | paste -sd'|' -)
    if [ -n "$pids" ]; then
        ip=$(ss -tnp 2>/dev/null | grep -E "pid=($pids)" \
            | awk '$4 ~ /:22$/ {print $5}' | cut -d: -f1 | head -1)
    fi
    if [ -n "${ip:-}" ]; then
        printf '%s\n' "$ip" > "$STATE_DIR/dns-home-ip"
        log "home 公网 IP 刷新: $ip"
    else
        log "home 公网 IP 取不到 (隧道未连), 沿用 $STATE_DIR/dns-home-ip"
    fi
}

probe_primary() {
    # 隧道 → .150 的真实数据路径; 隧道断或 .150 MySQL 停都会失败
    docker exec -e MYSQL_PWD="$PROBE_DB_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -h"$PRIMARY_DB_HOST" -P"$PRIMARY_DB_PORT" -u"$PROBE_DB_USER" \
              --connect-timeout=5 -N -B -e "SELECT 1" 2>/dev/null | grep -qx 1
}

patch_env() {
    # $1=local|tunnel  幂等改写 .env 的四个 ERP_DB_* 键
    local mode="$1" host port user pass
    if [ "$mode" = local ]; then
        host="$LOCAL_DB_HOST"; port="$LOCAL_DB_PORT"; user="$DB_USER"; pass="$DB_PASSWORD"
    else
        host="$PRIMARY_DB_HOST"; port="$PRIMARY_DB_PORT"; user="$PROBE_DB_USER"; pass="$PROBE_DB_PASSWORD"
    fi
    [ -f "$ENV_FILE.bak_failover" ] || cp -a "$ENV_FILE" "$ENV_FILE.bak_failover"
    python3 - "$ENV_FILE" "$host" "$port" "$user" "$pass" <<'PY'
import sys, re
path, host, port, user, pwd = sys.argv[1:6]
vals = {"ERP_DB_HOST": host, "ERP_DB_PORT": port,
        "ERP_DB_USER": user, "ERP_DB_PASSWORD": pwd}
lines, seen = [], set()
for line in open(path, encoding="utf-8"):
    m = re.match(r"^(\w+)=", line)
    if m and m.group(1) in vals:
        k = m.group(1)
        if k in seen:
            continue
        seen.add(k)
        lines.append(f"{k}={vals[k]}\n")
    else:
        lines.append(line)
missing = [k for k in vals if k not in seen]
if missing:
    lines.append("\n# --- 故障接管状态标记 (由 erp-failover.sh 维护) ---\n")
    for k in missing:
        lines.append(f"{k}={vals[k]}\n")
open(path, "w", encoding="utf-8").write("".join(lines))
PY
    chmod 600 "$ENV_FILE"
}

backend_env_host() {
    docker inspect erp-backend --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
        | awk -F= '/^SPRING_DATASOURCE_HOST=/{print $2}' | tr -d '\r'
}

wait_healthy() {
    local i
    for i in $(seq 1 40); do
        if [ "$(docker inspect -f '{{.State.Health.Status}}' erp-backend 2>/dev/null)" = healthy ] \
           && [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' -H 'Host: home.93gushi.com' http://127.0.0.1:8088/api/auth/captcha)" = 200 ]; then
            return 0
        fi
        sleep 3
    done
    return 1
}

recreate_backend() {
    ( cd "$COMPOSE_DIR" && docker compose up -d --no-build erp-backend 2>>"$LOG" )
}

dns_switch() {
    local target="$1"
    if [ ! -x "$DNS_SCRIPT" ]; then
        notify "DNS 切换脚本缺失, 请手工把 home.93gushi.com 指向${target}入口"
        return 1
    fi
    [ "$target" = home ] && refresh_home_ip
    if "$DNS_SCRIPT" "$target" >>"$LOG" 2>&1; then
        log "DNS 已切到 $target"
        return 0
    fi
    notify "DNS 自动切换失败($target), 请手工调整 home.93gushi.com 解析"
    return 1
}

# 从公网 IP 自测入口端口. 安全组没放行时本机 127.0.0.1 仍是 200,
# 只有打公网 IP 才能暴露 ECS 安全组缺口 (2026-10-31 真实故障踩过).
entry_check() {
    local port code bad=""
    for port in 8088 18080; do
        code=$(curl -s -m 8 -o /dev/null -w '%{http_code}' \
            -H 'Host: home.93gushi.com' "http://$PUBLIC_IP:$port/api/auth/captcha")
        log "入口自测 $PUBLIC_IP:$port => $code"
        [ "$code" = 200 ] || bad="$bad $port($code)"
    done
    if [ -n "$bad" ]; then
        notify "入口端口不通:$bad — ECS 安全组未放行, DNS 已切但用户访问不到"
        return 1
    fi
    return 0
}

write_test() {
    local t="_erp_failover_probe_$$"
    qapp "CREATE TABLE industrial_erp.$t (id INT PRIMARY KEY) ENGINE=InnoDB" >/dev/null 2>&1 || return 1
    qapp "DROP TABLE industrial_erp.$t" >/dev/null 2>&1 || return 1
    return 0
}

fingerprint() {
    local t
    q "SELECT table_name FROM information_schema.tables
         WHERE table_schema='industrial_erp' AND table_type='BASE TABLE' ORDER BY table_name" \
    | while read -r t; do
        [ -n "$t" ] || continue
        printf '%s\t%s\n' "$t" "$(q "SELECT COUNT(*) FROM industrial_erp.\`$t\`")"
    done
}

# ---------------------------------------------------------------- 接管
promote() {
    local mode="${1:-auto}" ts prior io sql lag
    ts=$(date +%Y%m%d-%H%M%S)
    prior="$STATE_DIR/replica-status-$ts.txt"
    q "SHOW REPLICA STATUS\G" > "$prior" 2>&1
    io=$(awk -F': ' '/Replica_IO_Running:/{print $2}' "$prior" | tr -d '\r')
    sql=$(awk -F': ' '/Replica_SQL_Running:/{print $2}' "$prior" | tr -d '\r')
    lag=$(awk -F': ' '/Seconds_Behind_Source:/{print $2}' "$prior" | tr -d '\r')
    log "promote($mode): 切换前从库状态 io=$io sql=$sql lag=$lag (详见 $prior)"

    if ! q "STOP REPLICA" >/dev/null; then
        notify "接管中止: STOP REPLICA 失败, 未做任何变更"
        return 1
    fi
    log "promote: 已停止从库复制"
    q "SET GLOBAL super_read_only=0; SET GLOBAL read_only=0" >/dev/null
    local ro sro
    ro=$(q "SELECT @@global.read_only"); sro=$(q "SELECT @@global.super_read_only")
    if [ "$ro" != "0" ] || [ "$sro" != "0" ]; then
        notify "接管中止: 无法解除只读 (read_only=$ro super_read_only=$sro)"
        return 1
    fi
    if ! write_test; then
        notify "接管中止: 本地库以业务账号写入失败"
        return 1
    fi
    log "promote: 本地库已可写 (read_only=0)"

    patch_env local
    set_state failed_over "$mode"
    recreate_backend
    if ! wait_healthy; then
        notify "接管异常: 阿里云 backend 未在 120s 内healthy, 请立即人工介入 (数据源已切本地库)"
        return 1
    fi
    log "promote: backend 已切到本地 erp-mysql 并 healthy"
    dns_switch aliyun
    entry_check || true
    notify "已自动接管: 数据源=阿里云本地库, 入口=home.93gushi.com(8088/18080); 切换前复制延迟=${lag}s; 主站恢复后需人工回切"
    return 0
}

enforce() {
    local ro sro changed=0
    ro=$(q "SELECT @@global.read_only"); sro=$(q "SELECT @@global.super_read_only")
    if [ "$ro" != "0" ] || [ "$sro" != "0" ]; then
        q "SET GLOBAL super_read_only=0; SET GLOBAL read_only=0" >/dev/null
        log "enforce: 重新解除只读 (ro=$ro sro=$sro, 多为 MySQL 容器重启回落到 --read-only=ON)"
        changed=1
    fi
    if [ "$(backend_env_host)" != "$LOCAL_DB_HOST" ]; then
        patch_env local
        recreate_backend
        log "enforce: 数据源改回本地库并重建 backend"
        changed=1
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' erp-backend 2>/dev/null)" != true ]; then
        ( cd "$COMPOSE_DIR" && docker compose up -d --no-build erp-backend 2>>"$LOG" )
        log "enforce: backend 未运行, 已重新拉起"
        changed=1
    fi
    [ "$changed" = 1 ] && wait_healthy
    return 0
}

tick() {
    mkdir "$LOCK" 2>/dev/null || exit 0
    trap 'rmdir "$LOCK" 2>/dev/null' EXIT

    if [ "$(state)" = failed_over ]; then
        enforce
        if probe_primary; then
            local now prev
            now=$(date +%s); prev=$(stat -c %Y "$STATE_DIR/recover-alert-at" 2>/dev/null || echo 0)
            if [ $((now - prev)) -ge "$RECOVER_ALERT_EVERY" ]; then
                : > "$STATE_DIR/recover-alert-at"
                notify "主站(.150)已恢复, 阿里云仍在接管中; 请核对业务后人工执行回切"
            fi
        fi
        exit 0
    fi

    if probe_primary; then
        echo 0 > "$STATE_DIR/fail_count"
        exit 0
    fi
    local n
    n=$(( $(cat "$STATE_DIR/fail_count" 2>/dev/null || echo 0) + 1 ))
    echo "$n" > "$STATE_DIR/fail_count"
    log "主库探测失败 ($n/$THRESHOLD)"
    if [ "$n" -lt "$THRESHOLD" ]; then exit 0; fi

    sleep 15
    if probe_primary; then
        echo 0 > "$STATE_DIR/fail_count"
        log "复核探测已恢复, 取消接管"
        exit 0
    fi
    # 判定故障前先自愈隧道, 再给 30s 重连窗口: 只有真·主站不可达才接管
    heal_tunnel
    local i
    for i in $(seq 1 6); do
        sleep 5
        if probe_primary; then
            echo 0 > "$STATE_DIR/fail_count"
            log "隧道已自愈, 取消接管"
            exit 0
        fi
    done
    echo 0 > "$STATE_DIR/fail_count"
    promote auto
}

# ---------------------------------------------------------------- 回切辅助
freeze() {
    if [ "$(docker inspect -f '{{.State.Running}}' erp-backend 2>/dev/null)" = true ]; then
        docker stop -t 30 erp-backend >>"$LOG" 2>&1
        log "freeze: 已停止 backend"
    fi
    q "SET GLOBAL read_only=1; SET GLOBAL super_read_only=0" >/dev/null
    local ro
    ro=$(q "SELECT @@global.read_only")
    if [ "$ro" != "1" ]; then
        notify "回切冻结失败: read_only=$ro, 请人工处理"
        return 1
    fi
    set_state restoring
    log "freeze: 阿里云写入已冻结 (backend 停止 + read_only=1)"
    return 0
}

dump_db() {
    local dir="$COMPOSE_DIR/backup/failback" f
    mkdir -p "$dir"
    f="aliyun-$(date +%Y%m%d-%H%M%S).sql.gz"
    docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
        sh -c "mysqldump -uroot --default-character-set=utf8mb4 --single-transaction --quick \
               --routines --triggers --events --databases industrial_erp | gzip -1" \
        > "$dir/$f" || { notify "回切导出失败: mysqldump 出错"; return 1; }
    gzip -t "$dir/$f" 2>/dev/null || { notify "回切导出失败: $f 压缩包损坏"; return 1; }
    {
        echo "file=$f"
        echo "sha256=$(sha256sum "$dir/$f" | awk '{print $1}')"
        echo "bytes=$(stat -c %s "$dir/$f")"
        echo "created=$(date '+%F %T')"
        echo "-- fingerprint --"
        fingerprint
    } > "$dir/$f.meta"
    log "dump: $dir/$f ($(stat -c %s "$dir/$f") B)"
    say "$f"
    return 0
}

finish() {
    local mfile="$1" mpos="$2" io sql lag i
    [ -n "$mfile" ] && [ -n "$mpos" ] || { say "usage: $0 finish <master_log_file> <master_log_pos>"; return 2; }

    q "STOP REPLICA" >/dev/null
    if ! q "CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='$mfile', SOURCE_LOG_POS=$mpos" >/dev/null; then
        notify "回切失败: 无法把从库位点改到 $mfile:$mpos"
        return 1
    fi
    q "START REPLICA" >/dev/null
    # 连线是异步的, 单次读 4s 会误判; 轮询最多 30s
    for i in $(seq 1 10); do
        io=$(qv "SHOW REPLICA STATUS\G" | awk -F': ' '/Replica_IO_Running:/{print $2}')
        sql=$(qv "SHOW REPLICA STATUS\G" | awk -F': ' '/Replica_SQL_Running:/{print $2}')
        lag=$(qv "SHOW REPLICA STATUS\G" | awk -F': ' '/Seconds_Behind_Source:/{print $2}')
        [ "$io" = Yes ] && [ "$sql" = Yes ] && break
        sleep 3
    done
    if [ "$io" != Yes ] || [ "$sql" != Yes ]; then
        notify "回切失败: 从库未恢复 io=$io sql=$sql (数据已回 .150, 阿里云仍冻结; 复制已好时可用 finish-tail 补完收尾)"
        return 1
    fi
    log "finish: 复制已重建 io=$io sql=$sql lag=$lag"
    finish_tail
}

# 回切的收尾动作 (复制已恢复但 finish 因故中断时, 单独跑 erp-failover.sh finish-tail)
finish_tail() {
    q "SET GLOBAL read_only=1; SET GLOBAL super_read_only=0" >/dev/null
    patch_env tunnel
    recreate_backend
    if ! wait_healthy; then
        notify "回切异常: 阿里云 backend 未在 120s 内 healthy (数据源已回隧道), 请人工介入"
        return 1
    fi
    dns_switch home
    set_state normal
    notify "回切完成: 主站(.150)重新对外服务, 阿里云恢复为只读从库"
    return 0
}

status() {
    local ro sro st
    st=$(state); st=${st:-normal}
    ro=$(q "SELECT @@global.read_only"); sro=$(q "SELECT @@global.super_read_only")
    say "state          : $st"
    say "probe_primary  : $(probe_primary && echo OK || echo FAIL)"
    say "mysql read_only: $ro (super=$sro)"
    say "backend db host: $(backend_env_host)"
    say "backend health : $(docker inspect -f '{{.State.Health.Status}}' erp-backend 2>/dev/null)"
    say "入口 8088      : $(curl -s -m 5 -o /dev/null -w '%{http_code}' -H 'Host: home.93gushi.com' http://127.0.0.1:8088/api/auth/captcha)"
    say "fail_count     : $(cat "$STATE_DIR/fail_count" 2>/dev/null || echo 0)"
}

case "${1:-tick}" in
    tick)       tick ;;
    status)     status ;;
    promote)    promote "${2:-manual}" ;;
    enforce)    enforce ;;
    freeze)     freeze ;;
    dump)       dump_db ;;
    finish)     finish "${2:-}" "${3:-}" ;;
    finish-tail) finish_tail ;;
    fingerprint) fingerprint ;;
    *) say "usage: $0 {tick|status|promote|enforce|freeze|dump|finish <file> <pos>|finish-tail|fingerprint}"; exit 2 ;;
esac
