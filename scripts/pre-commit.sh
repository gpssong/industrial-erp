#!/bin/bash
# ============================================================
# pre-commit hook — 校验:
#   1. 版本号在 7 个发布源中一致 (scripts/check-version.sh)
#   2. npm lockfile 一致性 (pc-web / app)
#   3. pc-web lint (error 级: 模板引用未定义标识符)
#   4. 实体字段 vs DDL 对账 (防"改实体不补 DDL"回归, v1.1.130 SOP/委外 500 教训)
#
# 用法: git config core.hooksPath .githooks
#      (.githooks/pre-commit 会转调本文件)
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

# -----------------------------------------------------------
# 1. 版本号一致性 (v1.1.183: 改代码时顺手改版本号, 最容易漏 SystemVersionInitializer
#    的 @Value 默认值 —— CI 也有同一道检查, 但本地先拦一道省一次 CI 往返)
# -----------------------------------------------------------
echo "==> [pre-commit] 校验版本号一致性..."
if bash scripts/check-version.sh; then
  echo "  ✅ 版本号一致"
else
  echo "  ❌ 版本号不一致, 提交前请对齐 (scripts/check-version.sh 会列出具体哪一处不一致)"
  FAILED=1
fi

# -----------------------------------------------------------
# 2. npm lockfile 一致性
# -----------------------------------------------------------
echo ""
echo "==> [pre-commit] 校验 npm lockfile 一致性..."

for DIR in pc-web app; do
  if [ ! -d "$DIR" ]; then
    continue
  fi

  LOCKFILE="$DIR/package-lock.json"
  if [ ! -f "$LOCKFILE" ]; then
    echo "  ⚠  $DIR/package-lock.json 不存在, 跳过"
    continue
  fi

  # npm ci --dry-run 会校验 lockfile 与当前 node_modules 是否一致,
  # 不一致会报错退出码 1, 且不实际安装
  echo "  → $DIR npm ci --dry-run"
  if (cd "$DIR" && npm ci --dry-run --no-audit --no-fund > /dev/null 2>&1); then
    echo "    ✅ lockfile 一致"
  else
    echo "    ❌ lockfile 不一致! 请运行: cd $DIR && npm install"
    FAILED=1
  fi
done

# -----------------------------------------------------------
# 3. pc-web lint (v1.1.183): 只拦 error 级 —— 模板引用了 <script setup> 里不存在的
#    标识符 (vue/no-undef-properties) 编译期不报错, 线上点按钮才炸 (空弹窗事故根因).
#    风格类规则全部 warn, 不会卡住历史代码.
# -----------------------------------------------------------
echo ""
echo "==> [pre-commit] pc-web lint..."
if [ ! -x "pc-web/node_modules/.bin/eslint" ]; then
  echo "  ⚠  pc-web 缺 eslint (node_modules 与 package.json 不同步), 跳过 (先 cd pc-web && npm ci)"
elif (cd pc-web && npm run lint --silent > /tmp/erp-pc-lint.log 2>&1); then
  echo "  ✅ lint 通过"
else
  echo "  ❌ lint 未通过:"
  tail -30 /tmp/erp-pc-lint.log | sed 's/^/     /'
  FAILED=1
fi

# -----------------------------------------------------------
# 4. 实体字段 vs DDL 对账 (离线 sql/ + 集合字段必查)
# 离线模式: 集合字段没标 exist=false → 硬 fail (真 500 风险, 与 DDL 完整性无关);
#          scalar 缺列只在离线 DDL 不全时误报 → 仅提示, 不 fail.
# 需要权威对账时手动: scripts/entity-schema-check.py --live-ssh (口令取自 ops-env, 见 scripts/ops-env.example)
# -----------------------------------------------------------
echo ""
echo "==> [pre-commit] 实体字段 vs DDL 对账 (scripts/entity-schema-check.py)..."
if [ -f "scripts/entity-schema-check.py" ]; then
  if python3 scripts/entity-schema-check.py; then
    echo "  ✅ 实体字段与 DDL 对齐"
  else
    # 离线模式下, 只有 error (集合字段/表缺失) 才 fail; 纯 warning 不 fail.
    # 上面的脚本: 有 error → exit 1, 只有 warning → exit 0. 所以 exit 1 = 真有问题.
    echo "  ❌ 实体有字段在 DDL 里不存在 / 集合字段没标 exist=false → 会 500"
    echo "     权威确认: scripts/entity-schema-check.py --live-ssh (口令取自 ops-env, 见 scripts/ops-env.example)"
    FAILED=1
  fi
else
  echo "  ⚠  scripts/entity-schema-check.py 不存在, 跳过"
fi

if [ $FAILED -eq 1 ]; then
  echo ""
  echo "❌ pre-commit 失败, 请修复后重试"
  exit 1
fi

echo ""
echo "==> [pre-commit] ✅ 全部校验通过"
