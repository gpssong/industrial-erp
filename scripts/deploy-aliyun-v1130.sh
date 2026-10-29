#!/bin/bash
set -e
TS=20261027_1440
P=/opt/erp-system
DOCKER=$(command -v docker || echo /usr/bin/docker)

# 1) 备份 jar + pc-web + .env
$DOCKER cp erp-backend:/opt/app/app.jar $P/backup/app.jar.bak.$TS
tar czf $P/backup/pc-web.bak.$TS.tgz -C $P/pc-web .
cp $P/.env $P/backup/.env.bak.$TS

# 2) 替换 jar, 固化 1.1.130 镜像, 切 ERP_VERSION 并重建
$DOCKER cp /tmp/app-$TS.jar erp-backend:/opt/app/app.jar
rm -f /tmp/app-$TS.jar
$DOCKER commit erp-backend erp-backend:1.1.130 >/dev/null
sed -i 's/^ERP_VERSION=1.1.129$/ERP_VERSION=1.1.130/' $P/.env
cd $P
$DOCKER compose up -d erp-backend 2>&1 | tail -3

# 3) pc-web 原地替换 (bind mount inode)
mkdir -p /tmp/dist-new
tar xzf /tmp/pc-dist-$TS.tgz -C /tmp/dist-new
find $P/pc-web -mindepth 1 -delete
tar cf - -C /tmp/dist-new . | tar xf - -C $P/pc-web
rm -rf /tmp/dist-new /tmp/pc-dist-$TS.tgz

ls -lh $P/backup/app.jar.bak.$TS $P/backup/pc-web.bak.$TS.tgz
md5sum $P/pc-web/index.html
grep -n ERP_VERSION $P/.env
