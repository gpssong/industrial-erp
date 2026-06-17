#!/bin/bash
# ================================================================
# 工业 ERP 本地一键启动脚本 (无 Docker 版本,适合 macOS + Homebrew)
# 适用: 开发、调试、单元测试
# 用法: bash scripts/start-local.sh
# ================================================================
set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC}  $*"; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/.local-logs"
mkdir -p "$LOG_DIR"

# ============================================================
# 0. 清理可能残留的 brew 进程(避免锁冲突)
# ============================================================
info "检查 brew 残留进程..."
BREW_RUNNING=$(pgrep -f "brew (install|download|upgrade)" 2>/dev/null | wc -l | tr -d ' ')
if [ "$BREW_RUNNING" -gt 0 ]; then
  warn "发现 $BREW_RUNNING 个 brew 进程残留,清理中..."
  pkill -9 -f "brew install" 2>/dev/null || true
  pkill -9 -f "brew download" 2>/dev/null || true
  sleep 2
  ok "已清理"
else
  ok "无残留"
fi

# ============================================================
# 1. 检查 / 安装 依赖
# ============================================================
info "检查本地环境..."

# Homebrew
if ! command -v brew &>/dev/null; then
  err "未检测到 Homebrew"
  err "安装: /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
  exit 1
fi
ok "Homebrew $(brew --version | head -1)"

# 一次性检查所有包,缺失的合并到一个 brew install(避免锁冲突)
NEED_INSTALL=()
for pkg in openjdk@17 maven mysql@8.0 redis; do
  if ! brew list "$pkg" &>/dev/null; then
    NEED_INSTALL+=("$pkg")
  fi
done

