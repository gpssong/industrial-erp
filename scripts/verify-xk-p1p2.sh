#!/bin/bash
# v1.1.130 P1/P2 验收 — 在 .150 上以 root 执行 (sudo bash)
# 临时管理员 _xkverify (授 SUPER_ADMIN 角色) + 一次 CRUD + 门禁用例, 结束后清理
set -u
API=http://127.0.0.1:8080/api
ENVF=/volume3/docker/erp-system/.env
RP=$(grep '^MYSQL_ROOT_PASSWORD=' "$ENVF" | cut -d= -f2-)
DOCKER=/usr/local/bin/docker

mysql_q() { $DOCKER exec -i -e MYSQL_PWD="$RP" erp-mysql mysql -uroot -N --default-character-set=utf8mb4 industrial_erp -e "$1" 2>/dev/null; }
redis_q() { $DOCKER exec erp-redis redis-cli --no-auth-warning GET "$1" 2>/dev/null; }

echo "=== 0. 临时管理员 + SUPER_ADMIN 角色 ==="
cat > /tmp/_xkverify.sql <<'SQL'
DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id WHERE u.username = '_xkverify';
DELETE FROM sys_user WHERE username = '_xkverify';
INSERT INTO sys_user(username,password,nickname,real_name,is_admin,status,deleted)
VALUES('_xkverify','$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2','xkverify','xkverify',1,1,0);
INSERT INTO sys_user_role(user_id, role_id) SELECT id, 1 FROM sys_user WHERE username = '_xkverify';
SQL
mysql_q "$(cat /tmp/_xkverify.sql)"
mysql_q "SELECT u.id,u.username,u.is_admin,ur.role_id FROM sys_user u LEFT JOIN sys_user_role ur ON ur.user_id=u.id WHERE u.username='_xkverify'"

echo "=== 1. 登录 ==="
CAP=$(curl -s --max-time 10 "$API/auth/captcha")
KEY=$(printf '%s' "$CAP" | sed -n 's/.*"captchaKey":"\([^"]*\)".*/\1/p')
CODE=$(redis_q "erp:login:limit:captcha:$KEY" | tail -1)
echo "captchaKey=$KEY code=$CODE"
LOGIN=$(curl -s --max-time 15 -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"_xkverify\",\"password\":\"admin123\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")
echo "$LOGIN" | head -c 260; echo
TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -z "$TOKEN" ] && { echo "!! 登录失败, 中止"; mysql_q "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id=ur.user_id WHERE u.username='_xkverify'; DELETE FROM sys_user WHERE username='_xkverify';"; exit 1; }
AUTH="Authorization: $TOKEN"

echo "=== 1b. 清除默认密码强制改密 (P1-8 拦截器) ==="
curl -s --max-time 15 -X PUT -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"oldPassword":"admin123","newPassword":"Xkverify#2026aZ"}' "$API/system/user/me/password" | head -c 200; echo

get()  { curl -s --max-time 15 -H "$AUTH" "$API$1"; }
post() { curl -s --max-time 20 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d "$2" "$API$1"; }
put()  { curl -s --max-time 20 -X PUT -H "$AUTH" -H 'Content-Type: application/json' -d "$2" "$API$1"; }
del()  { curl -s --max-time 15 -X DELETE -H "$AUTH" "$API$1"; }
idof() { sed -n 's/.*"id":"\?\([0-9][0-9]*\)"\?.*/\1/p' | head -1; }

SUPID=$(mysql_q "SELECT id FROM base_supplier WHERE deleted=0 ORDER BY id LIMIT 1" | head -1)
SUPCODE=$(mysql_q "SELECT supplier_code FROM base_supplier WHERE id=$SUPID")
SUPNAME=$(mysql_q "SELECT supplier_name FROM base_supplier WHERE id=$SUPID")
echo "supplier=$SUPID / $SUPCODE / $SUPNAME"

echo "=== 2. 统计 / 看板 (只读) ==="
get /xk/supplier/stats | head -c 500; echo
get /xk/supplier/dashboard | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("warnDays",d["warnDays"],"certDue",d["certDueCount"],"reviewDue",d["reviewDueCount"],"ratingDist",d["ratingDistribution"],"latestReviewKeys",len(d["latestReview"]))'

echo "=== 3. 准入评审 新增 ==="
R=$(post /xk/supplier/review "{\"supplierId\":$SUPID,\"reviewType\":\"INITIAL\",\"reviewDate\":\"2026-10-27\",\"reviewer\":\"验收人\",\"result\":\"PASS\",\"conclusion\":\"v1.1.130 P1P2 验收\"}")
echo "$R" | head -c 450; echo
RID=$(printf '%s' "$R" | idof); echo "reviewId=$RID"
get "/xk/supplier/review/list?limit=5" | head -c 400; echo

echo "=== 4. 生产许可登记 → 回写 base_supplier ==="
C=$(post /xk/supplier/cert "{\"supplierId\":$SUPID,\"certType\":\"PRODUCTION_LICENSE\",\"certName\":\"食品相关产品生产许可证\",\"certNo\":\"XK16-204-VERIFY\",\"issuer\":\"省市场监管局\",\"issueDate\":\"2026-01-01\",\"expireDate\":\"2027-12-31\",\"remark\":\"验收临时证照\"}")
echo "$C" | head -c 450; echo
CID=$(printf '%s' "$C" | idof); echo "certId(license)=$CID"
mysql_q "SELECT supplier_name,food_cert_no,food_cert_exp,food_cert_type FROM base_supplier WHERE id=$SUPID"

