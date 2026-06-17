#!/bin/bash
# 一键启动 ERP 系统
set -e
cd "$(dirname "$0")/.."
echo "==================================="
echo "  工业 ERP 一键启动"
echo "==================================="
if ! command -v docker &>/dev/null; then
  echo "[ERROR] Docker 未安装, 请先安装 Docker"
  exit 1
fi
if [ ! -f .env ]; then
  cp .env.example .env
  echo "[INFO] 已创建 .env, 默认密码: erp_root_pwd, 生产环境请修改"
fi
docker compose up -d --build
echo ""
echo "✅ 启动成功!"
echo "PC 管理后台: http://localhost"
echo "后端 API:    http://localhost/api"
echo "接口文档:    http://localhost/api/doc.html"
echo "默认账号: admin / admin123"
echo "查看日志: docker compose logs -f backend"
echo "停止:    docker compose down"
