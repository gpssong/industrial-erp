# Stage 1: Build
# 使用腾讯云镜像源, 绕开飞牛 docker.fnnas.com 的 401 问题
FROM mirror.ccs.tencentyun.com/library/node:20-alpine AS builder
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

# Stage 2: Serve with nginx
FROM mirror.ccs.tencentyun.com/library/nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ || exit 1
