# Stage 1: 直接使用宿主机已构建的 dist (跳过 npm install / build 阶段)
# 原因: 群晖访问 npm registry 不稳定, 容器内 npm install 经常断连.
# 部署步骤: 在 mac 上 vite build 后, File Station 上传 dist/ 到 NAS pc-web/dist/,
# 然后本 Dockerfile 直接 COPY 进 nginx 镜像.
# 如需重新 build:  mac 上 npm run build, 上传 dist 覆盖, 再 docker compose up -d --build pc-web
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
COPY ./dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
# v1.1.11+: USER root (原 USER nginx 在 alpine 上跑 nginx 绑 80 端口被拒 — alpine nginx 用户无 cap_net_bind)
# 改为 root + 修 /var/cache/nginx /var/run/nginx.pid 权限, 容器是内网服务, 安全可接受
RUN chown -R nginx:nginx /var/cache/nginx /usr/share/nginx/html /var/run/nginx.pid /var/log/nginx 2>/dev/null || true
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ || exit 1
