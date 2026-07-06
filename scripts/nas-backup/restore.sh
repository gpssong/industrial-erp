#!/bin/bash
# ============================================================
# ERP 一键恢复脚本
# 用法:
#   ./restore.sh                    # 列出最近 10 个备份
#   ./restore.sh 20260705_170000    # 恢复到指定 timestamp
#   ./restore.sh --db-only 20260705_170000    # 只恢复 DB
#   ./restore.sh --bin-only 20260705_170000   # 只恢复 jar + dist
#
# 恢复策略:
#   - DB: docker exec 加载 SQL, 写入前自动再 dump 一份当前库到 backup/pre-restore_*.sql.gz
#   - jar: 替换 /opt/app/app.jar 后 docker compose restart backend
#   - dist: 解压到容器 /usr/share/nginx/html/
# ============================================================
set -euo pipefail

BACKUP_ROOT="${ERP_BACKUP_ROOT:-/volume3/docker/erp-system/data/erp-backup}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ⚠️  从 .env 读取 MYSQL_ROOT_PASSWORD, 不再硬编码 erp_root_pwd
DB_PASS=""
for ENV_FILE in \
    "$(dirname "$SCRIPT_DIR")/../.env" \
    "$SCRIPT_DIR/.env" \
    "$SCRIPT_DIR/../.env" \
    "/volume3/docker/erp-system/.env" \
    "/volume1/docker/erp-system/.env"
do
    if [ -f "$ENV_FILE" ]; then
        set -a; . "$ENV_FILE"; set +a
        break
    fi
done
DB_PASS="${MYSQL_ROOT_PASSWORD:-${ERP_DB_PASSWORD:-}}"
if [ -z "$DB_PASS" ]; then
    echo "✗ 未找到 MYSQL_ROOT_PASSWORD; 无法执行数据库操作. 请在 docker-compose 同级目录创建 .env 并设置 MYSQL_ROOT_PASSWORD=..."
    exit 1
fi
DB_ONLY=0
BIN_ONLY=0
TARGET_TS="${1:-}"

usage() {
    grep -E '^# ' "$0" | sed 's/^# //; s/^#//'
    exit 1
}

[[ -z "$TARGET_TS" || "$TARGET_TS" == "-h" || "$TARGET_TS" == "--help" ]] && usage

# 跳过选项
case "$TARGET_TS" in
    --db-only)  DB_ONLY=1;  TARGET_TS="${2:-}" ;;
    --bin-only) BIN_ONLY=1; TARGET_TS="${2:-}" ;;
esac

[ -z "$TARGET_TS" ] && { echo "✗ 缺少时间戳参数"; usage; }

DB_GZ="$BACKUP_ROOT/db/industrial_erp_${TARGET_TS}.sql.gz"
[ -f "$DB_GZ" ] || { echo "✗ 不存在: $DB_GZ"; exit 1; }

read_meta() {
    local f="$BACKUP_ROOT/meta/version_${TARGET_TS}.json"
    [ -f "$f" ] && cat "$f" || echo "(无 meta)"
}

echo "================================================"
echo "  ERP 恢复 / target=$TARGET_TS"
echo "  meta: $(read_meta)"
echo "================================================"

confirm() {
    local prompt="$1"
    local ans
    read -rp "$prompt [y/N] " ans
    [[ "$ans" =~ ^[Yy]$ ]]
}

# ===== 选择模块 =====
do=("db" "bin")
[ $DB_ONLY  -eq 1 ] && do=("db")
[ $BIN_ONLY -eq 1 ] && do=("bin")

