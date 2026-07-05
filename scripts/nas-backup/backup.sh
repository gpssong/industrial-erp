#!/bin/bash
# ============================================================
# 群晖 NAS ERP 每日自动备份脚本
# 调度: DSM Task Scheduler → 每日 03:00
# 输出: /volume3/erp-backup/
#   ├── db/industrial_erp_YYYYMMDD_HHMMSS.sql.gz     (MySQL 全量)
#   ├── backend/industrial-erp-1.0.4_YYYYMMDD_HHMMSS.jar  (有变更时才覆盖)
#   ├── frontend/pc-web_YYYYMMDD_HHMMSS.tar.gz           (有变更时才覆盖)
#   ├── frontend/app-h5_YYYYMMDD_HHMMSS.tar.gz           (有变更时才覆盖)
#   └── version.json                                       (本次备份元信息)
#
# 恢复脚本: ./restore.sh   (参数: backup_id)
# 日志:     ./logs/backup_YYYYMMDD.log
# ============================================================
set -euo pipefail

# ===== 可配置 =====
# 默认写到 docker 数据卷下,普通用户也能直接写;如需改到 /volume3/erp-backup 等位置用环境变量覆盖
BACKUP_ROOT="${ERP_BACKUP_ROOT:-/volume3/docker/erp-system/data/erp-backup}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RETENTION_DB_DAYS="${ERP_RETENTION_DB_DAYS:-30}"      # DB 保留 30 天
RETENTION_BIN_KEEP="${ERP_RETENTION_BIN_KEEP:-10}"     # jar/dist 各保留 10 份
LOG_DIR="$SCRIPT_DIR/logs"
TS="$(date +%Y%m%d_%H%M%S)"
DATE_SHORT="$(date +%Y%m%d)"
LOG_FILE="$LOG_DIR/backup_${DATE_SHORT}.log"

MYSQL_CONTAINER="erp-mysql"
BACKEND_CONTAINER="erp-backend"
PCWEB_CONTAINER="erp-pc-web"
APP_H5_CONTAINER="erp-app-h5"

DB_USER="root"
DB_PASS="erp_root_pwd"
DB_NAME="industrial_erp"
MYSQLDUMP="/usr/bin/mysqldump"
GZIP_BIN="$(command -v pigz || command -v gzip)"

mkdir -p "$BACKUP_ROOT"/{db,backend,frontend,meta} "$LOG_DIR"

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG_FILE" ; }
fail() { log "FATAL: $*"; exit 1; }

# ===== 0. 健康检查 =====
log "===== ERP 备份开始 ($TS) ====="
if ! sudo /usr/local/bin/docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
    fail "MySQL 容器 $MYSQL_CONTAINER 未运行"
fi

# ===== 1. MySQL 全量备份 =====
log "[1/4] MySQL dump 开始"
SQL_FILE="$BACKUP_ROOT/db/${DB_NAME}_${TS}.sql"
SQL_GZ="${SQL_FILE}.gz"
sudo /usr/local/bin/docker exec "$MYSQL_CONTAINER" \
    bash -lc "
        set -e
        if ! command -v mysqldump >/dev/null; then
            echo 'NO_MYSQLDUMP' >&2
            exit 127
        fi
        mysqldump -u${DB_USER} -p${DB_PASS} \
            --default-character-set=utf8mb4 \
            --single-transaction --quick --routines --triggers --events \
            --hex-blob \
            ${DB_NAME} > /tmp/${DB_NAME}_${TS}.sql
    " || fail "mysqldump 执行失败"
sudo /usr/local/bin/docker cp "$MYSQL_CONTAINER:/tmp/${DB_NAME}_${TS}.sql" "$SQL_FILE" \
    || fail "docker cp dump 文件失败"
sudo /usr/local/bin/docker exec "$MYSQL_CONTAINER" rm -f "/tmp/${DB_NAME}_${TS}.sql"

# 校验非空
[ -s "$SQL_FILE" ] || fail "生成的 dump 为空: $SQL_FILE"

"$GZIP_BIN" -6 "$SQL_FILE" 2>/dev/null || gzip -6 "$SQL_FILE"
[ -s "$SQL_GZ" ] || fail "压缩失败: $SQL_GZ"
DB_SIZE=$(du -h "$SQL_GZ" | awk '{print $1}')
log "[1/4] ✓ DB dump: $SQL_GZ ($DB_SIZE)"

# ===== 2. 后端 jar (变更才覆盖) =====
log "[2/4] 后端 jar 备份"
BACKEND_JAR_INSIDE="/opt/app/app.jar"
BACKEND_VERSION=$(sudo /usr/local/bin/docker exec "$BACKEND_CONTAINER" \
    sh -c "ls -la $BACKEND_JAR_INSIDE 2>/dev/null" || echo "")
