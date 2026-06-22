#!/bin/bash
# ============================================================
#  飞牛 OS 一键部署后端脚本 (仅 Spring Boot + MySQL + Redis)
#  用法: ./scripts/fnos-backend-deploy.sh [/path/to/erp-system]
# ============================================================
set -e

PROJECT_DIR="${1:-$(pwd)}"
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
COMPOSE_FILE="docker-compose.backend.yml"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  工业 ERP - 仅后端部署 (飞牛)${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${YELLOW}项目目录: ${PROJECT_DIR}${NC}"
echo -e "${YELLOW}Compose:   ${COMPOSE_FILE}${NC}"
echo ""

cd "$PROJECT_DIR"

# 1. 检查环境
echo -e "${BLUE}[1/5] 检查环境...${NC}"
if ! command -v docker &>/dev/null; then
  echo -e "${RED}[ERROR] docker 未找到, 请先在 fnOS 桌面开启 Docker 应用${NC}"
  exit 1
fi
if ! docker compose version &>/dev/null; then
  echo -e "${RED}[ERROR] docker compose 未找到${NC}"
  exit 1
fi
echo -e "${GREEN}  ✓ docker / docker compose 就绪${NC}"

# 2. 检查项目文件
echo -e "${BLUE}[2/5] 检查项目文件...${NC}"
for f in "$COMPOSE_FILE" backend/Dockerfile .env.example sql; do
  if [ ! -e "$f" ]; then
    echo -e "${RED}[ERROR] 缺少: $f${NC}"
    exit 1
  fi
done
echo -e "${GREEN}  ✓ compose / backend / sql 齐全${NC}"

# 3. 配置 .env
echo -e "${BLUE}[3/5] 配置环境变量...${NC}"
if [ ! -f .env ]; then
  cp .env.example .env
  echo -e "${YELLOW}  ! 已生成 .env, 默认密码 erp_root_pwd, 请尽快修改${NC}"
fi

# 自动读取密码并显示
MYSQL_PWD=$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2 | tr -d '"' | tr -d ' ')
if [ "$MYSQL_PWD" = "erp_root_pwd" ]; then
  echo -e "${YELLOW}  ! 警告: MySQL 密码是默认值, 生产环境务必修改!${NC}"
fi

# 4. 启动
echo -e "${BLUE}[4/5] 构建并启动 (首次 5~10 分钟)...${NC}"
docker compose -f "$COMPOSE_FILE" up -d --build

# 5. 等待健康检查
echo -e "${BLUE}[5/5] 等待服务健康检查...${NC}"
sleep 30

echo ""
echo -e "${BLUE}========== 服务状态 ==========${NC}"
docker compose -f "$COMPOSE_FILE" ps
echo ""

# 获取 IP
NAS_IP=$(hostname -I | awk '{print $1}')

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  ✅ 后端部署完成${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "  ${YELLOW}后端 API 入口:${NC}"
echo -e "    ${BLUE}http://${NAS_IP}:8080/api${NC}"
echo ""
echo -e "  ${YELLOW}接口文档 (Knife4j):${NC}"
echo -e "    ${BLUE}http://${NAS_IP}:8080/api/doc.html${NC}"
echo ""
echo -e "  ${YELLOW}健康检查:${NC}"
echo -e "    ${BLUE}http://${NAS_IP}:8080/api/auth/captcha${NC}"
echo ""
echo -e "  ${YELLOW}默认账号: admin / admin123${NC}"
echo ""
echo -e "  ${YELLOW}前端调用方式:${NC}"
echo -e "    本地 Vite 开发: .env.development 里 VITE_API_BASE_URL=http://${NAS_IP}:8080/api"
echo -e "    生产前端部署: Nginx 反代 ${NAS_IP}:8080"
echo ""
echo -e "  ${YELLOW}常用命令:${NC}"
echo -e "    查看后端日志: ${BLUE}docker compose -f $COMPOSE_FILE logs -f backend${NC}"
echo -e "    重启后端:     ${BLUE}docker compose -f $COMPOSE_FILE restart backend${NC}"
echo -e "    停止所有:     ${BLUE}docker compose -f $COMPOSE_FILE down${NC}"
echo -e "    查看连接数:   ${BLUE}docker exec erp-mysql mysql -uroot -p密码 industrial_erp -e 'show processlist'${NC}"
echo ""
