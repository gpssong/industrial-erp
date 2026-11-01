#!/bin/bash
# =============================================================================
# erp-dns-switch.sh — 阿里云 DNS 解析切换 (home.93gushi.com A 记录)
#
# 部署位置: 阿里云 /usr/local/bin/erp-dns-switch.sh
# 配置:     /etc/erp-dns.env (600)
#   ALIYUN_AK_ID= / ALIYUN_AK_SECRET=   阿里云 AccessKey (需 AliyunDNSFullAccess)
#   DNS_DOMAIN=93gushi.com  DNS_RR=home  DNS_TTL=600
#   ⚠️ 阿里云云解析免费版 TTL 最小 600 秒, 填 60 会被 API 拒 (400 InvalidTTL).
#      想做到 60s 需要升级套餐; 现行 TTL=600 意味着切解析最长约 10 分钟生效.
#   DNS_TARGET_ALIYUN=47.96.188.129      DNS_TARGET_HOME=<家庭公网 IP, 可留空走记录文件>
#   家宽公网 IP 会变: home 模式优先用 /var/lib/erp-failover/dns-home-ip (由
#   erp-failover.sh 在切 DNS 前用隧道来源 IP 刷新), 其次命令行参数, 最后才是静态值.
#
# 用法: erp-dns-switch.sh show | aliyun | home [ip] | ttl-low
# 退出码: 0 成功 / 78 未配置 AccessKey / 1 调用失败
# =============================================================================
set -uo pipefail

CONF=/etc/erp-dns.env
[ -f "$CONF" ] || { echo "missing $CONF" >&2; exit 78; }
# shellcheck disable=SC1090
. "$CONF"
STATE_DIR=/var/lib/erp-failover
HOME_IP_FILE=$STATE_DIR/dns-home-ip
mkdir -p "$STATE_DIR"

if [ -z "${ALIYUN_AK_ID:-}" ] || [ -z "${ALIYUN_AK_SECRET:-}" ]; then
    echo "AccessKey 未配置: 请在 $CONF 填 ALIYUN_AK_ID / ALIYUN_AK_SECRET" >&2
    exit 78
fi

exec python3 - \
    "${1:-show}" "${ALIYUN_AK_ID}" "${ALIYUN_AK_SECRET}" \
    "${DNS_DOMAIN:-93gushi.com}" "${DNS_RR:-home}" "${DNS_TTL:-600}" \
    "${DNS_TARGET_ALIYUN:-}" "${DNS_TARGET_HOME:-}" "$HOME_IP_FILE" "${2:-}" <<'PY'
import base64, hashlib, hmac, json, sys, urllib.parse, urllib.request, uuid
from datetime import datetime, timezone

mode, ak_id, ak_secret, domain, rr, ttl, target_aliyun, target_home, home_ip_file = sys.argv[1:10]
home_ip_arg = sys.argv[10] if len(sys.argv) > 10 else ""
ttl = int(ttl)
ENDPOINT = "https://alidns.aliyuncs.com/"

def call(params):
    params = dict(params)
    params.update({
        "Format": "JSON", "Version": "2015-01-09", "AccessKeyId": ak_id,
        "SignatureMethod": "HMAC-SHA1", "SignatureVersion": "1.0",
        "SignatureNonce": uuid.uuid4().hex,
        "Timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    })
    q = "&".join(
        "%s=%s" % (urllib.parse.quote(k, safe="~"), urllib.parse.quote(str(v), safe="~"))
        for k, v in sorted(params.items())
    )
    sts = "GET&%2F&" + urllib.parse.quote(q, safe="~")
    sig = base64.b64encode(
        hmac.new((ak_secret + "&").encode(), sts.encode(), hashlib.sha1).digest()
    ).decode()
    url = ENDPOINT + "?" + q + "&Signature=" + urllib.parse.quote(sig, safe="~")
    with urllib.request.urlopen(url, timeout=15) as r:
        return json.loads(r.read().decode())

def find_record():
    res = call({"Action": "DescribeDomainRecords", "DomainName": domain,
                "RRKeyWord": rr, "SearchMode": "EXACT",
                "PageSize": 100, "PageNumber": 1})
    for rec in res.get("DomainRecords", {}).get("Record", []):
        if rec["RR"] == rr and rec["Type"] == "A":
            return rec
    return None

def update(value, new_ttl):
    rec = find_record()
    if rec is None:
        raise SystemExit("未找到 A 记录 %s.%s" % (rr, domain))
    if rec["Value"] == value and int(rec["TTL"]) == int(new_ttl):
        # 同值时阿里云返回 400 DomainRecordDuplicate, 这里直接跳过
        return False
    call({"Action": "UpdateDomainRecord", "RecordId": rec["RecordId"],
          "RR": rr, "Type": "A", "Value": value, "TTL": new_ttl})
    return True

def read_home_ip():
    try:
        with open(home_ip_file) as f:
            v = f.read().strip()
            return v or None
    except FileNotFoundError:
        return None

rec = find_record()
if rec is None:
    raise SystemExit("未找到 A 记录 %s.%s" % (rr, domain))
cur_value, cur_ttl = rec["Value"], rec["TTL"]

if mode == "show":
    print("record=%s.%s value=%s ttl=%s" % (rr, domain, cur_value, cur_ttl))
elif mode == "ttl-low":
    changed = update(cur_value, ttl)
    print("ttl=%s (value 未变 %s)%s" % (ttl, cur_value, "" if changed else " [已是目标值]"))
elif mode == "aliyun":
    if not target_aliyun:
        raise SystemExit("DNS_TARGET_ALIYUN 未配置")
    if cur_value != target_aliyun:
        if not target_home:
            with open(home_ip_file, "w") as f:
                f.write(cur_value)
    changed = update(target_aliyun, ttl)
    print("%s.%s → %s (ttl=%s)%s" % (rr, domain, target_aliyun, ttl, "" if changed else " [未变更]"))
elif mode == "home":
    # 优先: 命令行指定 → 隧道来源 IP 落盘 → 静态 DNS_TARGET_HOME
    target = home_ip_arg or read_home_ip() or target_home
    if not target:
        raise SystemExit("没有可用的 home IP: 请配置 DNS_TARGET_HOME 或 restore %s" % home_ip_file)
    changed = update(target, ttl)
    print("%s.%s → %s (ttl=%s)%s" % (rr, domain, target, ttl, "" if changed else " [未变更]"))
else:
    raise SystemExit("usage: erp-dns-switch.sh show|aliyun|home [ip]|ttl-low")
PY
