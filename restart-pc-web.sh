#!/bin/bash
# 仅重启 pc-web 容器 (因为 dist 已经成功覆盖)
# 状态: home /tmp/pc-web-new/index.html md5 已确认 6f8412890933e4b033dcbb21121240ed
#       grep dashboard:kpi 命中 Index-DadcDsRj.js ✅
# 剩余: docker restart erp-pc-web (bind mount 自动生效,无需 rebuild)
#
# 用法: 在 mac 终端执行
#   sshpass -p '19850225aB' ssh gpssong@192.168.0.150 \
#     'printf "19850225aB\n" | sudo -S -p "" /usr/local/bin/docker restart erp-pc-web'
#   或者 (如果 sudo 需要 tty):
#   sshpass -p '19850225aB' ssh -tt gpssong@192.168.0.150 \
#     "echo '19850225aB' | sudo -S /usr/local/bin/docker restart erp-pc-web"

HOST=192.168.0.150
USER=gpssong
PASS=19850225aB

echo "===== 重启 home pc-web 容器 ====="
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST \
  "printf '$PASS\n' | sudo -S -p '' /usr/local/bin/docker restart erp-pc-web"

echo ""
echo "===== 验证容器状态 ====="
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST \
  "sleep 3 && /usr/local/bin/docker ps --format 'table {{.Names}}\t{{.Status}}' | grep pc-web || \
   printf '$PASS\n' | sudo -S -p '' /usr/local/bin/docker ps --format 'table {{.Names}}\t{{.Status}}' | grep pc-web"

echo ""
echo "===== 业务验证 ====="
echo "1. 打开浏览器强制刷新 (Cmd+Shift+R): http://home.93gushi.com:8088/"
echo "2. F12 → Network → 看 JS chunk 应该是 Index-DadcDsRj.js"
echo "3. 工作台 → 4 个卡片按 perm 独立显示"

echo ""
echo "===== 清理 ====="
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST \
  "printf '$PASS\n' | sudo -S -p '' rm -f /volume3/docker/erp-system/upload/pc-web-v1153.tar.gz" \
  && echo "✅ 完成"
