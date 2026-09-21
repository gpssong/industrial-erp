#!/bin/bash
# v1.1.53-sysinfo-version: 系统设置页面系统信息版本号根据实际部署同步
# 改了什么: pc-web/package.json version 1.0.0 → 1.1.53-hotfix.1
#          (vite.config.js 把 package.json.version 注入到 __APP_VERSION__,
#           Settings.vue 第 131 行 frontendVersion 读的就是这个常量)
# 后端也改了: backend/.../SystemVersionInitializer.java 默认值 1.0.9 → 1.1.53-hotfix.1
#           application.yml 新增 erp.version: ${ERP_VERSION:1.1.53-hotfix.1}
#           容器启动时 SystemVersionInitializer 自动 upsert 到 sys_config 表
#           (key=SYSTEM_VERSION_INFO) — 只要重启 backend 容器即可生效
# App 也改了: app/package.json + app/src/manifest.json → 重打 APK
#
# 本脚本只处理 pc-web (后端 jar 由 mvn package 单独处理, App 由 build-app.sh)
#
# 部署方式: tar 管道 + sshpass + ssh (避免 rsync/scp 在 Synology DSM 上的兼容坑)
#   - rsync: sshpass 包 ssh 子进程传密码时 PAM "Permission denied" 偶发 (v1.1.53 实测)
#   - scp:   DSM 没装 scp subsystem ("subsystem request failed")
#   - tar over ssh: 最稳, ssh 本身支持 stdout streaming
#
# 用法:
#   cd /Users/tongban/Documents/根据前端开发erp\ 2/erp-system
#   ./deploy-version-bump.sh

set -e

HOST=192.168.0.150
USER=gpssong
PASS=19850225aB
# ⚠️ 关键: pc-web 容器 bind mount 的实际路径是 /tmp/pc-web-new, 不是 /volume3/...
# 历史 docker run 用的是 -v /tmp/pc-web-new:/usr/share/nginx/html:ro (Sep 6 初次部署命令)
# 后续 v1.1.53 部署也延续这个路径 — docker-compose.yml 写的是 /volume3/... 是失效声明.
# 跑前一定要 `docker inspect erp-pc-web | grep -A1 Mounts` 确认当前 bind mount 源.
REMOTE_DIST=/tmp/pc-web-new
REMOTE_STAGE=/tmp/pc-web-staging

echo "===== [1/5] 本地构建 pc-web dist ====="
cd pc-web
npm run build 2>&1 | tail -5
cd ..

echo ""
echo "===== [2/5] 打包 dist (排除 .map, 加速上传) ====="
STAGE=/tmp/pc-web-v1153sysinfo
rm -rf "$STAGE"
mkdir -p "$STAGE"
tar --no-xattrs -cf - -C pc-web/dist --exclude='*.map' . | tar xf - -C "$STAGE"
LOCAL_COUNT=$(find "$STAGE" -type f | wc -l)
LOCAL_INDEX_MD5=$(md5 -q "$STAGE/index.html")
echo "本地 dist 文件数: $LOCAL_COUNT"
echo "本地 index.html md5: $LOCAL_INDEX_MD5"

echo ""
echo "===== [3/5] 上传 home NAS ($HOST) (tar over ssh) ====="
# /tmp gpssong 没写权限, 用 sudo 创建 staging 目录 + chmod 777 让 tar 解压能 utime
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST "
  printf '$PASS\n' | sudo -S -p '' bash -c '
    rm -rf $REMOTE_STAGE
    mkdir -p $REMOTE_STAGE
    chmod 777 $REMOTE_STAGE
  '
"
tar --no-xattrs -cf - -C "$STAGE" . | \
  sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST \
  "cd $REMOTE_STAGE && tar xf - && echo STAGE_OK && find . -type f | wc -l"
echo "✅ dist 已暂存到 $HOST:$REMOTE_STAGE"

echo ""
echo "===== [4/5] 替换线上 dist (原位置 -> staging -> bind mount) ====="
# 注意: dist 目录可能被历史部署(tongban 用户)拥有, gpssong 无写权限.
# 必须 sudo 删除旧文件, 再 sudo 拷贝 staging 的新文件.
# 不能 rm -rf ./* 后再 cp, 因为 dpkg/apt 可能持有 dist inode 句柄 — 用 mv staging 目录到 dist 是更稳的"原子替换":
#   但 dist 已是容器 bind mount 的目标, 不能用 mv 跨 mount, 必须先删内容再 mv 内容
# 简化做法: sudo rm + sudo cp (gpssong 在 administrators 组, sudo 不需要密码? 不,需要 — 走 printf | sudo -S)
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST "
  set -e
  cd '$REMOTE_DIST'
  # 先看是不是我们能写 (上次的 cp 可能留下了 gpssong 拥有的副本)
  ls -la index.html 2>&1 | head -1
