# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────────────────
# Qoder 审计日志收集 Server —— 多阶段构建镜像
#
#   构建阶段：maven:3.9-eclipse-temurin-21（源码自包含，宿主机无需 JDK/Maven）
#   运行阶段：eclipse-temurin:21-jre（Ubuntu 基底，仅含 JRE + curl）
#
# 容器内路径约定：
#   /opt/qoder-log-server/app.jar    Fat jar（入口）
#   /data/spool                      spool 数据卷（named volume 挂载点）
#   /etc/qoder-log-server/           配置目录（api-keys.yml 只读挂载）
#
# 所有应用配置经 Spring 标准环境变量覆盖注入（见 docker-compose.yml），
# 镜像本身零凭证、零环境差异。
# ─────────────────────────────────────────────────────────────────────────────

# ── 构建阶段：Maven + JDK 21 ────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom.xml 预热依赖：pom 不变时命中层缓存，日常迭代免重新下载依赖
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# 再拷源码打包（产物固定改名 app.jar，隔离版本号变化）
COPY src ./src
RUN mvn -B -q clean package -DskipTests \
    && mv target/qoder-log-server-*.jar app.jar

# ── 运行阶段：JRE 21（Ubuntu 基底）─────────────────────────────────────────
FROM eclipse-temurin:21-jre

# curl 仅供 HEALTHCHECK 使用；--no-install-recommends 控制体积并清理 apt 缓存
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 固定 uid/gid=10001 的非 root 用户：宿主机挂载目录按 10001 对齐属主即可读写
RUN groupadd --gid 10001 qoderlog \
    && useradd --uid 10001 --gid qoderlog --create-home --shell /usr/sbin/nologin qoderlog

# 时区：manifest cron 与审计分区均按北京时间（代码内已硬编码 Asia/Shanghai，
# 这里同步设置 TZ 保证日志时间戳与宿主机运维观察一致）
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:+UseG1GC -Xms1g -Xmx3g"

# 镜像内置 spool 目录属主（named volume 首次挂载时按此初始化权限）
RUN mkdir -p /data/spool /etc/qoder-log-server \
    && chown -R qoderlog:qoderlog /data/spool /etc/qoder-log-server

COPY --from=build --chown=qoderlog:qoderlog /build/app.jar /opt/qoder-log-server/app.jar

USER qoderlog
WORKDIR /opt/qoder-log-server
EXPOSE 8080
VOLUME ["/data/spool"]

# 健康检查：无鉴权的 /api/health（启动宽限 60s 覆盖 JVM 预热）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

# 显式声明停机信号；sh -c 中 exec 前缀让 java 成为 PID 1，
# 保证 docker stop 的 SIGTERM 直达 JVM 触发优雅停机（120s 泄洪预算）
STOPSIGNAL SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/qoder-log-server/app.jar"]
