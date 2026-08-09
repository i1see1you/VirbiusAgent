# =============================================================================
#  VirbiusAgent — Multi-stage Dockerfile
#
#  Build targets:
#    virbius-engine   — Java Spring Boot security engine (port 8082)
#    virbius-control  — Java Spring Boot control plane  (port 8080)
#    virbius-mcp-proxy — Rust MCP proxy server          (port 9090)
#
#  Usage:
#    docker compose build
#    docker compose up
# =============================================================================

# ── Stage 1: Java build (Maven reactor) ─────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS java-build
WORKDIR /build

# Use Aliyun Maven mirror for fast/sane builds in CN. Override with --build-arg
# MAVEN_MIRROR=central; selection below (mirroring the APT/CRATES switch pattern).
ARG MAVEN_MIRROR="aliyun"
RUN if [ "$MAVEN_MIRROR" = "aliyun" ]; then \
      mkdir -p /root/.m2 && \
      printf '%s\n' \
        '<settings>' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>aliyun</id>' \
        '      <mirrorOf>central</mirrorOf>' \
        '      <url>https://maven.aliyun.com/repository/public</url>' \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' > /root/.m2/settings.xml; \
    fi

# Copy POM files first for dependency cache
COPY pom.xml .
COPY virbius-groovy-l3/pom.xml virbius-groovy-l3/
COPY virbius-policy/pom.xml virbius-policy/
COPY virbius-engine/pom.xml virbius-engine/
COPY virbius-control/pom.xml virbius-control/
COPY virbius-compiler/pom.xml virbius-compiler/

# Download dependencies (offline later)
RUN mvn dependency:go-offline -B -q || true

# Copy source and build all Java modules
COPY virbius-groovy-l3/ virbius-groovy-l3/
COPY virbius-policy/ virbius-policy/
COPY virbius-engine/ virbius-engine/
COPY virbius-control/ virbius-control/
COPY virbius-compiler/ virbius-compiler/

RUN mvn package -DskipTests -B -q

# ── Stage 2: Rust build (workspace) ─────────────────────────────────────────
FROM rust:1.87-slim-bookworm AS rust-build
WORKDIR /build

# Clear proxy env vars inherited from Docker daemon (not reachable inside container)
ENV HTTP_PROXY=""
ENV HTTPS_PROXY=""
ENV http_proxy=""
ENV https_proxy=""
ENV ALL_PROXY=""
ENV all_proxy=""

# Configure crates.io mirror (optional, set CRATES_MIRROR=cn for China users)
ARG CRATES_MIRROR=""
ARG APT_MIRROR=""
RUN if [ "$CRATES_MIRROR" = "cn" ]; then \
      mkdir -p /usr/local/cargo && \
      printf '%s\n' \
        '[source.crates-io]' \
        'replace-with = "rsproxy-sparse"' \
        '' \
        '[source.rsproxy]' \
        'registry = "https://rsproxy.cn/crates.io-index"' \
        '' \
        '[source.rsproxy-sparse]' \
        'registry = "sparse+https://rsproxy.cn/index/"' \
        '' \
        '[net]' \
        'git-fetch-with-cli = true' \
        > /usr/local/cargo/config.toml; \
    fi

# Install system deps for native libs (apt mirror switch when APT_MIRROR=cn)
RUN if [ "$APT_MIRROR" = "cn" ]; then \
      sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || true; \
      sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && apt-get install -y --no-install-recommends \
    pkg-config libssl-dev g++ python3 make && rm -rf /var/lib/apt/lists/*

# Copy all source and build
COPY Cargo.toml Cargo.lock* ./
COPY virbius-core/Cargo.toml virbius-core/
COPY virbius-kernel/Cargo.toml virbius-kernel/
COPY virbius-mcp-proxy/Cargo.toml virbius-mcp-proxy/
COPY virbius-mcp-node/Cargo.toml virbius-mcp-node/
COPY virbius-mcp-python/Cargo.toml virbius-mcp-python/
COPY virbius-core/src virbius-core/src
COPY virbius-kernel/src virbius-kernel/src
COPY virbius-mcp-proxy/src virbius-mcp-proxy/src
COPY virbius-mcp-node/src virbius-mcp-node/src
COPY virbius-mcp-python/src virbius-mcp-python/src
COPY virbius-core/include virbius-core/include

RUN cargo build --release -p virbius-mcp-proxy

# ── Stage 3: virbius-engine runtime ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS virbius-engine
WORKDIR /app

ARG APT_MIRROR=""
RUN if [ "$APT_MIRROR" = "cn" ]; then \
      sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r virbius && useradd -r -g virbius -d /app -s /sbin/nologin virbius

COPY --from=java-build /build/virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar app.jar

RUN mkdir -p /data /var/log/virbius && chown -R virbius:virbius /app /data /var/log/virbius

USER virbius
EXPOSE 8082

ENV SERVER_PORT=8082
ENV SPRING_PROFILES_ACTIVE=dev
ENV VIRBIUS_DATA_DIR=/data
ENV VIRBIUS_LOG_DIR=/var/log/virbius

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD curl -sf http://localhost:8082/admin/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

# ── Stage 4: virbius-control runtime ────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS virbius-control
WORKDIR /app

ARG APT_MIRROR=""
RUN if [ "$APT_MIRROR" = "cn" ]; then \
      sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r virbius && useradd -r -g virbius -d /app -s /sbin/nologin virbius

COPY --from=java-build /build/virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar app.jar

RUN mkdir -p /data /var/log/virbius && chown -R virbius:virbius /app /data /var/log/virbius

USER virbius
EXPOSE 8080

ENV SERVER_PORT=8080
ENV SPRING_PROFILES_ACTIVE=dev
ENV VIRBIUS_DATA_DIR=/data
ENV VIRBIUS_LOG_DIR=/var/log/virbius

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD curl -sf http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

# ── Stage 5: virbius-mcp-proxy runtime ──────────────────────────────────────
FROM debian:bookworm-slim AS virbius-mcp-proxy
WORKDIR /app

# Clear proxy env vars inherited from Docker daemon (not reachable inside container)
ENV HTTP_PROXY=""
ENV HTTPS_PROXY=""
ENV http_proxy=""
ENV https_proxy=""
ENV ALL_PROXY=""
ENV all_proxy=""

ARG APT_MIRROR=""
RUN if [ "$APT_MIRROR" = "cn" ]; then \
      sed -i 's|deb.debian.org|mirrors.aliyun.com|g; s|security.debian.org|mirrors.aliyun.com|g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || true; \
      sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && apt-get install -y --no-install-recommends curl python3 nodejs \
    && rm -rf /var/lib/apt/lists/*

# Run as root: the sandbox engine (gVisor runsc) creates its user/pid namespaces
# from the container's root namespace; as a non-root user runsc requires
# newuidmap/newgidmap which cannot reach the target /proc inside a container,
# so gVisor isolation only works when the proxy runs as root here. The real
# isolation boundary for untrusted code is the runsc sandbox itself.
RUN mkdir -p /var/log/virbius

USER root
EXPOSE 9090

ENV VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090
ENV VIRBIUS_LOG_DIR=/var/log/virbius

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD curl -sf http://localhost:9090/health || exit 1

ENTRYPOINT ["virbius-mcp-proxy"]
