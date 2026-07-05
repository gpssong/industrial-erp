#!/bin/bash
# 扫描所有 varchar/text 列, 找出含双重 UTF-8 编码的行, 生成修复 SQL
set -e
SCHEMA="industrial_erp"

echo "SET NAMES utf8mb4;" > /tmp/fix_all_mojibake.sql
echo "-- 自动扫描所有 text 列, 找出双重 UTF-8 编码" >> /tmp/fix_all_mojibake.sql
echo "" >> /tmp/fix_all_mojibake.sql

# 双重编码特征: Ã|Â|Ä|Å|ä|ö|ü 等拉丁字符作为字节出现
PATTERN='[ÃÂÄÅÆÇÈÉÊËäöü]'

sudo /usr/local/bin/docker exec erp-mysql mysql -uroot -perp_root_pwd industrial_erp --default-character-set=utf8mb4 -N -B -e "
SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND DATA_TYPE IN ('varchar','char','text','mediumtext','longtext')
" 2>/dev/null | while IFS=$'\t' read -r TABLE COL; do
  COUNT=$(sudo /usr/local/bin/docker exec erp-mysql mysql -uroot -perp_root_pwd industrial_erp --default-character-set=utf8mb4 -N -e "
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