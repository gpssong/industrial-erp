#!/bin/bash
# =============================================================
# DNS 自动切换脚本 (DNSPod + Cloudflare + 阿里云 DNS)
# 位置: /opt/industrial-erp/scripts/dns-switch.sh (主 NAS)
#       /volume2/erp-system/dns-switch.sh (飞牛 NAS)
# 作用: 主站故障时, 把 home.93gushi.com 解析切换到备用 IP
# 服务商: 阿里云 DNS (Alidns)
# =============================================================
set -e

# 阿里云 DNS 配置
DOMAIN="${DOMAIN:-home.93gushi.com}"
RR_KEYWORD="${RR_KEYWORD:-home}"     # 主机记录, @ 表示主域
RECORD_TYPE="${RECORD_TYPE:-A}"
RR_LINE="${RR_LINE:-default}"         # 解析线路: default/telecom/unicom/mobile
TTL="${TTL:-300}"

# 阿里云 DNS AK/SK
ALIYUN_AK="${ALIYUN_AK:-your_access_key}"
ALIYUN_SK="${ALIYUN_SK:-your_secret_key}"
ALIYUN_REGION="${ALIYUN_REGION:-cn-hangzhou}"

# IP 配置
MAIN_IP="${MAIN_IP:-主站IP}"
BACKUP_IP="${BACKUP_IP:-飞牛IP}"

# 兼容 DNSPod / Cloudflare (可选)
DNSPOD_TOKEN="${DNSPOD_TOKEN:-}"
CLOUDFLARE_API_KEY="${CLOUDFLARE_API_KEY:-}"
CLOUDFLARE_ZONE_ID="${CLOUDFLARE_ZONE_ID:-}"

LOG_FILE="${LOG_FILE:-/var/log/dns-switch.log}"

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $1" | tee -a "$LOG_FILE"
}

# =============================================================
# 阿里云 DNS API (Alidns)
# =============================================================

