#!/bin/bash
# =============================================================================
# erp-failback.sh — 人工确认回切: 把阿里云接管期间的新数据合并回 .150 并重建复制
#
# 部署位置: .150 /usr/local/bin/erp-failback.sh   (必须 root 执行)
# 用法:     erp-failback.sh --yes [--resume]
#
# 流程 (阿里云 backend 会停机, 预计 1-3 分钟不可用):
#   1. 阿里云冻结写入并停 backend
#   2. 阿里云 mysqldump 全库 → .150 拉回并校验 sha256
#   3. .150 先备份当前库, 再用阿里云 dump 覆盖恢复, 行数指纹逐表比对
#   4. .150 记录新主库位点 → 解冻并拉起 backend
#   5. 阿里云以该位点重建复制 → read_only=1 → 数据源回隧道 → DNS 切回
# =============================================================================
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

DOCKER=/usr/local/bin/docker
COMPOSE_DIR=/volume3/docker/erp-system
ENV_FILE=$COMPOSE_DIR/.env
FB_DIR=$COMPOSE_DIR/data/backup/failback
STATE_DIR=/var/lib/erp-failover-guard
LOG=$STATE_DIR/failback-$(date +%Y%m%d-%H%M%S).log
ALIYUN=root@47.96.188.129
KEY=/root/.ssh/erp-pull_ed25519
KH=/root/.ssh/erp_pull_known_hosts
SSH_CMD="ssh -i $KEY -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KH -o ConnectTimeout=10"
ASSUME_YES=0
RESUME=0

for a in "$@"; do
    case "$a" in
        --yes) ASSUME_YES=1 ;;
        --resume) RESUME=1 ;;
        *) echo "unknown arg: $a" >&2; exit 2 ;;
    esac
done

mkdir -p "$STATE_DIR" "$FB_DIR"; chmod 700 "$STATE_DIR"
[ -f /etc/erp-health.env ] && . /etc/erp-health.env
ALERT_WEBHOOK="${ALERT_WEBHOOK:-}"

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }
say() { printf '%s\n' "$*" | tee -a "$LOG"; }
die() { say "失败: $*"; notify "回切失败: $*"; exit 1; }

notify() {
    log "ALERT: $*"
    [ -n "$ALERT_WEBHOOK" ] || return 0
    local esc body
    esc=$(printf '%s' "$*" | sed 's/\\/\\\\/g; s/"/\\"/g')
    body="{\"msgtype\":\"text\",\"text\":{\"content\":\"[ERP回切] $esc\"}}"
    curl -s -m 8 -H 'Content-Type: application/json' -d "$body" "$ALERT_WEBHOOK" >/dev/null 2>&1 || true
}

aliyun() { $SSH_CMD "$ALIYUN" "$@" 2>>"$LOG"; }
aliyun_state() { $SSH_CMD "$ALIYUN" 'head -n1 /var/lib/erp-failover/state 2>/dev/null' 2>/dev/null | awk '{print $1}'; }

