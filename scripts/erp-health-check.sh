#!/bin/bash
# =============================================================================
# erp-health-check.sh — 巡检脚本 (home / feiniu / aliyun 三种角色)
#
# 拓扑口径 (2026-11-10 反转后):
#   home   = .150 群晖   主站 + MySQL 主库 (可写, server_id=1)
#   aliyun = 阿里云      只读热备 (server_id=3, ro/sro=1), nginx 反代桥接回 .150
#   feiniu = .32 飞牛    备份站 (server_id=2), 链式复制 .150 -> 阿里云 -> .32
# 不自动接管: erp-failover-guard 保持停用, 本脚本只告警。
#
# 用法:  erp-health-check.sh home|feiniu|aliyun
# 配置:  /etc/erp-health.env (可选, 内部可设 ALERT_WEBHOOK=企业微信机器人地址)
# 行为:  每 10 分钟由 cron 触发; 失败时写日志, 状态变化或持续失败每小时告警一次,
#        恢复正常时发送一次恢复通知. 未配置 ALERT_WEBHOOK 时只写本地日志.
# 2026-11-16: 新增 pc-web 假死自愈 (home 角色; 30 分钟内最多自动重启一次).
# =============================================================================
set -u
export PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin

ROLE="${1:-}"
HOST=$(hostname 2>/dev/null || echo unknown)
STATE_DIR="/tmp/erp-health-${ROLE}"
LOG_FILE="${STATE_DIR}/health.log"
STATE_FILE="${STATE_DIR}/state"
mkdir -p "$STATE_DIR"
[ -f /etc/erp-health.env ] && . /etc/erp-health.env
ALERT_WEBHOOK="${ALERT_WEBHOOK:-}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG_FILE"; }

FAILS=()

fail() { FAILS+=("$1"); }

