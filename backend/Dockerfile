# Multi-stage build for Spring Boot ERP backend
# 使用腾讯云镜像源, 绕开飞牛 docker.fnnas.com 的 401 问题
FROM mirror.ccs.tencentyun.com/library/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests clean package

FROM mirror.ccs.tencentyun.com/library/eclipse-temurin:17-jre
WORKDIR /opt/app
COPY --from=builder /app/target/industrial-erp.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=GMT+8"
ENV SPRING_PROFILES_ACTIVE=prod
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/api/auth/captcha || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