if echo "$BACKEND_VERSION" | grep -qE 'app.jar$'; then
    BACKEND_SHA=$(sudo /usr/local/bin/docker exec "$BACKEND_CONTAINER" \
        sha256sum "$BACKEND_JAR_INSIDE" | awk '{print $1}')
    LAST_BACKEND_SHA=$(cat "$BACKUP_ROOT/meta/backend.sha256" 2>/dev/null || echo "")
    if [ "$BACKEND_SHA" != "$LAST_BACKEND_SHA" ]; then
        BACKEND_TARGET="$BACKUP_ROOT/backend/industrial-erp_${TS}.jar"
        sudo /usr/local/bin/docker cp "$BACKEND_CONTAINER:$BACKEND_JAR_INSIDE" "$BACKEND_TARGET"
        echo "$BACKEND_SHA" > "$BACKUP_ROOT/meta/backend.sha256"
        log "[2/4] ✓ 后端 jar 已更新: $BACKEND_TARGET"
    else
        log "[2/4] - 后端 jar 未变更, 跳过"
    fi
else
    log "[2/4] ! 后端容器无 app.jar, 跳过"
fi

# ===== 3. 前端 dist (变更才覆盖) =====
log "[3/4] 前端备份"
backup_dir_if_changed() {
    local CONTAINER="$1"
    local SRC_PATH="$2"     # 容器内路径
    local OUT_NAME="$3"     # 输出文件名
    local META_FILE="$4"
    local LAST_SHA
    LAST_SHA=$(cat "$BACKUP_ROOT/meta/$META_FILE" 2>/dev/null || echo "")

    local CUR_SHA
    CUR_SHA=$(sudo /usr/local/bin/docker exec "$CONTAINER" \
        sh -c "if [ -d $SRC_PATH ]; then find $SRC_PATH -type f -exec sha256sum {} \; | sha256sum; else echo MISSING; fi" \
        2>/dev/null | awk '{print $1}')
    if [ -z "$CUR_SHA" ] || [ "$CUR_SHA" = "MISSING" ] || [ "$CUR_SHA" = "$LAST_SHA" ]; then
        log "[3/4] - $OUT_NAME 未变更, 跳过"
        return 0
    fi
    local TARGET="$BACKUP_ROOT/frontend/${OUT_NAME}_${TS}.tar.gz"
    sudo /usr/local/bin/docker exec "$CONTAINER" \
        sh -c "cd $(dirname $SRC_PATH) && tar -czf /tmp/frontend_${TS}.tar.gz $(basename $SRC_PATH)" \
        || { log "[3/4] ! $OUT_NAME 打包失败"; return 1; }
    sudo /usr/local/bin/docker cp "$CONTAINER:/tmp/frontend_${TS}.tar.gz" "$TARGET"
    sudo /usr/local/bin/docker exec "$CONTAINER" rm -f "/tmp/frontend_${TS}.tar.gz"
    echo "$CUR_SHA" > "$BACKUP_ROOT/meta/$META_FILE"
    log "[3/4] ✓ $OUT_NAME 已更新: $TARGET"
}

backup_dir_if_changed "$PCWEB_CONTAINER"  "/usr/share/nginx/html"  "pc-web" "pcweb.sha256"
backup_dir_if_changed "$APP_H5_CONTAINER" "/usr/share/nginx/html"  "app-h5"  "apph5.sha256" || true

# ===== 4. 写 version.json =====
log "[4/4] 写元信息"
DB_COUNT=$(ls -1 "$BACKUP_ROOT/db/"*.sql.gz 2>/dev/null | wc -l | tr -d ' ')
cat > "$BACKUP_ROOT/meta/version_${TS}.json" <<EOF
{
  "ts": "$TS",
  "db_file": "$(basename "$SQL_GZ")",
  "db_size": "$DB_SIZE",
  "backend_jar": "$(ls -t "$BACKUP_ROOT/backend/"*.jar 2>/dev/null | head -1 | xargs -I{} basename {} || echo '')",
  "pc_web":     "$(ls -t "$BACKUP_ROOT/frontend/"pc-web_*.tar.gz 2>/dev/null | head -1 | xargs -I{} basename {} || echo '')",
  "app_h5":     "$(ls -t "$BACKUP_ROOT/frontend/"app-h5_*.tar.gz 2>/dev/null | head -1 | xargs -I{} basename {} || echo '')",
  "db_total_kept": $DB_COUNT,
  "host": "$(hostname)"
}
EOF
# meta 软链到 latest.json
ln -sfn "version_${TS}.json" "$BACKUP_ROOT/latest.json"
log "[4/4] ✓ $BACKUP_ROOT/latest.json"

# ===== 5. 清理过期 =====
log "[5/5] 清理过期 (DB>${RETENTION_DB_DAYS}天 / 二进制保留最近${RETENTION_BIN_KEEP}份)"
find "$BACKUP_ROOT/db" -maxdepth 1 -name "${DB_NAME}_*.sql.gz" -mtime +"$RETENTION_DB_DAYS" -delete -print || true
ls -1t "$BACKUP_ROOT/backend/"*.jar   2>/dev/null | tail -n +$((RETENTION_BIN_KEEP + 1)) | xargs -I{} rm -f "{}" || true
ls -1t "$BACKUP_ROOT/frontend/"*.tar.gz 2>/dev/null | tail -n +$((RETENTION_BIN_KEEP + 1)) | xargs -I{} rm -f "{}" || true

log "===== ERP 备份成功完成 (DB=$DB_SIZE) ====="
