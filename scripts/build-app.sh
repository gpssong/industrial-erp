#!/bin/bash
# ============================================================
# v1.1.49: 一键重新打包 ERP App APK
#
# 背景: v1.1.48 踩坑 — APK 内嵌 H5 资源陈旧 (8月31日), 代码早写了
#        await api.warningList() 但 App 用户看不到. 原因: cap sync + assembleDebug
#        漏了任何一步, APK 就是旧的. 这个脚本把全流程固化.
#
# 用法: ./scripts/build-app.sh [输出 APK 路径]
# 默认输出: /Users/tongban/Documents/根据前端开发erp 2/erp-app-$(date +%Y%m%d).apk
# 也输出到: ~/Desktop/erp-app-$(date +%Y%m%d).apk
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/../app"

DATE=$(date +%Y%m%d)
DEFAULT_DEST_DIR="/Users/tongban/Documents/根据前端开发erp 2"
DEST="${1:-$DEFAULT_DEST_DIR/erp-app-$DATE.apk}"
DESKTOP_DEST="$HOME/Desktop/erp-app-$DATE.apk"

echo "==> [1/5] Java 17 环境"
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "==> [2/5] 清理 dist/build/h5 + cap sync 缓存"
rm -rf dist/build
rm -rf unpackage/cache
# v1.1.29 修复: cap sync 不会重置 capacitor.plugins.json, 不要删

echo "==> [3/5] npm run build:h5"
npm run build:h5

echo "==> [4/5] npx cap sync android"
npx cap sync android

echo "==> [4.5/5] v1.1.48 bug 防御: 检查 capacitor.settings.gradle 含 :capacitor-share"
if ! grep -q ":capacitor-share" android/capacitor.settings.gradle; then
    echo "✗ 缺少 :capacitor-share 配置! 自动追加..."
    cat >> android/capacitor.settings.gradle <<'EOF'

// v1.1.49 build-app.sh 自动追加 (CLI 6.x cap sync 不会扫 @capacitor/share)
include ':capacitor-share'
project(':capacitor-share').projectDir = new File('../node_modules/@capacitor/share/android')
EOF
fi

echo "==> [5/5] ./gradlew assembleDebug (不带 --offline, 新插件需离线缓存)"
(cd android && ./gradlew assembleDebug 2>&1 | tail -20)

# 清 share 插件 build 缓存 (v1.1.48 踩坑: 第一次 build 失败, 重跑才成)
rm -rf node_modules/@capacitor/share/android/build
(cd android && ./gradlew assembleDebug 2>&1 | tail -10)

APK=android/app/build/outputs/apk/debug/app-debug.apk
if [ ! -f "$APK" ]; then
    echo "✗ APK 生成失败: $APK 不存在"
    exit 1
fi

MD5=$(md5 "$APK" | awk '{print $NF}')
SIZE=$(wc -c < "$APK" | tr -d ' ')

mkdir -p "$(dirname "$DEST")"
cp "$APK" "$DEST"
cp "$APK" "$DESKTOP_DEST"

echo ""
echo "=========================================="
echo "✅ APK 生成成功"
echo "   路径1: $DEST"
echo "   路径2: $DESKTOP_DEST"
echo "   MD5:   $MD5"
echo "   大小:  $SIZE 字节"
echo "=========================================="
echo ""
echo "部署步骤:"
echo "1. 卸载用户手机上的旧 ERP App (避免签名/资源冲突)"
echo "2. 微信/钉钉发送 $DEST"
echo "3. 用户安装 → 打开 → 工作台 → 库存预警 → 应能看到具体产品卡片"
echo ""
echo "回滚: 用户手机装回上一个 APK (git log 找上一次 commit)"
