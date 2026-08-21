#!/bin/bash
# =============================================================
# DNS 自动切换脚本 (DNSPod + Cloudflare)
# 位置: /opt/industrial-erp/scripts/dns-switch.sh
# 作用: 主站故障时, 把 home.93gushi.com 解析切换到备用 IP
# =============================================================
set -e

# 配置
DOMAIN="${DOMAIN:-home.93gushi.com}"
RECORD_TYPE="${RECORD_TYPE:-A}"
RECORD_LINE="${RECORD_LINE:-默认}"
MAIN_IP="${MAIN_IP:-主站IP}"
BACKUP_IP="${BACKUP_IP:-飞牛IP}"
DNSPOD_TOKEN="${DNSPOD_TOKEN:-your_token}"
CLOUDFLARE_API_KEY="${CLOUDFLARE_API_KEY:-}"
CLOUDFLARE_ZONE_ID="${CLOUDFLARE_ZONE_ID:-}"
LOG_FILE="${LOG_FILE:-/var/log/dns-switch.log}"

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $1" | tee -a "$LOG_FILE"
}

# 当前解析 IP
get_current_ip() {
  dig +short "$DOMAIN" @8.8.8.8 2>/dev/null | head -1
}

# 切换到备用 IP (DNSPod)
switch_to_backup_dnspod() {
  log "切换到备用 IP: $BACKUP_IP (DNSPod)"

  # 获取记录 ID
  RECORD_ID=$(curl -s -X POST "https://dnsapi.cn/Record.List" \
    -d "login_token=$DNSPOD_TOKEN" \
    -d "format=json" \
    -d "domain=$DOMAIN" \
    -d "sub_domain=" \
    -d "record_type=$RECORD_TYPE" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['records'][0]['id'])")

  # 更新记录
  RESULT=$(curl -s -X POST "https://dnsapi.cn/Record.Modify" \
    -d "login_token=$DNSPOD_TOKEN" \
    -d "format=json" \
    -d "domain=$DOMAIN" \
    -d "sub_domain=" \
    -d "record_type=$RECORD_TYPE" \
    -d "record_line=$RECORD_LINE" \
    -d "record_id=$RECORD_ID" \
    -d "value=$BACKUP_IP" \
    -d "ttl=300")

  log "DNSPod 切换结果: $RESULT"
}

# 切换到备用 IP (Cloudflare)
switch_to_backup_cloudflare() {
  log "切换到备用 IP: $BACKUP_IP (Cloudflare)"

  RECORD_ID=$(curl -s -X GET "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/dns_records?type=$RECORD_TYPE&name=$DOMAIN" \
    -H "Authorization: Bearer $CLOUDFLARE_API_KEY" \
    -H "Content-Type: application/json" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['result'][0]['id'])")

  RESULT=$(curl -s -X PUT "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/dns_records/$RECORD_ID" \
    -H "Authorization: Bearer $CLOUDFLARE_API_KEY" \
    -H "Content-Type: application/json" \
    --data "{\"type\":\"$RECORD_TYPE\",\"name\":\"$DOMAIN\",\"content\":\"$BACKUP_IP\",\"ttl\":300,\"proxied\":false}")

  log "Cloudflare 切换结果: $RESULT"
}

# 切回主 IP
switch_to_main() {
  log "切回主 IP: $MAIN_IP"

  # 优先用 DNSPod
  if [ -n "$DNSPOD_TOKEN" ] && [ "$DNSPOD_TOKEN" != "your_token" ]; then
    RECORD_ID=$(curl -s -X POST "https://dnsapi.cn/Record.List" \
      -d "login_token=$DNSPOD_TOKEN" \
      -d "format=json" \
      -d "domain=$DOMAIN" \
      -d "sub_domain=" \
      -d "record_type=$RECORD_TYPE" \
      | python3 -c "import sys,json; print(json.load(sys.stdin)['records'][0]['id'])")

    curl -s -X POST "https://dnsapi.cn/Record.Modify" \
      -d "login_token=$DNSPOD_TOKEN" \
      -d "format=json" \
      -d "domain=$DOMAIN" \
      -d "sub_domain=" \
      -d "record_type=$RECORD_TYPE" \
      -d "record_line=$RECORD_LINE" \
      -d "record_id=$RECORD_ID" \
      -d "value=$MAIN_IP" \
      -d "ttl=600"
  fi
}

# 主入口
case "${1:-}" in
  to-backup)
    switch_to_backup_dnspod
    [ -n "$CLOUDFLARE_API_KEY" ] && switch_to_backup_cloudflare
    ;;
  to-main)
    switch_to_main
    ;;
  *)
    echo "Usage: $0 {to-backup|to-main}"
    exit 1
    ;;
esac
