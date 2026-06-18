# 多阶段构建：① Maven 构建 fat-jar ② 瘦运行镜像。
# 构建需能访问制品仓（GitHub Packages / 内网私服）解析 hashmatrix-* 制品——见 libs-java/README。
# 凭据经构建期 secret / settings.xml 注入，**不入库**（红线）。

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先拷 pom 预热依赖缓存（源码改动不必重拉依赖）。
COPY pom.xml ./
RUN mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 非 root 运行。
RUN useradd --system --uid 10001 --no-create-home appuser
USER 10001

COPY --from=build /workspace/target/control-plane-*.jar /app/control-plane.jar

EXPOSE 8080
# 容器健康检查打到 actuator liveness 探针。
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://localhost:8080/actuator/health/liveness || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/control-plane.jar"]
