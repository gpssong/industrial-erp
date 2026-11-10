#!/bin/bash
# 扫描所有 varchar/text 列, 找出含双重 UTF-8 编码的行, 生成修复 SQL
#
# 在 home NAS 上执行 (口令取该机 compose 的 .env, 命令行里不出现明文)。
# 覆盖: ERP_ENV_FILE=/path/.env ./scripts/scan_mojibake.sh
set -e
SCHEMA="industrial_erp"

ENV_FILE="${ERP_ENV_FILE:-/volume3/docker/erp-system/.env}"
MYSQL_ROOT_PWD="${MYSQL_ROOT_PASSWORD:-$(grep -m1 '^MYSQL_ROOT_PASSWORD=' "$ENV_FILE" 2>/dev/null | cut -d= -f2-)}"
if [ -z "$MYSQL_ROOT_PWD" ]; then
    echo "❌ 取不到 MySQL root 口令: $ENV_FILE 里没有 MYSQL_ROOT_PASSWORD" >&2
    echo "   用 ERP_ENV_FILE=... 指定环境文件, 或直接 MYSQL_ROOT_PASSWORD=... 传入" >&2
    exit 1
fi
DB() { sudo /usr/local/bin/docker exec -e MYSQL_PWD="$MYSQL_ROOT_PWD" erp-mysql \
    mysql -uroot "$SCHEMA" --default-character-set=utf8mb4 "$@"; }

echo "SET NAMES utf8mb4;" > /tmp/fix_all_mojibake.sql
echo "-- 自动扫描所有 text 列, 找出双重 UTF-8 编码" >> /tmp/fix_all_mojibake.sql
echo "" >> /tmp/fix_all_mojibake.sql

# 双重编码特征: Ã|Â|Ä|Å|ä|ö|ü 等拉丁字符作为字节出现
PATTERN='[ÃÂÄÅÆÇÈÉÊËäöü]'

DB -N -B -e "
SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND DATA_TYPE IN ('varchar','char','text','mediumtext','longtext')
" 2>/dev/null | while IFS=$'\t' read -r TABLE COL; do
  COUNT=$(DB -N -e "
    SELECT COUNT(*) FROM \`${TABLE}\` WHERE \`${COL}\` REGEXP '${PATTERN}';
  " 2>/dev/null | tail -1)
  if [ -n "$COUNT" ] && [ "$COUNT" -gt 0 ] 2>/dev/null; then
    echo "-- ${TABLE}.${COL}: ${COUNT} 行" | tee -a /tmp/fix_all_mojibake.sql
    echo "UPDATE \`${TABLE}\` SET \`${COL}\` = CONVERT(CAST(CONVERT(\`${COL}\` USING latin1) AS BINARY) USING utf8) WHERE \`${COL}\` REGEXP '${PATTERN}';" >> /tmp/fix_all_mojibake.sql
  fi
done

echo ""
echo "=== 生成结果 ==="
wc -l /tmp/fix_all_mojibake.sql
echo ""
echo "=== 前 30 行预览 ==="
head -30 /tmp/fix_all_mojibake.sql