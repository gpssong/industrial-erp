#!/bin/bash
# =============================================================================
# erp-health-check.sh — P1-7 巡检脚本 (home / feiniu / aliyun 三种角色)
#
# 用法:  erp-health-check.sh home|feiniu|aliyun
# 配置:  /etc/erp-health.env (可选, 内部可设 ALERT_WEBHOOK=企业微信机器人地址)
# 行为:  每 10 分钟由 cron 触发; 失败时写日志, 状态变化或持续失败每小时告警一次,
#        恢复正常时发送一次恢复通知. 未配置 ALERT_WEBHOOK 时只写本地日志.
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

case "$ROLE" in
  home)
    check_http "主站 PC" "http://127.0.0.1/" 200
    check_http "主站 API" "http://127.0.0.1/api/auth/captcha" 200
    check_disk /volume3 85
    check_disk /volume1 85
    # 隧道容器必须存在: 阿里云写入与复制都依赖它
    if command -v docker >/dev/null 2>&1; then
        docker inspect -f '{{.State.Running}}' erp-tunnel 2>/dev/null | grep -q true \
            || fail "erp-tunnel 容器未运行"
    fi
    ;;
  feiniu)
    check_http "主站 PC (家里)" "http://192.168.0.150/" 200
    check_http "阿里云 API" "http://47.96.188.129/api/auth/captcha" 200
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
    # 本机 80 端口有多个 vhost, 必须带 Host 才是 ERP 站点
    check_http "PC" "http://127.0.0.1/" 200 "47.96.188.129"
    check_http "API" "http://127.0.0.1/api/auth/captcha" 200 "47.96.188.129"
    check_disk / 85
    mysql_replica_check erp-mysql "$(grep '^MYSQL_ROOT_PASSWORD=' /opt/erp-system/.env 2>/dev/null | cut -d= -f2-)" 60
    docker inspect -f '{{.State.Health.Status}}' erp-backend 2>/dev/null | grep -q healthy \
        || fail "erp-backend 不健康"
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
