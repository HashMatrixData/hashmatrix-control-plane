# syntax=docker/dockerfile:1
# 多阶段构建：① Maven 构建 fat-jar ② 瘦运行镜像。
# 构建需能访问制品仓（GitHub Packages / 内网私服）解析 hashmatrix-* 制品——见 libs-java/README。
# 凭据经构建期 BuildKit secret（id=maven_settings → Maven settings.xml）注入，**不入库 / 不落镜像层**（红线）；
# 本地直接 `mvn` 构建时无需该 secret（用开发者自身 ~/.m2/settings.xml）。CI 注入见 .github/workflows/publish-image.yml。

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先拷 pom 预热依赖缓存（源码改动不必重拉依赖）。
COPY pom.xml ./
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -q -B -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 非 root 运行。
RUN useradd --system --uid 10001 --no-create-home appuser
USER 10001

COPY --from=build /workspace/target/control-plane-*.jar /app/control-plane.jar

# 端口基线：应用 8081、管理/actuator 9081（management.server.port 独立）。
EXPOSE 8081 9081
# 容器健康检查打到管理端口的 actuator liveness 探针（9081）。
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://localhost:9081/actuator/health/liveness || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/control-plane.jar"]