ROOT_PWD=$(grep '^MYSQL_ROOT_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2-)
mysql_root() { "$DOCKER" exec -e MYSQL_PWD="$ROOT_PWD" erp-mysql mysql -uroot -N -B -e "$1" 2>>"$LOG" | tr -d '\r'; }
fingerprint() {
    local t
    mysql_root "SELECT table_name FROM information_schema.tables
                  WHERE table_schema='industrial_erp' AND table_type='BASE TABLE' ORDER BY table_name" \
    | while read -r t; do
        [ -n "$t" ] || continue
        printf '%s\t%s\n' "$t" "$(mysql_root "SELECT COUNT(*) FROM industrial_erp.\`$t\`")"
    done
}
wait_healthy() {
    local i
    for i in $(seq 1 40); do
        if [ "$("$DOCKER" inspect -f '{{.State.Health.Status}}' erp-backend 2>/dev/null)" = healthy ] \
           && [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1/api/auth/captcha)" = 200 ]; then
            return 0
        fi
        sleep 3
    done
    return 1
}

[ "$(id -u)" = 0 ] || { echo "请用 root 执行" >&2; exit 2; }

STATE=$(aliyun_state)
say "阿里云接管状态: ${STATE:-<不可读>}"
if [ "$STATE" = restoring ] && [ "$RESUME" = 1 ]; then
    say "resume 模式: 阿里云处于 restoring, 继续"
elif [ "$STATE" != failed_over ]; then
    die "阿里云状态为 ${STATE:-<不可读>}, 不是 failed_over, 无需回切"
fi

if [ "$ASSUME_YES" = 0 ]; then
    cat <<EOF
将要执行回切, 期间阿里云停服 1-3 分钟:
  阿里云冻结 → 导出 → .150 备份并覆盖恢复 → 重建复制 → DNS 切回 home
确认无误请加 --yes 重跑。日志目录: $STATE_DIR
EOF
    exit 1
fi

say "=== Step 1: 阿里云冻结写入 ==="
if [ "$RESUME" = 0 ]; then
    aliyun /usr/local/bin/erp-failover.sh freeze >/dev/null \
        || die "阿里云冻结失败, 未做任何变更"
else
    say "resume 模式: 跳过冻结"
fi

say "=== Step 2: 阿里云导出 ==="
if [ "$RESUME" = 0 ]; then
    DUMP=$(aliyun /usr/local/bin/erp-failover.sh dump) || die "阿里云导出失败"
    [ -n "$DUMP" ] || die "阿里云导出未返回文件名"
else
    DUMP=$(ls -1t "$FB_DIR"/aliyun-*.sql.gz 2>/dev/null | head -1)
    DUMP=$(basename "${DUMP:-}")
    [ -n "$DUMP" ] || die "resume 模式下没有可用 dump"
    say "resume 模式: 复用 $DUMP"
fi
say "dump: $DUMP"

say "=== Step 3: 拉回并校验 ==="
rsync -t --no-o --no-g -e "$SSH_CMD" \
    "$ALIYUN:/opt/erp-system/backup/failback/$DUMP" "$FB_DIR/" >>"$LOG" 2>&1 \
    || die "rsync 拉取 $DUMP 失败"
rsync -t --no-o --no-g -e "$SSH_CMD" \
    "$ALIYUN:/opt/erp-system/backup/failback/$DUMP.meta" "$FB_DIR/" >>"$LOG" 2>&1 \
    || die "rsync 拉取校验旁注失败"
WANT=$(awk -F= '/^sha256=/{print $2}' "$FB_DIR/$DUMP.meta")
GOT=$(sha256sum "$FB_DIR/$DUMP" | awk '{print $1}')
[ -n "$WANT" ] && [ "$WANT" = "$GOT" ] || die "dump 校验失败 want=$WANT got=$GOT"
say "sha256 校验通过: $GOT"

say "=== Step 4: 备份 .150 当前库 ==="
PRE="$FB_DIR/pre-failback-$(date +%Y%m%d-%H%M%S).sql.gz"
"$DOCKER" exec -e MYSQL_PWD="$ROOT_PWD" erp-mysql \
    sh -c "mysqldump -uroot --default-character-set=utf8mb4 --single-transaction --quick \
           --routines --triggers --events --databases industrial_erp | gzip -1" \
    > "$PRE" 2>>"$LOG" || die ".150 备份失败, 已中止"
gzip -t "$PRE" 2>/dev/null || die ".150 备份文件损坏: $PRE"
say "已备份: $PRE ($(stat -c %s "$PRE") B)"

say "=== Step 5: 覆盖恢复 ==="
mysql_root "SET GLOBAL super_read_only=0; SET GLOBAL read_only=0" >/dev/null \
    || die "无法解除 .150 只读"
gunzip -c "$FB_DIR/$DUMP" \
    | "$DOCKER" exec -i -e MYSQL_PWD="$ROOT_PWD" erp-mysql \
        mysql -uroot --default-character-set=utf8mb4 2>>"$LOG"
[ "${PIPESTATUS[1]}" = 0 ] || die "导入失败, .150 库可能不完整, 请用 $PRE 还原"
say "导入完成"

say "=== Step 6: 行数指纹比对 ==="
awk '/^-- fingerprint --$/{f=1;next} f' "$FB_DIR/$DUMP.meta" | grep -v '^$' > "$FB_DIR/.want.fp"
fingerprint > "$FB_DIR/.got.fp"
if ! diff -q "$FB_DIR/.want.fp" "$FB_DIR/.got.fp" >/dev/null; then
    diff "$FB_DIR/.want.fp" "$FB_DIR/.got.fp" | head -20 >>"$LOG"
    die "指纹不一致 (明细见日志), 请人工核对后再 finish"
fi
say "指纹一致 ($(wc -l < "$FB_DIR/.got.fp") 张表)"

say "=== Step 7: 记录主库位点 ==="
mysql_root "FLUSH BINARY LOGS" >/dev/null || die "FLUSH BINARY LOGS 失败"
POS_LINE=$(mysql_root "SHOW MASTER STATUS" | head -1)
MFILE=$(printf '%s' "$POS_LINE" | awk '{print $1}')
MPOS=$(printf '%s' "$POS_LINE" | awk '{print $2}')
[ -n "$MFILE" ] && [ -n "$MPOS" ] || die "取主库位点失败: $POS_LINE"
say "主库位点: $MFILE:$MPOS"

say "=== Step 8: .150 解冻并拉起 backend ==="
rm -f "$STATE_DIR/frozen"
mysql_root "SET GLOBAL super_read_only=0; SET GLOBAL read_only=0" >/dev/null
"$DOCKER" start erp-backend >>"$LOG" 2>&1 || die "启动 .150 backend 失败"
wait_healthy || die ".150 backend 120s 内未 healthy"
say ".150 已恢复服务"

say "=== Step 9: 阿里云重建复制并切回隧道 ==="
aliyun /usr/local/bin/erp-failover.sh finish "$MFILE" "$MPOS" >/dev/null \
    || die "阿里云 finish 失败 (数据已回 .150, 阿里云仍冻结, 未切 DNS)"

say "=== Step 10: 复核 ==="
aliyun /usr/local/bin/erp-failover.sh status | tee -a "$LOG"
DNS_IP=$(getent hosts home.93gushi.com 2>/dev/null | awk '{print $1}' | head -1)
[ -n "$DNS_IP" ] || DNS_IP=$(nslookup home.93gushi.com 2>/dev/null | awk '/^Address: /{print $2}' | tail -1)
say "home.93gushi.com → ${DNS_IP:-<解析失败>}"
notify "回切完成: .150 已恢复主站服务, 阿里云恢复只读从库 (位点 $MFILE:$MPOS); 日志 $LOG"
say "完成。回切包保留在 $FB_DIR (含 .150 回切前备份 $PRE)"
