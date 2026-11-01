#!/bin/bash
# =============================================================================
# erp-failover-guard.sh — .150 主站在阿里云接管期间的写保护守卫
#
# 部署位置: .150 /usr/local/bin/erp-failover-guard.sh  (root cron, 每 2 分钟)
#
# 问题: .150 故障恢复后 backend 会自动重连本地库继续写, 而此刻阿里云仍在对外
#       提供服务 → 两边各写各的 = 脑裂. 本脚本轮询阿里云接管状态:
#         failed_over → 立刻把 .150 库置只读并停掉 backend (冻结)
#         normal      → 解冻并拉起 backend (回切收尾后)
#       restoring 等过渡态 → 不动作 (回切脚本正在合并数据)
#       阿里云不可达 → 不动作 (.150 仍是唯一站点, 不能自冻)
# =============================================================================
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

DOCKER=/usr/local/bin/docker
COMPOSE_DIR=/volume3/docker/erp-system
ENV_FILE=$COMPOSE_DIR/.env
STATE_DIR=/var/lib/erp-failover-guard
LOG=$STATE_DIR/guard.log
FLAG=$STATE_DIR/frozen
LOCK=$STATE_DIR/.lock
ALIYUN=root@47.96.188.129
KEY=/root/.ssh/erp-pull_ed25519
KH=/root/.ssh/erp_pull_known_hosts

mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR"
[ -f /etc/erp-health.env ] && . /etc/erp-health.env
ALERT_WEBHOOK="${ALERT_WEBHOOK:-}"

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

notify() {
    log "ALERT: $*"
    [ -n "$ALERT_WEBHOOK" ] || return 0
    local esc body
    esc=$(printf '%s' "$*" | sed 's/\\/\\\\/g; s/"/\\"/g')
    body="{\"msgtype\":\"text\",\"text\":{\"content\":\"[ERP守卫] $esc\"}}"
    curl -s -m 8 -H 'Content-Type: application/json' -d "$body" "$ALERT_WEBHOOK" >/dev/null 2>&1 || true
}

mysql_root() {
    local pwd
    pwd=$(grep '^MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2-)
    "$DOCKER" exec -e MYSQL_PWD="$pwd" erp-mysql mysql -uroot -N -B -e "$1" 2>&1 | tr -d '\r'
}

aliyun_state() {
    ssh -i "$KEY" -o BatchMode=yes -o StrictHostKeyChecking=yes \
        -o UserKnownHostsFile="$KH" -o ConnectTimeout=8 \
        "$ALIYUN" 'head -n1 /var/lib/erp-failover/state 2>/dev/null' 2>/dev/null | awk '{print $1}'
}

freeze() {
    local ro
    ro=$(mysql_root "SELECT @@global.read_only")
    if [ "$ro" != "1" ]; then
        mysql_root "SET GLOBAL super_read_only=1; SET GLOBAL read_only=1" >/dev/null
        log "freeze: .150 库已置只读 (原 read_only=$ro)"
    fi
    if [ "$("$DOCKER" inspect -f '{{.State.Running}}' erp-backend 2>/dev/null)" = true ]; then
        "$DOCKER" stop -t 30 erp-backend >>"$LOG" 2>&1
        log "freeze: 已停止 .150 backend, 防止脑裂写入"
    fi
    : > "$FLAG"
    notify "主站已冻结写入: 阿里云正在接管, .150 backend 已停止; 请勿在 .150 手工改数据, 恢复后走回切流程"
}

unfreeze() {
    mysql_root "SET GLOBAL super_read_only=0; SET GLOBAL read_only=0" >/dev/null
    if [ "$("$DOCKER" inspect -f '{{.State.Running}}' erp-backend 2>/dev/null)" != true ]; then
        "$DOCKER" start erp-backend >>"$LOG" 2>&1
    fi
    rm -f "$FLAG"
    log "unfreeze: .150 已解冻并拉起 backend"
    notify "主站已解冻恢复服务: .150 backend 已启动, 库可写"
}

main() {
    mkdir "$LOCK" 2>/dev/null || exit 0
    trap 'rmdir "$LOCK" 2>/dev/null' EXIT

    local st
    st=$(aliyun_state)
    if [ -z "$st" ]; then
        log "阿里云状态不可读, 不动作 (本机视为唯一站点)"
        exit 0
    fi

    case "$st" in
        failed_over)
            if [ -f "$FLAG" ]; then
                log "state=failed_over, 已处于冻结状态"
            else
                freeze
            fi
            ;;
        normal)
            if [ -f "$FLAG" ]; then
                unfreeze
            else
                log "state=normal, 正常"
            fi
            ;;
        *)
            log "state=$st, 过渡态不动作"
            ;;
    esac
}

main
