#!/bin/bash
# ============================================================
# 列出 ERP 备份清单
# 用法: ./list.sh          # 列出最近 10 条
#       ./list.sh all      # 列出全部
# ============================================================
set -euo pipefail
BACKUP_ROOT="${ERP_BACKUP_ROOT:-/volume3/docker/erp-system/data/erp-backup}"
LIMIT="${1:-10}"

if [ "$LIMIT" = "all" ]; then LIMIT=9999; fi

echo "================================================"
echo "  ERP 备份列表 (最近 $LIMIT 条)"
echo "================================================"
cd "$BACKUP_ROOT" || { echo "✗ $BACKUP_ROOT 不存在"; exit 1; }

# DB
echo "── DB dumps (每行 1 条, gzip) ──"
ls -1t "$BACKUP_ROOT/db/"*.sql.gz 2>/dev/null | head -n "$LIMIT" | while read -r f; do
    sz=$(du -h "$f" | awk '{print $1}')
    dt=$(stat -c "%y" "$f" 2>/dev/null | cut -d. -f1)
    echo "  [$dt]  $sz  $(basename "$f")"
done

# meta
echo ""
echo "── 备份快照 (meta/version_*.json) ──"
for f in $(ls -1t "$BACKUP_ROOT/meta/"version_*.json 2>/dev/null | head -n "$LIMIT"); do
    ts=$(basename "$f" .json | sed 's/version_//')
    echo "  [$ts]"
    grep -E '"db_size"|"backend_jar"|"pc_web"|"app_h5"' "$f" | sed 's/^/    /'
done

# jar + dist
echo ""
echo "── 后端 jar (最早 3 / 全部) ──"
ls -1t "$BACKUP_ROOT/backend/"*.jar 2>/dev/null | head -n 3 | sed 's/^/  /'
echo "  共 $(ls -1 "$BACKUP_ROOT/backend/"*.jar 2>/dev/null | wc -l) 份"

echo ""
echo "── 前端 tar.gz (最早 3 / 全部) ──"
ls -1t "$BACKUP_ROOT/frontend/"*.tar.gz 2>/dev/null | head -n 3 | sed 's/^/  /'
echo "  共 $(ls -1 "$BACKUP_ROOT/frontend/"*.tar.gz 2>/dev/null | wc -l) 份"

echo ""
echo "  latest → $(readlink "$BACKUP_ROOT/latest.json" 2>/dev/null || echo '无')"
echo ""
echo "  总占用: $(du -sh "$BACKUP_ROOT" 2>/dev/null | awk '{print $1}')"