# 阿里云 API 签名
aliyun_sign() {
  local METHOD="$1"
  local URL="$2"
  local QUERY="$3"
  local BODY="${4:-}"

  # 公共参数
  local COMMON="AccessKeyId=${ALIYUN_AK}&Format=JSON&SignatureMethod=HMAC-SHA1&SignatureNonce=$(date +%s%N)&SignatureVersion=1.0&Timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

  # 构造待签名字符串
  local SORTED_QUERY=$(echo -e "${COMMON}&${QUERY}" | tr '&' '\n' | sort | tr '\n' '&' | sed 's/&$//')
  local PARAM_ESC=$(echo -n "$SORTED_QUERY" | python3 -c '
import sys, urllib.parse
s = sys.stdin.read().strip()
parts = s.split("&")
result = []
for p in parts:
    k, v = p.split("=", 1)
    result.append(f"{urllib.parse.quote(k, safe=\"\")}={urllib.parse.quote(v, safe=\"\")}")
print("&".join(result))
')

  local STRING_TO_SIGN="${METHOD}&%2F&$(echo -n "$PARAM_ESC" | python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=""))')"

  # HMAC-SHA1 签名
  local SIGNATURE=$(echo -n "$STRING_TO_SIGN" | openssl dgst -sha1 -hmac "${ALIYUN_SK}&" | awk '{print $2}')

  echo "${SIGNATURE}"
}

# 阿里云: 获取解析记录 ID
aliyun_get_record_id() {
  local RR="$1"
  local ACTION="DescribeDomainRecords"
  local QUERY="Action=${ACTION}&DomainName=${DOMAIN}&RRKeyWord=${RR}&Type=${RECORD_TYPE}"

  local SIGNATURE=$(aliyun_sign "GET" "/" "$QUERY")

  curl -s "https://alidns.aliyuncs.com/?${QUERY}&Signature=${SIGNATURE}" \
    | python3 -c "
import sys, json
data = json.load(sys.stdin)
for record in data.get('DomainRecords', {}).get('Record', []):
    if record.get('RR') == '${RR}' and record.get('Type') == '${RECORD_TYPE}':
        print(record['RecordId'])
        break
"
}

# 阿里云: 更新解析记录
aliyun_update_record() {
  local RR="$1"
  local TARGET_IP="$2"

  local RECORD_ID=$(aliyun_get_record_id "$RR")
  if [ -z "$RECORD_ID" ]; then
    log "ERROR" "获取 RecordId 失败: $RR"
    return 1
  fi

  log "INFO" "阿里云更新解析: $RR -> $TARGET_IP (RecordId=$RECORD_ID)"

  local ACTION="UpdateDomainRecord"
  local QUERY="Action=${ACTION}&RecordId=${RECORD_ID}&RR=${RR}&Type=${RECORD_TYPE}&Value=${TARGET_IP}&TTL=${TTL}"

  local SIGNATURE=$(aliyun_sign "GET" "/" "$QUERY")

  local RESULT=$(curl -s "https://alidns.aliyuncs.com/?${QUERY}&Signature=${SIGNATURE}")

  log "INFO" "阿里云更新结果: $RESULT"

  # 检查返回
  echo "$RESULT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
if 'RecordId' in data:
    print('SUCCESS: RecordId=' + data['RecordId'])
else:
    print('FAILED: ' + json.dumps(data))
    exit(1)
"

  return $?
}

# 阿里云主入口
switch_to_backup_aliyun() {
  log "INFO" "切换到备用 IP: $BACKUP_IP (阿里云 DNS)"
  aliyun_update_record "$RR_KEYWORD" "$BACKUP_IP"
}

switch_to_main_aliyun() {
  log "INFO" "切回主 IP: $MAIN_IP (阿里云 DNS)"
  aliyun_update_record "$RR_KEYWORD" "$MAIN_IP"
}

# =============================================================
# DNSPod (兼容)
# =============================================================
switch_to_backup_dnspod() {
  [ -z "$DNSPOD_TOKEN" ] && return 0
  log "INFO" "切换到备用 IP: $BACKUP_IP (DNSPod)"

  RECORD_ID=$(curl -s -X POST "https://dnsapi.cn/Record.List" \
    -d "login_token=$DNSPOD_TOKEN" \
    -d "format=json" \
    -d "domain=$DOMAIN" \
    -d "sub_domain=$RR_KEYWORD" \
    -d "record_type=$RECORD_TYPE" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['records'][0]['id'])")

  curl -s -X POST "https://dnsapi.cn/Record.Modify" \
    -d "login_token=$DNSPOD_TOKEN" \
    -d "format=json" \
    -d "domain=$DOMAIN" \
    -d "sub_domain=$RR_KEYWORD" \
    -d "record_type=$RECORD_TYPE" \
    -d "record_line=$RR_LINE" \
    -d "record_id=$RECORD_ID" \
    -d "value=$BACKUP_IP" \
    -d "ttl=$TTL"
}

switch_to_main_dnspod() {
  [ -z "$DNSPOD_TOKEN" ] && return 0

  RECORD_ID=$(curl -s -X POST "https://dnsapi.cn/Record.List" \
    -d "login_token=$DNSPOD_TOKEN" \
    -d "format=json" \
    -d "domain=$DOMAIN" \
    -d "sub_domain=$RR_KEYWORD" \
    -d "record_type=$RECORD_TYPE" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['records'][0]['id'])")

  curl -s -X POST "https://dnsapi.cn/Record.Modify" \
    -d "login_token=$DNSPOD_TOKEN" \
    -d "format=json" \
    -d "domain=$DOMAIN" \
    -d "sub_domain=$RR_KEYWORD" \
    -d "record_type=$RECORD_TYPE" \
    -d "record_line=$RR_LINE" \
    -d "record_id=$RECORD_ID" \
    -d "value=$MAIN_IP" \
    -d "ttl=600"
}

# =============================================================
# Cloudflare (兼容)
# =============================================================
switch_to_backup_cloudflare() {
  [ -z "$CLOUDFLARE_API_KEY" ] && return 0
  log "INFO" "切换到备用 IP: $BACKUP_IP (Cloudflare)"

  RECORD_ID=$(curl -s "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/dns_records?type=$RECORD_TYPE&name=$DOMAIN" \
    -H "Authorization: Bearer $CLOUDFLARE_API_KEY" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['result'][0]['id'])")

  curl -s -X PUT "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/dns_records/$RECORD_ID" \
    -H "Authorization: Bearer $CLOUDFLARE_API_KEY" \
    -H "Content-Type: application/json" \
    --data "{\"type\":\"$RECORD_TYPE\",\"name\":\"$DOMAIN\",\"content\":\"$BACKUP_IP\",\"ttl\":300,\"proxied\":false}"
}

switch_to_main_cloudflare() {
  [ -z "$CLOUDFLARE_API_KEY" ] && return 0

  RECORD_ID=$(curl -s "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/dns_records?type=$RECORD_TYPE&name=$DOMAIN" \
    -H "Authorization: Bearer $CLOUDFLARE_API_KEY" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['result'][0]['id'])")

  curl -s -X PUT "https://api.cloudflare.com/client/v4/zones/$CLOUDFLARE_ZONE_ID/dns_records/$RECORD_ID" \
    -H "Authorization: Bearer $CLOUDFLARE_API_KEY" \
    -H "Content-Type: application/json" \
    --data "{\"type\":\"$RECORD_TYPE\",\"name\":\"$DOMAIN\",\"content\":\"$MAIN_IP\",\"ttl\":600,\"proxied\":false}"
}

# =============================================================
# 主入口
# =============================================================

# 切换到备用 IP (调用所有配置的 DNS)
to_backup() {
  log "INFO" "========== 切换到备用 IP: $BACKUP_IP =========="

  # 1. 阿里云 (主要)
  switch_to_backup_aliyun

  # 2. DNSPod (兼容)
  switch_to_backup_dnspod

  # 3. Cloudflare (兼容)
  switch_to_backup_cloudflare

  log "INFO" "========== 切换完成 =========="
}

# 切回主 IP
to_main() {
  log "INFO" "========== 切回主 IP: $MAIN_IP =========="

  switch_to_main_aliyun
  switch_to_main_dnspod
  switch_to_main_cloudflare

  log "INFO" "========== 切回完成 =========="
}

# 主入口
case "${1:-}" in
  to-backup)
    to_backup
    ;;
  to-main)
    to_main
    ;;
  test)
    echo "Domain: $DOMAIN"
    echo "RR: $RR_KEYWORD"
    echo "Type: $RECORD_TYPE"
    echo "MAIN_IP: $MAIN_IP"
    echo "BACKUP_IP: $BACKUP_IP"
    echo "TTL: $TTL"
    echo "DNS Provider: 阿里云 DNS"
    echo "Aliyun AK: ${ALIYUN_AK:0:8}..."
    ;;
  *)
    echo "Usage: $0 {to-backup|to-main|test}"
    exit 1
    ;;
esac
