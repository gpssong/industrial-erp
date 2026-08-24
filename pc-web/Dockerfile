# v1.1.20+: pc-web 改 bind mount 模式 (P0-3)
#
# 不再 COPY dist 进镜像, 而是由 docker-compose.yml 通过 bind mount 把
# NAS 主机的 pc-web/dist 目录挂载到容器 /usr/share/nginx/html.
#
# 优势:
# - 前端热修复: mac npm run build → 上传 dist 到 NAS → docker restart pc-web
#   (30 秒, 之前需 docker build + force-recreate, 5-10 分钟)
#
# 部署步骤:
# - mac 上 npm run build → File Station 上传 dist 到 /volume3/docker/erp-system/pc-web/dist/
# - NAS 上 docker compose up -d pc-web (会自动重建容器使 bind mount 生效)
#
# 如需回滚到镜像内置: 改回 COPY ./dist 然后 docker compose build --no-cache pc-web
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
# v1.1.11+: USER root (原 USER nginx 在 alpine 上跑 nginx 绑 80 端口被拒)
# 改为 root + 修 /var/cache/nginx /var/run/nginx.pid 权限, 容器是内网服务, 安全可接受
RUN chown -R nginx:nginx /var/cache/nginx /var/run/nginx.pid /var/log/nginx 2>/dev/null || true
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ || exit 1