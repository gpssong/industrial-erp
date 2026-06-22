#!/bin/bash
# ============================================================
#  飞牛 OS (fnOS) 一键部署脚本
#  用法: ./scripts/fnos-deploy.sh [/path/to/erp-system]
# ============================================================
set -e

# 默认路径: 当前目录 (推荐 cd /vol1/docker/erp-system 后执行)
PROJECT_DIR="${1:-$(pwd)}"
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  工业 ERP - 飞牛 OS 一键部署${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${YELLOW}项目目录: ${PROJECT_DIR}${NC}"
echo ""

# 1. 检查环境
echo -e "${BLUE}[1/6] 检查环境...${NC}"
if ! command -v docker &> /dev/null; then
  echo -e "${RED}[ERROR] docker 未找到, 请先在 fnOS 桌面开启 Docker 应用${NC}"
  exit 1
fi
if ! docker compose version &> /dev/null; then
  echo -e "${RED}[ERROR] docker compose 未找到, fnOS 默认应已安装 Compose V2${NC}"
  exit 1
fi
DOCKER_VER=$(docker --version)
COMPOSE_VER=$(docker compose version)
echo -e "${GREEN}  ✓ ${DOCKER_VER}${NC}"
echo -e "${GREEN}  ✓ ${COMPOSE_VER}${NC}"

# 2. 检测飞牛系统
echo -e "${BLUE}[2/6] 检测飞牛系统...${NC}"
if [ -f /etc/fnos-release ] || [ -f /etc/os-release ] && grep -qi "fnos" /etc/os-release 2>/dev/null; then
  echo -e "${GREEN}  ✓ 检测到飞牛 OS${NC}"
elif uname -r | grep -qi "fnos\|truenas\|scale"; then
  echo -e "${GREEN}  ✓ 检测到类 Unix NAS 系统${NC}"
else
  echo -e "${YELLOW}  ⚠ 未明确识别到 fnOS, 脚本仍可继续${NC}"
fi

# 检查 btrfs
if mount | grep -q "btrfs"; then
  echo -e "${GREEN}  ✓ 检测到 btrfs 文件系统 (支持快照)${NC}"
  BTRFS_OK=1
else
  echo -e "${YELLOW}  ⚠ 未检测到 btrfs, 跳过快照功能${NC}"
  BTRFS_OK=0
fi

# 3. 项目文件检查
echo -e "${BLUE}[3/6] 检查项目文件...${NC}"
cd "$PROJECT_DIR"
for f in docker-compose.yml backend/Dockerfile pc-web/Dockerfile .env.example; do
  if [ ! -e "$f" ]; then
    echo -e "${RED}[ERROR] 缺少文件: $f${NC}"
    echo "请确认项目已完整上传到 $PROJECT_DIR"
    exit 1
  fi
  echo -e "${GREEN}  ✓ $f${NC}"
done

# 4. 配置 .env
echo -e "${BLUE}[4/6] 配置环境变量...${NC}"
if [ ! -f .env ]; then
  cp .env.example .env
  echo -e "${YELLOW}  ! 已生成 .env (默认密码 erp_root_pwd, 请尽快修改!)${NC}"
else
  echo -e "${GREEN}  ✓ .env 已存在, 跳过${NC}"
fi

# 5. btrfs 快照 (首次部署前)
if [ "$BTRFS_OK" = "1" ] && [ ! -d ".snapshots" ]; then
  echo -e "${BLUE}[5/6] 初始化 btrfs 快照目录...${NC}"
  sudo mkdir -p .snapshots
  sudo chmod 755 .snapshots
  echo -e "${GREEN}  ✓ 快照目录已就绪${NC}"
else
  echo -e "${BLUE}[5/6] 跳过快照初始化${NC}"
fi

# 6. 构建并启动
echo -e "${BLUE}[6/6] 构建并启动服务 (首次 5~10 分钟)...${NC}"
docker compose up -d --build

# 等待健康检查
echo ""
echo -e "${YELLOW}等待服务健康检查 (约 30~60 秒)...${NC}"
sleep 30

# 显示状态
echo ""
echo -e "${BLUE}========== 服务状态 ==========${NC}"
docker compose ps
echo ""

# 获取本机 IP
NAS_IP=$(hostname -I | awk '{print $1}')
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  ✅ 部署完成!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "  PC Web 前台: ${YELLOW}http://${NAS_IP}${NC}"
echo -e "  接口文档:    ${YELLOW}http://${NAS_IP}:8080/api/doc.html${NC}"
echo -e "  默认账号:    ${YELLOW}admin / admin123${NC}"
echo ""
echo -e "  常用命令:"
echo -e "    查看日志:   ${BLUE}docker compose logs -f backend${NC}"
echo -e "    重启服务:   ${BLUE}docker compose restart${NC}"
echo -e "    完全停止:   ${BLUE}docker compose down${NC}"
echo -e "    备份数据:   ${BLUE}./scripts/fnos-backup.sh${NC}"
echo -e "    升级系统:   ${BLUE}./scripts/fnos-upgrade.sh${NC}"
echo ""