echo "=== 5. GB 4806 报告 (不应改动 food_cert_*) ==="
C2=$(post /xk/supplier/cert "{\"supplierId\":$SUPID,\"certType\":\"GB4806_REPORT\",\"certName\":\"GB 4806.7 迁移量检测报告\",\"certNo\":\"GB4806-VERIFY\",\"issuer\":\"SGS\",\"issueDate\":\"2026-03-01\",\"expireDate\":\"2026-11-30\"}")
echo "$C2" | head -c 350; echo
CID2=$(printf '%s' "$C2" | idof)
mysql_q "SELECT food_cert_no,food_cert_exp FROM base_supplier WHERE id=$SUPID"

echo "=== 6. 年度绩效 (95/90/85/90/95 → 计算总分与等级) ==="
P=$(post /xk/supplier/perf "{\"supplierId\":$SUPID,\"evalYear\":2026,\"qualityScore\":95,\"deliveryScore\":90,\"priceScore\":85,\"serviceScore\":90,\"complianceScore\":95,\"evalDate\":\"2026-10-27\",\"evaluator\":\"验收人\"}")
echo "$P" | head -c 500; echo
PID=$(printf '%s' "$P" | idof); echo "perfId=$PID"
get "/xk/supplier/perf/list?limit=5" | head -c 350; echo

echo "=== 7. 看板复查 (证照临期 + A 级) ==="
get /xk/supplier/dashboard | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("certDue",d["certDueCount"],[(c["supplierName"],c["certTypeLabel"],c["daysLeft"]) for c in d["certs"]],"ratingDist",d["ratingDistribution"],"reviewDue",d["reviewDueCount"])'

echo "=== 8. 门禁: 准入状态不可直接改 ==="
put /base/supplier "{\"id\":$SUPID,\"supplierCode\":\"$SUPCODE\",\"supplierName\":\"$SUPNAME\",\"status\":0}" | head -c 320; echo

echo "=== 9. 门禁: 采购订单 ==="
PROD=$(mysql_q "SELECT id FROM base_product WHERE deleted=0 AND is_food_grade=1 LIMIT 1" | head -1)
WH=$(mysql_q "SELECT id FROM base_warehouse WHERE deleted=0 LIMIT 1" | head -1)
echo "foodGradeProduct=$PROD warehouse=$WH"
PO="{\"supplierId\":$SUPID,\"billDate\":\"2026-10-27\",\"warehouseId\":$WH,\"remark\":\"_xkverify\",\"details\":[{\"productId\":$PROD,\"qty\":10,\"price\":1,\"taxRate\":13}]}"
POID=""
if [ -n "$PROD" ]; then
  echo "合格态建单: $(post /purchase/order "$PO" | head -c 300)"
  POID=$(mysql_q "SELECT id FROM pur_order WHERE remark='_xkverify' ORDER BY id DESC LIMIT 1" | head -1)
  echo "poId=$POID"
  echo "暂停: $(post /xk/supplier/$SUPID/suspend '{"reason":"验收临时暂停","reasonType":"CERT_EXPIRED"}' | head -c 120)"
  echo "暂停后建单(应被拦截): $(post /purchase/order "$PO" | head -c 400)"
  echo "恢复: $(post /xk/supplier/$SUPID/resume '{"reason":"验收结束恢复供货"}' | head -c 120)"
else
  echo "!! 无食品级商品, 跳过采购门禁用例"
fi

echo "=== 10. 审计流水 ==="
mysql_q "SELECT biz_type,action,biz_bill_no,LEFT(change_desc,36) FROM xk_audit_log WHERE biz_type LIKE 'SUPPLIER%' ORDER BY id DESC LIMIT 10"

echo "=== 11. 删除接口 (软删 + 回写清空) ==="
[ -n "$CID2" ] && echo "del-report: $(del /xk/supplier/cert/$CID2 | head -c 120)"
[ -n "$CID" ] && echo "del-license: $(del /xk/supplier/cert/$CID | head -c 120)"
[ -n "$RID" ] && echo "del-review: $(del /xk/supplier/review/$RID | head -c 120)"
[ -n "$PID" ] && echo "del-perf: $(del /xk/supplier/perf/$PID | head -c 120)"
mysql_q "SELECT supplier_name,food_cert_no,food_cert_exp FROM base_supplier WHERE id=$SUPID"

echo "=== 12. 清理临时数据 ==="
[ -n "$POID" ] && del "/purchase/order/$POID" >/dev/null
mysql_q "DELETE FROM pur_order_detail WHERE order_id IN (SELECT id FROM pur_order WHERE remark='_xkverify'); DELETE FROM pur_order WHERE remark='_xkverify';"
mysql_q "DELETE FROM xk_supplier_review WHERE conclusion LIKE '%验收%'; DELETE FROM xk_supplier_cert WHERE cert_no LIKE '%VERIFY%'; DELETE FROM xk_supplier_perf WHERE evaluator='验收人'; DELETE FROM xk_supplier_status_log WHERE reason LIKE '%验收%'; DELETE FROM xk_audit_log WHERE change_desc LIKE '%验收%' OR change_desc LIKE '%VERIFY%'; DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id=ur.user_id WHERE u.username='_xkverify'; DELETE FROM sys_user WHERE username='_xkverify';"
mysql_q "SELECT 'review_left',COUNT(*) FROM xk_supplier_review WHERE deleted=0 UNION ALL SELECT 'cert_left',COUNT(*) FROM xk_supplier_cert WHERE deleted=0 UNION ALL SELECT 'perf_left',COUNT(*) FROM xk_supplier_perf WHERE deleted=0 UNION ALL SELECT 'temp_user',COUNT(*) FROM sys_user WHERE username='_xkverify' UNION ALL SELECT 'po_left',COUNT(*) FROM pur_order WHERE remark='_xkverify'"
echo "=== DONE ==="
