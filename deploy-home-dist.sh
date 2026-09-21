#!/bin/bash
# v1.1.53 pc-web dist 一键部署 (home NAS)
# 用法: 1. 上传 tar 到 NAS 后,在 mac 终端执行: bash deploy-home-dist.sh
#       2. 或者直接 SSH 到 NAS 执行 sudo 部分

set -e

LOCAL_DIST="/Users/tongban/Documents/根据前端开发erp 2/erp-system/pc-web/dist"
UPLOAD_DIR="/volume3/docker/erp-system/upload"
TAR_FILE="pc-web-v1153.tar.gz"
REMOTE_FINAL="/tmp/pc-web-new"
BACKUP="/tmp/pc-web-new.bak.$(date +%Y%m%d_%H%M%S)"

echo "===== Step 1: 打包本地 dist ====="
cd "$(dirname "$LOCAL_DIST")"
tar czf /tmp/$TAR_FILE -C "$(dirname "$LOCAL_DIST")" "$(basename "$LOCAL_DIST")"
ls -la /tmp/$TAR_FILE

echo ""
echo "===== Step 2: 上传到 home NAS ====="
sshpass -p '19850225aB' scp -o StrictHostKeyChecking=no \
  /tmp/$TAR_FILE gpssong@192.168.0.150:$UPLOAD_DIR/$TAR_FILE

echo ""
echo "===== Step 3: SSH 到 NAS 解压 + 重启 ====="
sshpass -p '19850225aB' ssh -o StrictHostKeyChecking=no \
  gpssong@192.168.0.150 'bash --noprofile --norc' <<REMOTE_EOF
export LANG=C LC_ALL=C PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
printf '19850225aB\n' | sudo -S -p '' bash -c '
set -e
echo "--- 备份旧版 ---"
if [ -d $REMOTE_FINAL ]; then
  rm -rf $BACKUP
  mv $REMOTE_FINAL $BACKUP
  echo "备份到 $BACKUP"
fi
echo "--- 解压新版 ---"
mkdir -p $REMOTE_FINAL
tar xzf $UPLOAD_DIR/$TAR_FILE -C $REMOTE_FINAL --strip-components=1
chown -R 1000:1000 $REMOTE_FINAL
echo "--- 验证 v1.1.53 dashboard 子模块代码 ---"
grep -l "dashboard:kpi" $REMOTE_FINAL/assets/*.js 2>/dev/null
md5sum $REMOTE_FINAL/index.html
du -sh $REMOTE_FINAL/
echo "--- 重启 pc-web 容器 (bind mount 自动生效) ---"
docker restart erp-pc-web
sleep 3
docker ps --format "table {{.Names}}\t{{.Status}}" | grep pc-web
echo "--- 清理 tar ---"
rm -f $UPLOAD_DIR/$TAR_FILE /tmp/$TAR_FILE
echo "ALL_DONE ✅"
'
REMOTE_EOF

echo ""
echo "===== Step 4: 业务验证 ====="
curl -sI http://home.93gushi.com:8088/ | head -3
echo "--- 首页 hash ---"
curl -s http://home.93gushi.com:8088/ | grep -oE 'assets/Index-[^"]*\.js'

echo ""
echo "===== ✅ 部署完成 ====="
echo "回滚: docker exec erp-pc-web ls /tmp/pc-web-new.bak.*"