send_alert() {
    local msg="$1"
    if [ -z "$ALERT_WEBHOOK" ]; then
        log "ALERT(未配置 webhook): $msg"
        return
    fi
    local body esc
    esc=${msg//\\/\\\\}
    esc=${esc//\"/\\\"}
    body="{\"msgtype\":\"text\",\"text\":{\"content\":\"$esc\"}}"
    curl -s -m 8 -H 'Content-Type: application/json' -d "$body" "$ALERT_WEBHOOK" >/dev/null 2>&1 \
        && log "ALERT 已发送: $msg" || log "ALERT 发送失败: $msg"
}

http_code() {
    curl -s -m 8 -o /dev/null -w '%{http_code}' "$1" 2>/dev/null
}

check_http() {
    local name="$1" url="$2" expect="$3" host="${4:-}" code
    if [ -n "$host" ]; then
        code=$(curl -s -m 8 -H "Host: $host" -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)
    else
        code=$(http_code "$url")
    fi
    [ "$code" = "$expect" ] || fail "$name 不可用 (HTTP $code, 期望 $expect) $url"
}

check_disk() {
    local path="$1" max="$2" used
    used=$(df -P "$path" 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')
    [ -n "$used" ] || { fail "磁盘检查失败: $path 不存在"; return; }
    [ "$used" -lt "$max" ] || fail "磁盘使用率 ${used}% ≥ ${max}% ($path)"
}

mysql_replica_check() {
    # $1=docker容器名 $2=root密码 $3=允许延迟秒
    local c="$1" p="$2" maxlag="$3" out io sql lag
    out=$(docker exec -e MYSQL_PWD="$p" "$c" mysql -uroot -e \
        "SHOW REPLICA STATUS\G" 2>/dev/null)
    [ -n "$out" ] || { fail "无法读取复制状态 ($c)"; return; }
    io=$(printf '%s\n' "$out" | awk -F': ' '/Replica_IO_Running:/ {print $2}')
    sql=$(printf '%s\n' "$out" | awk -F': ' '/Replica_SQL_Running:/ {print $2}')
    lag=$(printf '%s\n' "$out" | awk -F': ' '/Seconds_Behind_Source:/ {print $2}')
    [ "$io" = "Yes" ] || fail "复制 IO 线程停止 ($c)"
    [ "$sql" = "Yes" ] || fail "复制 SQL 线程停止 ($c)"
    if [ -n "$lag" ] && [ "$lag" != "NULL" ] && [ "$lag" -gt "$maxlag" ]; then
        fail "复制延迟 ${lag}s > ${maxlag}s ($c)"
    fi
}

# 只认 ERP 自己的 JSON: DSM/静态站对任意路径都回 200 text/html, 单看状态码会长期假 OK
check_api() {
    local name="$1" url="$2" host="${3:-}" body code
    if [ -n "$host" ]; then
        body=$(curl -s -m 8 -H "Host: $host" -w '\n@@HTTP@@%{http_code}' "$url" 2>/dev/null)
    else
        body=$(curl -s -m 8 -w '\n@@HTTP@@%{http_code}' "$url" 2>/dev/null)
    fi
    code=${body##*@@HTTP@@}
    body=${body%@@HTTP@@*}
    if [ "$code" != "200" ]; then
        fail "$name 不可用 (HTTP $code, 期望 200) $url"
    elif ! printf '%s' "$body" | grep -q '"captchaKey"'; then
        fail "$name 回了 200 但不是 ERP 验证码 JSON (疑似 DSM/静态页兜底) $url"
    fi
}

# 密码取容器自身 env, 不落盘、不进进程参数、不回显
mysql_q() {
    docker exec "$1" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -e "$1"' sh "$2" 2>/dev/null
}

mysql_writable_check() {
    # $1=容器名 $2=期望的 read_only+super_read_only (主库 00 / 热备 11) $3=角色名
    local c="$1" want="$2" role="$3" flags
    flags=$(mysql_q "$c" "SELECT CONCAT(@@read_only,@@super_read_only) AS f" | awk 'NR==2{print $1}')
    [ -n "$flags" ] || { fail "无法读取 MySQL $role 只读标志 ($c)"; return; }
    [ "$flags" = "$want" ] || fail "MySQL $role 只读标志=$flags, 期望 $want ($c)"
}

mysql_downstream_check() {
    # SHOW REPLICAS 的 Host 列是空的, 默认 awk 会把 Port 挤到 $2; 只按 $1=Server_Id 判
    local c="$1" want="$2" out
    out=$(mysql_q "$c" "SHOW REPLICAS")
    [ -n "$out" ] || { fail "无法读取下游复制列表 ($c)"; return; }
    printf '%s\n' "$out" | awk -v w="$want" 'NR>1 && $1==w {f=1} END{exit !f}' \
        || fail "下游 (server_id=$want) 未挂到主库 ($c)"
}

backend_running_healthy() {
    # exited 容器会保留最后一次 Health.Status=healthy, 必须同时判 Running
    docker inspect -f '{{.State.Running}} {{.State.Health.Status}}' "$1" 2>/dev/null \
        | grep -q '^true healthy$'
}

# 2026-11-16: pc-web 假死自愈。
# 11-16 的事故里容器一直是 Up, 只是 nginx 不响应, docker 的 restart:unless-stopped 不会触发,
# 只能人工登录处理。巡检这里补一次自动重启 (30 分钟窗口内只重启一次, 避免和人工操作打架)。
pcweb_state() {
    # docker 卡死时 inspect 也会阻塞, 必须带超时
    timeout 15 docker inspect -f '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' erp-pc-web 2>/dev/null
}

pcweb_guard() {
    local st now last i
    st=$(pcweb_state)
    [ "$st" = "true healthy" ] && return 0
    now=$(date +%s); last=$(cat "$STATE_DIR/pcweb-restart-at" 2>/dev/null || echo 0)
    if [ $((now - last)) -lt 1800 ]; then
        fail "erp-pc-web 异常 ($st), 30 分钟内已自动重启过, 本次不再重启"
        return 0
    fi
    log "erp-pc-web 异常 ($st), 自动重启"
    printf '%s' "$now" > "$STATE_DIR/pcweb-restart-at"
    timeout 60 docker restart erp-pc-web >>"$LOG_FILE" 2>&1 \
        || { fail "erp-pc-web 自动重启失败或超时 ($st)"; return 0; }
    for i in $(seq 1 20); do
        sleep 3
        st=$(pcweb_state)
        [ "$st" = "true healthy" ] && { log "erp-pc-web 自动重启后恢复健康"; return 0; }
    done
    fail "erp-pc-web 自动重启后仍异常 ($st)"
}

case "$ROLE" in
  home)
    # .150 = 主站。80 端口是 DSM (对 /api/auth/captcha 也回 200 text/html), ERP 真入口是 8088
    check_http "主站 PC" "http://127.0.0.1:8088/" 200
    check_api  "主站 API" "http://127.0.0.1:8088/api/auth/captcha"
    check_disk /volume3 85
    check_disk /volume1 85
    mysql_writable_check erp-mysql 00 "主库"
    mysql_downstream_check erp-mysql 3
    backend_running_healthy erp-backend || fail "erp-backend 未运行或不健康 (主站)"
    # pc-web 假死 (容器 Up 但不响应) 时自动重启一次
    pcweb_guard
    # 隧道容器必须存在: 阿里云热备的复制与附件同步都依赖它
    docker inspect -f '{{.State.Running}}' erp-tunnel 2>/dev/null | grep -q true \
        || fail "erp-tunnel 容器未运行"
    ;;
  feiniu)
    # 主站入口是 .150:8088, 不是 DSM 的 80
    check_http "主站 PC (家里)" "http://192.168.0.150:8088/" 200
    check_api  "主站 API (家里)" "http://192.168.0.150:8088/api/auth/captcha"
    check_api  "阿里云 API (热备桥接)" "http://47.96.188.129/api/auth/captcha"
    check_disk /vol1 85
    check_disk /volume2 85
    mysql_replica_check erp-mysql-failover "$(cat /home/gpssong/erp-backup/mysql_password 2>/dev/null)" 60
    # 备份新鲜度: 26 小时内必须有新备份 (03:30 任务, 留 2 小时缓冲)
    BACKUP_DIR="/vol1/1000/mac使用/erp/数据备份"
    [ -n "$(find "$BACKUP_DIR" -maxdepth 1 -name 'industrial_erp_*.sql.gz' -mmin -1560 2>/dev/null | head -1)" ] \
        || fail "备份已超过 26 小时未更新 ($BACKUP_DIR)"
    docker inspect -f '{{.State.Health.Status}}' erp-backend-failover 2>/dev/null | grep -q healthy \
        || fail "erp-backend-failover 不健康"
    ;;
  aliyun)
    # 阿里云 = 只读热备。本机 80 有多个 vhost, 必须带 Host; nginx 已反代回 .150,
    # 所以这两条同时验证「桥接还在」——桥接断了 API 会打到本机停着的 backend 变 502
    check_http "热备 PC (桥接回 .150)" "http://127.0.0.1/" 200 "47.96.188.129"
    check_api  "热备 API (桥接回 .150)" "http://127.0.0.1/api/auth/captcha" "47.96.188.129"
    check_disk / 85
    mysql_writable_check erp-mysql 11 "热备"
    mysql_replica_check erp-mysql "$(grep '^MYSQL_ROOT_PASSWORD=' /opt/erp-system/.env 2>/dev/null | cut -d= -f2-)" 60
    # 热备上 backend 应当停着; 只查 Health.Status 会因 exited 容器保留 healthy 而假 OK
    docker inspect -f '{{.State.Running}}' erp-backend 2>/dev/null | grep -q '^false$' \
        || fail "erp-backend 不应在只读热备上运行 (人工接管时需同步改本巡检口径)"
    ;;
  *)
    echo "usage: $0 home|feiniu|aliyun" >&2
    exit 2
    ;;
esac

NOW=$(date +%s)
PREV=$(cat "$STATE_FILE" 2>/dev/null || echo "")
PREV_AT=$(stat -c %Y "$STATE_FILE" 2>/dev/null || echo 0)

if [ "${#FAILS[@]}" -eq 0 ]; then
    if [ -n "$PREV" ]; then
        send_alert "[ERP恢复] $ROLE@$HOST 全部检查恢复正常"
    fi
    : > "$STATE_FILE"
    log "OK"
    exit 0
fi

MSG="[ERP告警] $ROLE@$HOST"
for f in "${FAILS[@]}"; do MSG="$MSG | $f"; done
CUR=$(printf '%s\n' "${FAILS[@]}" | sort | tr '\n' ';')

if [ "$CUR" != "$PREV" ] || [ $((NOW - PREV_AT)) -ge 3600 ]; then
    send_alert "$MSG"
else
    log "FAIL(未重复告警): $CUR"
fi
printf '%s' "$CUR" > "$STATE_FILE"
exit 1
