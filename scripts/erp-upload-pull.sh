#!/bin/bash
# =============================================================================
# erp-upload-pull.sh — .150 主站从阿里云拉取上传文件 (root cron, 每 10 分钟)
#
# 背景: 阿里云 → .150 的推方向需要写 .150 上 999:999 的上传目录, gpssong 无权限;
#       改由 .150 root 反向拉取, 幂等 (--ignore-existing), 不回删.
# 注意: 阿里云侧 /root/erp-upload-sync.sh 只保留 .150 → 阿里云 的拉取方向.
# =============================================================================
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

KEY=/root/.ssh/erp-pull_ed25519
KH=/root/.ssh/erp_pull_known_hosts
SSH_CMD="ssh -i $KEY -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KH -o ConnectTimeout=15"
REMOTE=root@47.96.188.129:/opt/erp-system/upload/
LOCAL=/volume3/docker/erp-system/data/upload/
LOG=/var/log/erp-upload-pull.log
LOCK=/var/run/erp-upload-pull.lock

mkdir "$LOCK" 2>/dev/null || exit 0
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

{
  echo "[$(date '+%F %T')] start"
  rsync -rt --ignore-existing --no-o --no-g \
    --exclude='*.jar' --exclude='*.log' \
    -e "$SSH_CMD" --rsync-path=/usr/bin/rsync "$REMOTE" "$LOCAL"
  rc=$?
  echo "[$(date '+%F %T')] done rc=$rc"
} >> "$LOG" 2>&1

if [ -f "$LOG" ] && [ "$(stat -c%s "$LOG")" -gt 1048576 ]; then
  tail -n 300 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi
exit 0
