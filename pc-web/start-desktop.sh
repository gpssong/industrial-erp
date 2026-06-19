#!/bin/bash
# 工业ERP 桌面启动脚本
# 使用系统 Chrome 应用模式打开（无地址栏、无标签栏）
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --app="http://localhost:5173" \
  --window-size="1400,900" \
  --window-position="100,50" \
  --no-first-run \
  --no-default-browser-check \
  --user-data-dir="$HOME/.erp-desktop-profile"
