# 单阶段: 用宿主机已编译的 jar (避免 mvn 容器内重编; NAS 部署的 src 不一定同步)
# 1. 先在 mac 本地 mvn package, 推到 NAS 覆盖 industrial-erp-${APP_VERSION}.jar
# 2. docker build 时 COPY 这个 jar 进镜像
# 3. docker compose up 自动拉取新镜像 (基于 sha256 digest)
FROM eclipse-temurin:17-jre AS builder
WORKDIR /opt/app
COPY industrial-erp-*.jar app.jar

# 安全加固: 使用非 root 用户运行
RUN groupadd -r erp && useradd -r -g erp erp
RUN chown -R erp:erp /opt/app
USER erp

EXPOSE 8080
# 数据备份功能依赖 mysqldump / mysql 客户端 (基础镜像不含)
# 持久化安装: 镜像构建时一次安装, 容器内随时可用
RUN apt-get update \
 && apt-get install -y --no-install-recommends default-mysql-client=1.1.1ubuntu2 \
 && rm -rf /var/lib/apt/lists/*
# Docker Compose 环境已设置 JAVA_OPTS (容器内运行)
# 本地运行时可自行设置: export JAVA_OPTS="-Xms256m -Xmx768m ..."
ENV SPRING_PROFILES_ACTIVE=prod
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/api/auth/captcha || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
