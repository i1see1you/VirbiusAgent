# =============================================================================
#  VirbiusAgent — Multi-stage Dockerfile
#
#  Build targets:
#    virbius-engine   — Java Spring Boot security engine (port 8082)
#    virbius-control  — Java Spring Boot control plane  (port 8080)
#    virbius-auth     — Java Spring Boot operator auth  (port 8083)
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
COPY virbius-auth/pom.xml virbius-auth/
COPY virbius-compiler/pom.xml virbius-compiler/

# Download dependencies (offline later)
RUN mvn dependency:go-offline -B -q || true

# Copy source and build all Java modules
COPY virbius-groovy-l3/ virbius-groovy-l3/
COPY virbius-policy/ virbius-policy/
COPY virbius-engine/ virbius-engine/
COPY virbius-control/ virbius-control/
COPY virbius-auth/ virbius-auth/
COPY virbius-compiler/ virbius-compiler/

# Clear proxy env vars inherited from the Docker daemon config (a daemon-injected
# proxy is typically unreachable inside the build container — npm dies with
# ECONNREFUSED, and its crash exits 0, surfacing later as "vue-tsc: not found").
# BuildKit injects the client proxy into RUN steps and overrides ENV, so the
# network-calling RUN below also clears them per-command.
ENV HTTP_PROXY=""
ENV HTTPS_PROXY=""
ENV http_proxy=""
ENV https_proxy=""
ENV ALL_PROXY=""
ENV all_proxy=""

# npm registry for the console UI build; npmmirror default matches the
# MAVEN_MIRROR=aliyun convention (registry.npmjs.org is unreachable from CN build
# hosts). Override with --build-arg NPM_REGISTRY= to use the default registry.
ARG NPM_REGISTRY="https://registry.npmmirror.com"
RUN if [ -n "$NPM_REGISTRY" ]; then \
      printf 'registry=%s\n' "$NPM_REGISTRY" > virbius-control/frontend/.npmrc; \
    fi

RUN HTTP_PROXY= HTTPS_PROXY= http_proxy= https_proxy= ALL_PROXY= all_proxy= \
    mvn package -DskipTests -B -q

# Guard the runtime stages' wildcard COPY: each module must produce exactly one
# main jar (a 0 or ≥2 match would fail the COPY, or silently package the wrong jar).
RUN set -e; for m in virbius-engine virbius-control virbius-auth; do \
      n=$(ls /build/$m/target/$m-*.jar 2>/dev/null | grep -vc '\.original$' || true); \
      [ "$n" = "1" ] || { echo "expected exactly 1 $m jar, found $n"; exit 1; }; \
    done

# ── Stage 2: Rust build (workspace) ─────────────────────────────────────────
FROM rust:1.94-slim-bookworm AS rust-build
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
RUN unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy; \
    if [ "$APT_MIRROR" = "cn" ]; then \
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

# BuildKit injects the client proxy into RUN steps and overrides the ENV clears
# above, so clear them per-command for the network-calling build step.
RUN HTTP_PROXY= HTTPS_PROXY= http_proxy= https_proxy= ALL_PROXY= all_proxy= \
    cargo build --release -p virbius-mcp-proxy

# ── Stage 3: virbius-engine runtime ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS virbius-engine
WORKDIR /app

ARG APT_MIRROR=""
RUN unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy; \
    if [ "$APT_MIRROR" = "cn" ]; then \
      sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r virbius && useradd -r -g virbius -d /app -s /sbin/nologin virbius

COPY --from=java-build /build/virbius-engine/target/virbius-engine-*.jar app.jar

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
RUN unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy; \
    if [ "$APT_MIRROR" = "cn" ]; then \
      sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r virbius && useradd -r -g virbius -d /app -s /sbin/nologin virbius

COPY --from=java-build /build/virbius-control/target/virbius-control-*.jar app.jar

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

# ── Stage 5: virbius-auth runtime ───────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS virbius-auth
WORKDIR /app

ARG APT_MIRROR=""
RUN unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy; \
    if [ "$APT_MIRROR" = "cn" ]; then \
      sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' \
        /etc/apt/sources.list 2>/dev/null || true; \
    fi && \
    apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r virbius && useradd -r -g virbius -d /app -s /sbin/nologin virbius

COPY --from=java-build /build/virbius-auth/target/virbius-auth-*.jar app.jar

RUN mkdir -p /data /var/log/virbius && chown -R virbius:virbius /app /data /var/log/virbius

USER virbius
EXPOSE 8083

ENV SERVER_PORT=8083
ENV SPRING_PROFILES_ACTIVE=prod
ENV VIRBIUS_AUTH_DATA_DIR=/data
ENV VIRBIUS_LOG_DIR=/var/log/virbius

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD curl -sf http://localhost:8083/api/v1/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

# ── Stage 6: virbius-mcp-proxy runtime ──────────────────────────────────────
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
RUN unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy; \
    if [ "$APT_MIRROR" = "cn" ]; then \
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

COPY --from=rust-build /build/target/release/virbius-mcp-proxy /usr/local/bin/virbius-mcp-proxy

USER root
EXPOSE 9090

ENV VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090
ENV VIRBIUS_LOG_DIR=/var/log/virbius

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD curl -sf http://localhost:9090/health || exit 1

ENTRYPOINT ["virbius-mcp-proxy"]