if [ ${#NEED_INSTALL[@]} -gt 0 ]; then
  info "需要安装: ${NEED_INSTALL[*]}"
  warn "首次安装约 10-20 分钟(下载 + 编译/解压)..."
  warn "期间请勿 Ctrl+C, 等待完成..."
  echo ""
  # 关键:一次命令装多个,brew 内部统一调度依赖下载,不会锁冲突
  HOMEBREW_NO_AUTO_UPDATE=1 brew install "${NEED_INSTALL[@]}"
  echo ""
  ok "依赖安装完成"
else
  ok "所有依赖已安装: openjdk@17 maven mysql@8.0 redis"
fi

# 配置 Java 环境变量(写入 .zshrc,只写一次)
JAVA_HOME_LINE='export JAVA_HOME=$(/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home)'
if ! grep -q "JAVA_HOME.*openjdk" ~/.zshrc 2>/dev/null; then
  echo "" >> ~/.zshrc
  echo "# ERP Java 17" >> ~/.zshrc
  echo "$JAVA_HOME_LINE" >> ~/.zshrc
  echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.zshrc
  warn "已写入 ~/.zshrc, 请执行: source ~/.zshrc"
fi
export JAVA_HOME="$(/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home 2>/dev/null || true)"
export PATH="$JAVA_HOME/bin:$PATH"

# 启动 MySQL 服务
if ! brew services list 2>/dev/null | grep -q "mysql.*started"; then
  info "启动 MySQL 服务..."
  brew services start mysql@8.0
  ok "MySQL 服务已启动"
else
  ok "MySQL 服务运行中"
fi

# 启动 Redis 服务
if ! brew services list 2>/dev/null | grep -q "redis.*started"; then
  info "启动 Redis 服务..."
  brew services start redis
  ok "Redis 服务已启动"
else
  ok "Redis 服务运行中"
fi

# 等待 MySQL 就绪
info "等待 MySQL 就绪..."
for i in {1..30}; do
  if mysqladmin ping -h 127.0.0.1 -uroot --silent 2>/dev/null; then
    ok "MySQL 已就绪"
    break
  fi
  sleep 1
done
if ! mysqladmin ping -h 127.0.0.1 -uroot >/dev/null 2>&1; then
  err "MySQL 30 秒内未就绪"
  err "请手动验证: mysql -uroot -p"
  err "如果是首次安装,需要执行: mysql_secure_installation 设置 root 密码"
  exit 1
fi

# ============================================================
# 2. 创建数据库 + 导入 SQL
# ============================================================
DB_NAME="industrial_erp"
MYSQL_PWD=${MYSQL_ROOT_PASSWORD:-"root"}

# 探测正确的 root 密码
if ! mysql -h 127.0.0.1 -uroot -p"$MYSQL_PWD" -e "SELECT 1" >/dev/null 2>&1; then
  warn "密码 '$MYSQL_PWD' 不对,尝试空密码..."
  if mysql -h 127.0.0.1 -uroot -e "SELECT 1" >/dev/null 2>&1; then
    MYSQL_PWD=""
    ok "MySQL root 无密码"
  else
    err "MySQL root 密码探测失败"
    err "请先这样登录: mysql -uroot -p"
    err "登录后执行: ALTER USER 'root'@'localhost' IDENTIFIED BY 'root';"
    err "然后重跑本脚本: export MYSQL_ROOT_PASSWORD=root && bash scripts/start-local.sh"
    exit 1
  fi
fi

info "创建数据库 $DB_NAME (root 密码: ${MYSQL_PWD:-<空>})..."
mysql -h 127.0.0.1 -uroot -p"$MYSQL_PWD" -e "CREATE DATABASE IF NOT EXISTS $DB_NAME DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>/dev/null
ok "数据库已就绪"

# 检查是否已经导入过(用 sys_user 表的存在性判断)
TABLE_EXISTS=$(mysql -h 127.0.0.1 -uroot -p"$MYSQL_PWD" -BN -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='sys_user';" 2>/dev/null)
if [ "$TABLE_EXISTS" = "1" ]; then
  warn "检测到数据库已初始化(sys_user 存在),跳过 SQL 导入"
  info "如需重新导入,先: mysql -uroot -p -e \"DROP DATABASE $DB_NAME\""
else
  info "导入 SQL (9 个文件, 60+ 表)..."
  for f in "$ROOT"/sql/*.sql; do
    if [ -f "$f" ]; then
      info "  → $(basename "$f")"
      mysql -h 127.0.0.1 -uroot -p"$MYSQL_PWD" "$DB_NAME" < "$f" 2>&1 | grep -v "Using a password" || true
    fi
  done
  ok "SQL 导入完成"
fi

# ============================================================
# 3. 修改后端 dev 配置(写入 MySQL 密码)
# ============================================================
DEV_CFG="$ROOT/backend/src/main/resources/application-dev.yml"
if [ -f "$DEV_CFG" ]; then
  if grep -q "password: $MYSQL_PWD" "$DEV_CFG" 2>/dev/null; then
    ok "后端配置已是当前密码"
  else
    sed -i.bak "s|password:.*|password: $MYSQL_PWD|" "$DEV_CFG"
    ok "已写入 MySQL 密码到 $DEV_CFG"
  fi
fi

# ============================================================
# 4. 启动后端 (后台)
# ============================================================
if [ -f "$LOG_DIR/backend.pid" ] && kill -0 "$(cat $LOG_DIR/backend.pid)" 2>/dev/null; then
  ok "后端已在运行 (PID $(cat $LOG_DIR/backend.pid))"
else
  info "启动后端 SpringBoot (首次编译 2~5 分钟)..."
  cd "$ROOT/backend"
  nohup mvn spring-boot:run -Dspring-boot.run.profiles=dev \
    > "$LOG_DIR/backend.log" 2>&1 &
  BACKEND_PID=$!
  echo $BACKEND_PID > "$LOG_DIR/backend.pid"
  ok "后端进程 PID: $BACKEND_PID"

  info "等待后端就绪 (http://localhost:8080)..."
  for i in {1..90}; do
    if curl -sf http://localhost:8080/api/actuator/health -o /dev/null 2>&1 \
       || curl -sf http://localhost:8080/api/auth/captcha -o /dev/null 2>&1 \
       || curl -sf http://localhost:8080/api/doc.html -o /dev/null 2>&1; then
      ok "后端已就绪"
      break
    fi
    sleep 2
    echo -n "."
  done
  echo ""

  if ! curl -sf http://localhost:8080/api/actuator/health -o /dev/null 2>&1 \
     && ! curl -sf http://localhost:8080/api/doc.html -o /dev/null 2>&1; then
    warn "后端 3 分钟内未就绪"
    warn "查看日志: tail -f $LOG_DIR/backend.log"
  fi
fi

# ============================================================
# 5. 启动前端 (后台)
# ============================================================
if [ ! -d "$ROOT/pc-web/node_modules" ]; then
  info "安装前端依赖 (3-5 分钟)..."
  cd "$ROOT/pc-web" && npm install --registry=https://registry.npmmirror.com --no-audit --no-fund
fi

if [ -f "$LOG_DIR/frontend.pid" ] && kill -0 "$(cat $LOG_DIR/frontend.pid)" 2>/dev/null; then
  ok "前端已在运行 (PID $(cat $LOG_DIR/frontend.pid))"
else
  info "启动前端 Vite..."
  cd "$ROOT/pc-web"
  nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 &
  FRONTEND_PID=$!
  echo $FRONTEND_PID > "$LOG_DIR/frontend.pid"
  ok "前端进程 PID: $FRONTEND_PID"

  info "等待 Vite 就绪 (http://localhost:5173)..."
  for i in {1..30}; do
    if curl -sf http://localhost:5173 -o /dev/null 2>&1; then
      ok "前端已就绪"
      break
    fi
    sleep 1
    echo -n "."
  done
  echo ""
fi

# ============================================================
# 6. 完成
# ============================================================
echo ""
echo "==========================================="
echo "  ERP 启动完成!"
echo "==========================================="
echo "  PC 后台:    http://localhost:5173"
echo "  后端 API:   http://localhost:8080/api"
echo "  接口文档:   http://localhost:8080/api/doc.html"
echo "  健康检查:   http://localhost:8080/api/actuator/health"
echo "  默认账号:   admin / admin123"
echo ""
echo "  实时日志:"
echo "    tail -f $LOG_DIR/backend.log"
echo "    tail -f $LOG_DIR/frontend.log"
echo "  停止:"
echo "    kill \$(cat $LOG_DIR/backend.pid) \$(cat $LOG_DIR/frontend.pid)"
echo "==========================================="

# 尝试打开浏览器
sleep 2
if command -v open &>/dev/null; then
  open "http://localhost:5173" 2>/dev/null || true
fi
