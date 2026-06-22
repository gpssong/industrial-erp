#!/bin/bash
# ============================================================
#  飞牛 OS 一键备份脚本 (btrfs 快照 + 数据库 dump)
#  用法: ./scripts/fnos-backup.sh
# ============================================================
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  ERP 数据备份${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# 检查 .env
if [ ! -f .env ]; then
  echo -e "${RED}[ERROR] .env 不存在, 请先运行 ./scripts/fnos-deploy.sh${NC}"
  exit 1
fi

# 读取 MySQL 密码
MYSQL_PWD=$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2 | tr -d '"' | tr -d ' ')
if [ -z "$MYSQL_PWD" ]; then
  echo -e "${RED}[ERROR] .env 中 MYSQL_ROOT_PASSWORD 为空${NC}"
  exit 1
fi

# 检查 MySQL 容器
if ! docker ps --format '{{.Names}}' | grep -q erp-mysql; then
  echo -e "${RED}[ERROR] erp-mysql 容器未运行, 无法备份${NC}"
  exit 1
fi

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="${PROJECT_DIR}/data/backup/${TIMESTAMP}"
mkdir -p "$BACKUP_DIR"

# 1. 数据库 dump
echo -e "${BLUE}[1/2] 数据库备份...${NC}"
docker exec erp-mysql mysqldump \
  -uroot -p"${MYSQL_PWD}" \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  industrial_erp > "${BACKUP_DIR}/db.sql"

if [ -s "${BACKUP_DIR}/db.sql" ]; then
  SIZE=$(du -h "${BACKUP_DIR}/db.sql" | cut -f1)
  echo -e "${GREEN}  ✓ 数据库备份完成 (${SIZE}): ${BACKUP_DIR}/db.sql${NC}"
else
  echo -e "${YELLOW}  ⚠ 数据库备份文件为空, 请检查密码是否正确${NC}"
fi

# 2. btrfs 快照 (如果可用)
echo -e "${BLUE}[2/2] btrfs 文件系统快照...${NC}"
SNAPSHOT_PARENT="${PROJECT_DIR}/.snapshots"
if [ -d "$SNAPSHOT_PARENT" ] && command -v btrfs &>/dev/null; then
  SNAPSHOT_PATH="${SNAPSHOT_PARENT}/erp-${TIMESTAMP}"
  if sudo btrfs subvolume snapshot -r "$PROJECT_DIR" "$SNAPSHOT_PATH" 2>/dev/null; then
    SNAP_SIZE=$(sudo du -sh "$SNAPSHOT_PATH" | cut -f1)
    echo -e "${GREEN}  ✓ 快照创建成功 (${SNAP_SIZE}): ${SNAPSHOT_PATH}${NC}"
    echo -e "${YELLOW}  ! 注意: btrfs 快照仅记录差异, 实际占用很小${NC}"
  else
    echo -e "${YELLOW}  ⚠ btrfs 快照失败 (可能不在 btrfs 卷), 已跳过${NC}"
  fi
else
  echo -e "${YELLOW}  - 未检测到 btrfs, 跳过系统快照 (仅数据库已备份)${NC}"
fi

# 3. 清理旧备份 (保留 30 天)
echo -e "${BLUE}[清理] 删除 30 天前的数据库备份...${NC}"
find "${PROJECT_DIR}/data/backup" -mindepth 1 -maxdepth 1 -type d -mtime +30 -exec rm -rf {} + 2>/dev/null || true
find "${PROJECT_DIR}/data/backup" -mindepth 1 -maxdepth 1 -name "*.sql" -mtime +30 -delete 2>/dev/null || true

if [ -d "$SNAPSHOT_PARENT" ]; then
  OLD_SNAPS=$(find "$SNAPSHOT_PARENT" -maxdepth 1 -name "erp-*" -mtime +30 2>/dev/null | wc -l)
  if [ "$OLD_SNAPS" -gt 0 ]; then
    find "$SNAPSHOT_PARENT" -maxdepth 1 -name "erp-*" -mtime +30 -exec sudo btrfs subvolume delete {} \; 2>/dev/null || true
    echo -e "${GREEN}  ✓ 清理了 ${OLD_SNAPS} 个旧快照${NC}"
  fi
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  ✅ 备份完成${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  数据库: ${BACKUP_DIR}/db.sql"
[ -d "$SNAPSHOT_PARENT/erp-${TIMESTAMP}" ] && echo "  快照:   ${SNAPSHOT_PARENT}/erp-${TIMESTAMP}"
echo ""
echo "  恢复数据库命令:"
echo -e "    ${BLUE}docker exec -i erp-mysql mysql -uroot -p密码 industrial_erp < ${BACKUP_DIR}/db.sql${NC}"
[ -d "$SNAPSHOT_PARENT/erp-${TIMESTAMP}" ] && echo "  回滚快照命令:"
echo -e "    ${BLUE}sudo btrfs subvolume delete ${PROJECT_DIR} && sudo btrfs subvolume snapshot ${SNAPSHOT_PARENT}/erp-${TIMESTAMP} ${PROJECT_DIR}${NC}"
echo ""