# ============== DB 恢复 ==============
if [[ " ${do[*]} " =~ " db " ]]; then
    confirm "确认恢复 DB 到 $TARGET_TS ? 这会覆盖现有数据"
    [[ $? -ne 0 ]] && { echo "用户取消"; exit 0; }

    SQL_TMP="/tmp/restore_${TARGET_TS}.sql"
    gunzip -c "$DB_GZ" > "$SQL_TMP"
    [ -s "$SQL_TMP" ] || { echo "✗ SQL 解压为空"; exit 1; }

    echo "→ 先 dump 当前库到安全点"
    SAFETY="$BACKUP_ROOT/db/pre-restore_$(date +%Y%m%d_%H%M%S).sql.gz"
    sudo /usr/local/bin/docker exec erp-mysql mysqldump \
        -uroot -p"${DB_PASS}" --default-character-set=utf8mb4 \
        --single-transaction --quick --routines --triggers \
        industrial_erp 2>/dev/null | gzip -6 > "$SAFETY"
    echo "  当前库快照: $SAFETY"

    echo "→ 加载 SQL 到容器"
    sudo /usr/local/bin/docker exec -i erp-mysql mysql \
        -uroot -p"${DB_PASS}" industrial_erp < "$SQL_TMP"
    rm -f "$SQL_TMP"
    echo "✓ DB 恢复完成"
fi

# ============== bin 恢复 ==============
if [[ " ${do[*]} " =~ " bin " ]]; then
    confirm "确认恢复后端 jar / 前端 dist ?"
    [[ $? -ne 0 ]] && { echo "用户取消"; exit 0; }

    # jar
    JAR_FILE=$(ls -1 "$BACKUP_ROOT/backend/"*"${TARGET_TS}"*.jar 2>/dev/null | head -1)
    if [ -n "$JAR_FILE" ]; then
        echo "→ 后端: $JAR_FILE"
        sudo /usr/local/bin/docker cp "$JAR_FILE" erp-backend:/tmp/app.jar
        sudo /usr/local/bin/docker exec erp-backend \
            sh -c "rm -f /opt/app/app.jar && cp /tmp/app.jar /opt/app/app.jar"
        sudo /usr/local/bin/docker compose -p erp-system \
            -f /volume3/docker/erp-system/docker-compose.yml restart backend
        echo "✓ 后端已重启"
    else
        echo "  (未找到后端 jar 备份)"
    fi

    # pc-web
    PC_TGZ=$(ls -1 "$BACKUP_ROOT/frontend/"pc-web*"${TARGET_TS}"*.tar.gz 2>/dev/null | head -1)
    if [ -n "$PC_TGZ" ]; then
        echo "→ pc-web: $PC_TGZ"
        sudo /usr/local/bin/docker cp "$PC_TGZ" erp-pc-web:/tmp/dist.tar.gz
        sudo /usr/local/bin/docker exec erp-pc-web sh -c "
            mkdir -p /tmp/newdist && tar -xzf /tmp/dist.tar.gz -C /tmp/newdist &&
            rm -rf /usr/share/nginx/html/assets/* /usr/share/nginx/html/index.html &&
            cp -a /tmp/newdist/. /usr/share/nginx/html/ &&
            rm -rf /tmp/newdist /tmp/dist.tar.gz
        "
        echo "✓ pc-web 已部署"
    else
        echo "  (未找到 pc-web 备份)"
    fi

    # app-h5
    H5_TGZ=$(ls -1 "$BACKUP_ROOT/frontend/"app-h5*"${TARGET_TS}"*.tar.gz 2>/dev/null | head -1)
    if [ -n "$H5_TGZ" ]; then
        echo "→ app-h5: $H5_TGZ"
        sudo /usr/local/bin/docker cp "$H5_TGZ" erp-app-h5:/tmp/dist.tar.gz
        sudo /usr/local/bin/docker exec erp-app-h5 sh -c "
            mkdir -p /tmp/newdist && tar -xzf /tmp/dist.tar.gz -C /tmp/newdist &&
            cp -a /tmp/newdist/. /usr/share/nginx/html/ &&
            rm -rf /tmp/newdist /tmp/dist.tar.gz
        "
        echo "✓ app-h5 已部署"
    else
        echo "  (未找到 app-h5 备份)"
    fi
fi

echo "================================================"
echo "  ✓ 恢复完成, 建议登录前端验证一次"
echo "================================================"