"
echo ""
echo "--- sudo 替换 dist 内容 ---"
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST "
  printf '$PASS\n' | sudo -S -p '' bash -c '
    set -e
    cd $REMOTE_DIST
    # 清掉旧文件 (包括 macOS tar 留下的 ._* 扩展属性文件)
    find . -mindepth 1 -delete
    # 从 staging 拷贝新文件 (cp -a 保留权限/时间戳)
    cp -a $REMOTE_STAGE/. .
    echo COPY_OK
    ls | head -5
    echo ---
    md5sum index.html
  '
"
echo "✅ 线上 dist 已替换"

echo ""
echo "===== [5/5] 验证线上版本字符串 + 重启 pc-web 容器 ====="
# Settings.vue 模板会把 __APP_VERSION__ 替换为 "1.1.53-hotfix.1"
# 编译后会出现在某个 chunk 里
HITS=$(sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST \
  "grep -rl '1.1.53-hotfix.1' '$REMOTE_DIST/assets/' 2>/dev/null | head -3")
if [ -z "$HITS" ]; then
  echo "❌ 未在 chunk 中找到版本字符串, 请检查 dist 是否真的覆盖!"
  exit 1
fi
echo "✅ 命中 chunk:"
echo "$HITS"

echo ""
echo "--- 重启 pc-web 容器 (需要 sudo) ---"
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST \
  "printf '$PASS\n' | sudo -S -p '' /usr/local/bin/docker restart erp-pc-web"
sleep 3
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST \
  "printf '$PASS\n' | sudo -S -p '' /usr/local/bin/docker ps --format 'table {{.Names}}\t{{.Status}}' 2>&1 | grep pc-web"

echo ""
echo "--- 业务验证: curl 拉页面 + md5 对照 ---"
LOCAL_MD5=$(md5 -q "$STAGE/index.html")
sleep 2  # 给 nginx open file cache 一点失效时间
SERVED_MD5=$(curl -s "http://$HOST:18080/?nocache=$(date +%s%N)" | md5 -q)
echo "本地 index.html md5: $LOCAL_MD5"
echo "nginx 服务 md5:     $SERVED_MD5"
if [ "$LOCAL_MD5" = "$SERVED_MD5" ]; then
  echo "✅ MATCH — 部署成功"
else
  echo "❌ MISMATCH — nginx 可能还在读旧文件, 检查 bind mount"
  exit 1
fi

echo ""
echo "===== 清理 staging ====="
sshpass -p "$PASS" ssh -o StrictHostKeyChecking=no $USER@$HOST \
  "printf '$PASS\n' | sudo -S -p '' rm -rf $REMOTE_STAGE && echo CLEAN_STAGE_OK"

echo ""
echo "===== 完成 ====="
echo "1. 浏览器强刷 (Cmd+Shift+R) http://home.93gushi.com:8088/"
echo "2. 系统管理 → 系统设置 → 系统信息 → 前端版本 应显示 1.1.53-hotfix.1"
echo ""
echo "===== 后端版本号生效方法 ====="
echo "后端 jar 改动在代码, 还没有重新构建. 你可以选择:"
echo "A) 重打 jar (大动作): cd backend && mvn package -DskipTests"
echo "   然后参考 deploy-home-backend.sh 重启容器 (会自动 upsert 新版本到 sys_config)"
echo "B) 不重打 jar, 直接在线修改 sys_config (临时方案):"
echo "   docker exec -i erp-mysql mysql -uroot -p'\$MYSQL_ROOT_PASSWORD' industrial_erp \\"
echo "     -e \"UPDATE sys_config SET config_value=JSON_SET("
echo "       config_value,'\$.version','1.1.53-hotfix.1') WHERE config_key='SYSTEM_VERSION_INFO'\""
echo ""
echo "===== App APK 重打方法 ====="
echo "cd app && npm run build:app && npx cap sync android && cd android && ./gradlew assembleDebug"
echo "新 APK 在 android/app/build/outputs/apk/debug/app-debug.apk"
echo "App 端的版本号在 manifest.json 已改, 重打后才生效"