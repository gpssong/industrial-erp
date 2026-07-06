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
echo -e "${BLUE}[1/8] 当前状态...${NC}"
CURRENT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
echo -e "  当前 commit: ${YELLOW}${CURRENT}${NC}"
echo -e "  目标 branch: ${YELLOW}${BRANCH}${NC}"
echo ""

# 3. 拉新代码
echo -e "${BLUE}[2/8] 拉取最新代码 (branch: ${BRANCH})...${NC}"
git fetch origin "$BRANCH"
NEW=$(git rev-parse --short origin/"$BRANCH")
if [ "$CURRENT" = "$NEW" ]; then
  echo -e "${YELLOW}  ! 已是最新, 无需升级${NC}"
  exit 0
fi

# 4. 打快照 + 数据库备份
echo -e "${BLUE}[3/8] 升级前备份...${NC}"
if [ -x ./scripts/fnos-backup.sh ]; then
  ./scripts/fnos-backup.sh
else
  echo -e "${YELLOW}  ! 未找到备份脚本, 跳过备份 (强烈建议手动备份!)${NC}"
fi

# 5. 拉代码
echo -e "${BLUE}[4/8] 合并新代码...${NC}"
git pull origin "$BRANCH"
NEW_COMMIT=$(git rev-parse --short HEAD)
echo -e "${GREEN}  ✓ 已升级到: ${NEW_COMMIT}${NC}"

# 6. 重新构建
echo -e "${BLUE}[5/8] 重新构建镜像...${NC}"
docker compose up -d --build

echo ""
sleep 15
echo -e "${BLUE}========== 服务状态 ==========${NC}"
docker compose ps
echo ""

# 7. 自动跑增量 SQL (10_*.sql 之后的所有文件, 幂等执行)
echo -e "${BLUE}[6/8] 执行增量 SQL (10_*.sql 及以后, 幂等)...${NC}"
APPLIED_LOG="$PROJECT_DIR/sql/.applied.log"
mkdir -p "$(dirname "$APPLIED_LOG")"
touch "$APPLIED_LOG"

run_incremental_sql() {
    local f="$1"
    local bn; bn=$(basename "$f")
    # 已跑过就跳过
    if grep -qxF "$bn" "$APPLIED_LOG" 2>/dev/null; then
        echo -e "  ${YELLOW}⏭ $bn (已记录, 跳过)${NC}"
        return 0
    fi
    # 执行 (docker compose exec + 默认字符集)
    if docker compose exec -T erp-mysql mysql \
        -uroot -perp_root_pwd \
        --default-character-set=utf8mb4 \
        industrial_erp < "$f" 2>&1 | grep -v "Using a password" | grep -v "Warning"; then
        echo -e "  ${GREEN}✓ $bn${NC}"
        echo "$bn" >> "$APPLIED_LOG"
        return 0
    else
        echo -e "  ${RED}✗ $bn 执行失败 (见上方), 升级终止${NC}"
        return 1
    fi
}

SQL_FAILED=0
# 只跑 10_ 及以后 (01-09 是首次初始化)
for f in $(ls -1 "$PROJECT_DIR"/sql/1[0-9]*.sql "$PROJECT_DIR"/sql/2[0-9]*.sql 2>/dev/null | sort); do
    [ -f "$f" ] || continue
    run_incremental_sql "$f" || { SQL_FAILED=1; break; }
done
if [ $SQL_FAILED -eq 1 ]; then
    echo -e "${RED}========== 升级失败: SQL 增量执行出错 ==========${NC}"
    echo -e "  ${YELLOW}回滚命令:${NC}"
    echo -e "    ${BLUE}git reset --hard ${CURRENT}${NC}"
    echo -e "    ${BLUE}docker compose up -d --build${NC}"
    echo -e "  ${YELLOW}修复后再手动跑: docker compose exec -T erp-mysql mysql -uroot -perp_root_pwd industrial_erp < sql/<文件>.sql${NC}"
    exit 1
fi
echo ""

# 8. 重新跑一下应用层健康检查
echo -e "${BLUE}[7/8] 健康检查...${NC}"
sleep 5
HEALTH_OK=1
for c in erp-mysql erp-backend erp-pc-web erp-app-h5; do
    if docker ps --format '{{.Names}}' | grep -qx "$c"; then
        STATE=$(docker inspect --format '{{.State.Health.Status}}' "$c" 2>/dev/null || echo "no-healthcheck")
        if [ "$STATE" = "healthy" ] || [ "$STATE" = "no-healthcheck" ]; then
            echo -e "  ${GREEN}✓ $c ($STATE)${NC}"
        else
            echo -e "  ${YELLOW}! $c ($STATE)${NC}"
            HEALTH_OK=0
        fi
    else
        echo -e "  ${RED}✗ $c 未运行${NC}"
        HEALTH_OK=0
    fi
done

echo ""
echo -e "${BLUE}[8/8] 升级总结...${NC}"
echo -e "  起始 commit: ${YELLOW}${CURRENT}${NC}"
echo -e "  当前 commit: ${GREEN}${NEW_COMMIT}${NC}"
if [ $HEALTH_OK -eq 1 ]; then
    echo -e "  服务健康:   ${GREEN}全部正常${NC}"
else
    echo -e "  服务健康:   ${YELLOW}部分异常, 请检查上方输出${NC}"
fi
echo -e "  已应用 SQL: ${YELLOW}$(wc -l < "$APPLIED_LOG" 2>/dev/null || echo 0) 份 (见 sql/.applied.log)${NC}"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  ✅ 升级完成${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  ${YELLOW}如遇问题, 回滚:${NC}"
echo -e "    ${BLUE}git reset --hard ${CURRENT}${NC}"
echo -e "    ${BLUE}docker compose up -d --build${NC}"
echo -e "    ${BLUE}docker compose exec -T erp-mysql mysql -uroot -perp_root_pwd industrial_erp < /path/to/snapshot.sql${NC}"
echo ""
