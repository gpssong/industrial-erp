#!/bin/bash
# 校验版本号在 6 个发布源中一致 (P2-版本漂移)
# 用法: scripts/check-version.sh [期望版本]
# 未传期望版本时以 pc-web/package.json 为准.
set -euo pipefail
cd "$(dirname "$0")/.."

pc=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' pc-web/package.json | head -1)
app=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' app/package.json | head -1)
man=$(sed -n 's/.*"versionName" *: *"\([^"]*\)".*/\1/p' app/src/manifest.json | head -1)
code=$(sed -n 's/.*"versionCode" *: *\([0-9]*\).*/\1/p' app/src/manifest.json | head -1)
profile=$(sed -n "s/.*APP_VERSION = '\([^']*\)'.*/\1/p" app/src/pages/profile/index.vue | head -1)
yml=$(sed -n 's/.*ERP_VERSION:\([0-9.]*\)}.*/\1/p' backend/src/main/resources/application.yml | head -1)
java=$(sed -n 's/.*erp.version:\([0-9.]*\).*/\1/p' backend/src/main/java/com/industrial/erp/config/SystemVersionInitializer.java | head -1)

expected="${1:-$pc}"
# 本项目 versionCode = 1000 + patch (1.1.128 → 1128, 见历史版本)
expected_code=$(echo "$expected" | awk -F. '{printf "%d", 1000 + $3}')

status=0
check() {
    local name="$1" actual="$2" want="$3"
    if [ "$actual" != "$want" ]; then
        printf 'MISMATCH  %-45s actual=%s expected=%s\n' "$name" "${actual:-<empty>}" "$want"
        status=1
    else
        printf 'OK        %-45s %s\n' "$name" "$actual"
    fi
}

check "pc-web/package.json" "$pc" "$expected"
check "app/package.json" "$app" "$expected"
check "app/src/manifest.json versionName" "$man" "$expected"
check "app/src/manifest.json versionCode" "$code" "$expected_code"
check "app/src/pages/profile/index.vue APP_VERSION" "$profile" "$expected"
check "application.yml erp.version default" "$yml" "$expected"
check "SystemVersionInitializer default" "$java" "$expected"

if [ "$status" -ne 0 ]; then
    echo "版本号不一致, 发布前必须统一 (见 CLAUDE.md R15)"
    exit 1
fi
echo "版本一致: $expected"
