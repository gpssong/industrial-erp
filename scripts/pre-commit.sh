#!/bin/bash
# ============================================================
# v1.1.49 P2-22: pre-commit hook — 校验 npm lockfile 一致性
#
# 背景: 项目用 package-lock.json 而非 pnpm/yarn, 但没人校验
#       lockfile 是否与 node_modules 一致. 手动 `npm install`
#       可能漂移, 导致 CI 构建与本地不一致.
#
# 用法: 放到 .git/hooks/pre-commit (或用户手动 ln -s)
#       运行时自动检测 pc-web/ 和 app/ 的 package-lock.json
# ============================================================
set -euo pipefail

echo "==> [pre-commit] 校验 npm lockfile 一致性..."

FAILED=0

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

if [ $FAILED -eq 1 ]; then
  echo ""
  echo "❌ pre-commit 失败: lockfile 不一致, 请修复后重试"
  echo "   修复命令: cd pc-web && npm install && cd ../app && npm install"
  exit 1
fi

echo "==> [pre-commit] ✅ lockfile 校验通过"
