#!/bin/bash
set -e
TS=20261027_1440
D=/vol2/erp-system
BK=$D/backup
DOCKER=/usr/bin/docker
mkdir -p $BK

# 1) 备份 jar / dist / compose
$DOCKER cp erp-backend-failover:/opt/app/app.jar $BK/app.jar.bak.$TS
tar czf $BK/dist-new.bak.$TS.tgz -C $D/pc-web/dist-new .
cp $D/docker-compose.failover.yml $BK/docker-compose.failover.yml.bak.$TS

# 2) jar → 容器 + 固化镜像
$DOCKER cp /home/gpssong/erp-1130.jar erp-backend-failover:/opt/app/app.jar
$DOCKER commit erp-backend-failover erp-system-backend:1.1.130 >/dev/null
sed -i 's#image: erp-system-backend:1.1.129#image: erp-system-backend:1.1.130#' $D/docker-compose.failover.yml
sed -i 's#ERP_VERSION: 1.1.129#ERP_VERSION: 1.1.130#' $D/docker-compose.failover.yml

# 3) dist-new 原地替换 (bind mount inode)
mkdir -p /tmp/dist-new
tar xzf /home/gpssong/pcweb-1130.tgz -C /tmp/dist-new
find $D/pc-web/dist-new -mindepth 1 -delete
tar cf - -C /tmp/dist-new . | tar xf - -C $D/pc-web/dist-new
rm -rf /tmp/dist-new /home/gpssong/pcweb-1130.tgz

# 4) 重启
cd $D
$DOCKER compose -f docker-compose.failover.yml --profile failover up -d erp-backend-failover 2>&1 | tail -3
$DOCKER restart erp-pc-web-failover >/dev/null

ls -lh $BK/app.jar.bak.$TS $BK/dist-new.bak.$TS.tgz
md5sum $D/pc-web/dist-new/index.html
