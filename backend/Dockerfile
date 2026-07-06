# 单阶段: 用宿主机已编译的 jar (避免 mvn 容器内重编; NAS 部署的 src 不一定同步)
# 1. 先在 mac 本地 mvn package, 推到 NAS 覆盖 industrial-erp-1.0.4.jar
# 2. docker build 时 COPY 这个 jar 进镜像
FROM eclipse-temurin:17-jre
WORKDIR /opt/app
COPY industrial-erp-1.0.4.jar app.jar
EXPOSE 8080
# 数据备份功能依赖 mysqldump / mysql 客户端 (基础镜像不含)
# 持久化安装: 镜像构建时一次安装, 容器内随时可用
RUN apt-get update \
 && apt-get install -y --no-install-recommends default-mysql-client \
 && rm -rf /var/lib/apt/lists/*
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=GMT+8"
ENV SPRING_PROFILES_ACTIVE=prod
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/api/auth/captcha || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
