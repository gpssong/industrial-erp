#!/bin/bash
# =============================================================================
# 打包 ERP 项目为 zip, 用于传到群晖 NAS (零 SSH 方案的核心步骤)
# =============================================================================
# 为什么用 zip 而不是 tar.gz:
#   macOS BSD tar 默认 pax 格式, 对中文路径会生成 PaxHeader 扩展头.
#   群晖 File Station 解压 pax tar 时会把它错误解析为真实目录, 导致
#   源码包成 com/industrial/erp/PaxHeader/IndustrialErpApplication.java 这种
#   诡异结构, maven 编译全部失败.
#   zip 没有这个问题, 且 File Station 对 zip 解压是 DSM 原生支持, 最稳.
#
# 用法:
#   bash scripts/package-nas.sh                   # 输出到桌面 erp-system.zip
#   bash scripts/package-nas.sh /path/to/out.zip # 输出到指定路径
# =============================================================================
set -e

# 项目根目录 = 脚本所在目录的父目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_NAME="$(basename "$ROOT_DIR")"

OUT="${1:-$HOME/Desktop/$PROJECT_NAME.zip}"

# 检查 zip 命令
if ! command -v zip >/dev/null 2>&1; then
  echo "[FAIL] zip 命令未找到. macOS 自带, 应该可用."
  echo "  若是 Linux: sudo apt install zip / sudo yum install zip"
  exit 1
fi

# 检查当前目录
if [ ! -d "$ROOT_DIR/backend/src" ] || [ ! -f "$ROOT_DIR/docker-compose.yml" ]; then
  echo "[FAIL] $ROOT_DIR 看起来不是 ERP 项目根目录"
  exit 1
fi

echo "==========================================="
echo "  ERP -> NAS 打包"
echo "==========================================="
echo "  源: $ROOT_DIR"
echo "  目标: $OUT"
echo ""

# 已存在则删除
[ -f "$OUT" ] && rm -f "$OUT" && echo "[INFO] 删除已存在的 $OUT"

cd "$(dirname "$ROOT_DIR")"

# 打包. 排除项:
#   */target     - Java 编译产物
#   */node_modules - npm 依赖 (容器内重装)
#   */dist       - 前端 build 产物 (容器内重 build)
#   */.git       - Git 历史 (容器内不需要, NAS 上要的话单独 git clone)
#   */release-build - 桌面端构建产物
#   */.local-logs, */.local-config - 本地开发残留
#   */.DS_Store, */__MACOSX - macOS 元数据
#   */.env       - 用户的真实环境变量, 绝不入包 (NAS 上从 .env.example 复制)
zip -rq "$OUT" "$PROJECT_NAME" \
  -x '*/target/*' \
  -x '*/node_modules/*' \
  -x '*/dist/*' \
  -x '*/.git/*' \
  -x '*/release-build/*' \
  -x '*/.local-logs/*' \
  -x '*/.local-config/*' \
  -x '*/.DS_Store' \
  -x '*/__MACOSX/*' \
  -x '*/.env'

echo "[INFO] 打包完成, 校验..."
echo ""

# 校验: PaxHeader 应为 0
PAX_COUNT=$(unzip -l "$OUT" 2>/dev/null | grep -ci pax || true)
if [ "$PAX_COUNT" -gt 0 ]; then
  echo "[WARN] 包内发现 $PAX_COUNT 个 PaxHeader 条目! 群晖解压可能出问题."
  echo "       请检查: 路径中是否有 macOS 资源叉 (._) 文件未被排除?"
  unzip -l "$OUT" 2>/dev/null | grep -i pax | head -5
  exit 1
fi

# 校验: 关键文件必须存在
KEY_FILES=(
  "docker-compose.yml"
  ".env.example"
  "backend/src/main/java/com/industrial/erp/IndustrialErpApplication.java"
  "backend/Dockerfile"
  "pc-web/Dockerfile"
  "pc-web/package.json"
)
MISSING=0
for f in "${KEY_FILES[@]}"; do
  if ! unzip -l "$OUT" 2>/dev/null | grep -qE " ${PROJECT_NAME}/${f}\$"; then
    echo "[FAIL] 关键文件缺失: $f"
    MISSING=$((MISSING+1))
  fi
done
if [ $MISSING -gt 0 ]; then
  echo "[FAIL] 缺少 $MISSING 个关键文件, 请检查源目录"
  exit 1
fi

# 校验: 排除项应空
EXCLUDED=$(unzip -l "$OUT" 2>/dev/null | grep -cE "target/|node_modules/|\\.DS_Store|/\\.env\$" || true)
if [ "$EXCLUDED" -gt 0 ]; then
  echo "[WARN] 排除未生效, 还有 $EXCLUDED 个 target/node_modules/.DS_Store/.env 进了包"
  unzip -l "$OUT" 2>/dev/null | grep -E "target/|node_modules/|\\.DS_Store|/\\.env\$" | head -5
fi

# 汇总
SIZE=$(du -h "$OUT" | cut -f1)
COUNT=$(unzip -l "$OUT" 2>/dev/null | tail -1 | awk '{print $2}')

echo "[OK] 验证通过"
echo "      路径: $OUT"
echo "      大小: $SIZE"
echo "      文件: $COUNT 个"
echo "      PaxHeader: 0 (安全)"
echo ""
echo "==========================================="
echo "  下一步"
echo "==========================================="
echo "  1. Finder 拖 $OUT 到 SMB 共享 (Finder 前往 smb://NAS_IP/docker)"
echo "  2. File Station 在 docker/erp-system/ 下解压缩 $OUT"
echo "  3. File Station 重命名 .env.example -> .env 并编辑密码/CORS"
echo "  4. Container Manager -> 项目 -> 创建 -> 选 docker-compose.yml"
echo "  5. 首次构建 5-15 分钟, 完成后访问 http://NAS_IP"
echo ""
echo "  详细: docs/17_群晖NAS部署指南.md"
