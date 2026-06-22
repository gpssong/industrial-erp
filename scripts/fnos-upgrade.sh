#!/bin/bash
# ============================================================
#  飞牛 OS 一键升级脚本 (打快照 + 拉代码 + 重构建)
#  用法: ./scripts/fnos-upgrade.sh [git_branch]
# ============================================================
set -e

BRANCH="${1:-main}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  ERP 系统升级${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# 1. 检查 git 仓库
if [ ! -d .git ]; then
  echo -e "${RED}[ERROR] 当前目录不是 git 仓库, 无法升级${NC}"
  echo "请用 git clone 方式部署项目"
  exit 1
fi

# 2. 当前状态
echo -e "${BLUE}[1/5] 当前状态...${NC}"
CURRENT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
echo -e "  当前 commit: ${YELLOW}${CURRENT}${NC}"
echo -e "  目标 branch: ${YELLOW}${BRANCH}${NC}"
echo ""

# 3. 拉新代码
echo -e "${BLUE}[2/5] 拉取最新代码 (branch: ${BRANCH})...${NC}"
git fetch origin "$BRANCH"
NEW=$(git rev-parse --short origin/"$BRANCH")
if [ "$CURRENT" = "$NEW" ]; then
  echo -e "${YELLOW}  ! 已是最新, 无需升级${NC}"
  exit 0
fi

# 4. 打快照 + 数据库备份
echo -e "${BLUE}[3/5] 升级前备份...${NC}"
if [ -x ./scripts/fnos-backup.sh ]; then
  ./scripts/fnos-backup.sh
else
  echo -e "${YELLOW}  ! 未找到备份脚本, 跳过备份 (强烈建议手动备份!)${NC}"
fi

# 5. 拉代码
echo -e "${BLUE}[4/5] 合并新代码...${NC}"
git pull origin "$BRANCH"
NEW_COMMIT=$(git rev-parse --short HEAD)
echo -e "${GREEN}  ✓ 已升级到: ${NEW_COMMIT}${NC}"

# 6. 重新构建
echo -e "${BLUE}[5/5] 重新构建镜像...${NC}"
docker compose up -d --build

echo ""
sleep 15
echo -e "${BLUE}========== 服务状态 ==========${NC}"
docker compose ps
echo ""

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  ✅ 升级完成${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  ${YELLOW}注意: 如有 SQL 变更, 请手动执行:${NC}"
ls -1 sql/*.sql 2>/dev/null | while read f; do
  echo -e "    ${BLUE}docker exec -i erp-mysql mysql -uroot -p密码 industrial_erp < $f${NC}"
done
echo ""
echo "  如遇问题, 回滚:"
echo -e "    ${BLUE}git reset --hard ${CURRENT}${NC}"
echo -e "    ${BLUE}docker compose up -d --build${NC}"
echo ""
