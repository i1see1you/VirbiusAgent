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
FROM rust:1.80-slim-bookworm AS rust-build
WORKDIR /build

# Install system deps for native libs
RUN apt-get update && apt-get install -y --no-install-recommends \
    pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

# Copy manifests for dependency cache
COPY Cargo.toml Cargo.lock* ./
COPY virbius-core/Cargo.toml virbius-core/
COPY virbius-kernel/Cargo.toml virbius-kernel/
COPY virbius-mcp-proxy/Cargo.toml virbius-mcp-proxy/

# Create dummy main for dependency resolution
RUN mkdir -p virbius-core/src virbius-kernel/src virbius-mcp-proxy/src \
    && echo 'fn main() {}' | tee virbius-core/src/main.rs \
        virbius-kernel/src/main.rs virbius-mcp-proxy/src/main.rs
RUN cargo build --release -p virbius-mcp-proxy 2>/dev/null || true
RUN rm -rf virbius-core/src virbius-kernel/src virbius-mcp-proxy/src

# Copy actual source and build
COPY virbius-core/src virbius-core/src
COPY virbius-kernel/src virbius-kernel/src
COPY virbius-mcp-proxy/src virbius-mcp-proxy/src
COPY virbius-core/include virbius-core/include
COPY virbius-core/Cargo.toml virbius-core/Cargo.toml

RUN cargo build --release -p virbius-mcp-proxy

# ── Stage 3: virbius-engine runtime ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS virbius-engine
WORKDIR /app

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

RUN groupadd -r virbius && useradd -r -g virbius -d /app -s /sbin/nologin virbius

COPY --from=rust-build /build/target/release/virbius-mcp-proxy /usr/local/bin/

RUN mkdir -p /var/log/virbius && chown -R virbius:virbius /app /var/log/virbius

USER virbius
EXPOSE 9090

ENV VIRBIUS_TRANSPORT=sse
ENV VIRBIUS_LOG_DIR=/var/log/virbius

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD curl -sf http://localhost:9090/health || exit 1

ENTRYPOINT ["virbius-mcp-proxy"]
