# Stage 1: 直接使用宿主机已构建的 dist (跳过 npm install / build 阶段)
# 原因: 群晖访问 npm registry 不稳定, 容器内 npm install 经常断连.
# 部署步骤: 在 mac 上 vite build 后, File Station 上传 dist/ 到 NAS pc-web/dist/,
# 然后本 Dockerfile 直接 COPY 进 nginx 镜像.
# 如需重新 build:  mac 上 npm run build, 上传 dist 覆盖, 再 docker compose up -d --build pc-web
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10
COPY ./dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
# 安全加固: 以非 root 用户运行 nginx
USER nginx
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ || exit 1
