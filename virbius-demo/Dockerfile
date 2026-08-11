# =============================================================================
# virbiusDemo - multi-stage Dockerfile
#
# Packages the Flask demo together with its two real Rust dependencies:
#   1. virbius_mcp_python  - PyO3 extension (compiled from virbius-core),
#                            drives the OWASP "VirbiusAgent protection" engine.
#   2. virbius-mcp-proxy   - Rust binary, drives the dvla tool-authorization
#                            chain (Agent -> proxy -> engine -> SSE upstream).
#
# IMPORTANT: the build context MUST be the VirbiusAgent repo root (the parent
# of virbius-demo), because the Rust sources live under virbius-core/ etc.
#   docker compose build demo
# =============================================================================

# ---- Stage 1: build Rust deps ----
FROM rust:1.87-slim-bookworm AS rust-build
WORKDIR /build

# Optional build-time HTTP(S) proxy for apt/pip/cargo downloads (defaults to
# empty = direct). Pass via `--build-arg http_proxy=https_proxy=...` or compose
# build args to accelerate downloads behind a proxy/firewall. Empty keeps the
# old behavior (no proxy, and any daemon-inherited proxy vars are cleared).
ARG http_proxy=""
ARG https_proxy=""
ENV HTTP_PROXY="$http_proxy" HTTPS_PROXY="$https_proxy" \
    http_proxy="$http_proxy" https_proxy="$https_proxy" \
    ALL_PROXY="" all_proxy=""

RUN apt-get update && apt-get install -y --no-install-recommends \
    pkg-config libssl-dev g++ python3 python3-pip make \
    && rm -rf /var/lib/apt/lists/* \
    && pip3 install --no-cache-dir --break-system-packages maturin

# Manifests first for dependency caching.
# NOTE: cargo needs the manifest of EVERY workspace member to load the
# workspace, even when we only build virbius-mcp-proxy / virbius-mcp-python.
COPY Cargo.toml Cargo.lock* ./
COPY virbius-core/Cargo.toml virbius-core/
COPY virbius-mcp-python/Cargo.toml virbius-mcp-python/
COPY virbius-mcp-proxy/Cargo.toml virbius-mcp-proxy/
COPY virbius-mcp-node/Cargo.toml virbius-mcp-node/
COPY virbius-kernel/Cargo.toml virbius-kernel/

# Sources (only the crates the demo actually depends on).
COPY virbius-core/src virbius-core/src
COPY virbius-core/include virbius-core/include
COPY virbius-mcp-python/src virbius-mcp-python/src
COPY virbius-mcp-proxy/src virbius-mcp-proxy/src
# The other workspace members are NOT built, but cargo validates their manifest
# (lib path) when loading the workspace, so their sources must be present too.
COPY virbius-mcp-node/src virbius-mcp-node/src
COPY virbius-mcp-node/build.rs virbius-mcp-node/
COPY virbius-kernel/src virbius-kernel/src

# Build the proxy binary.
RUN cargo build --release -p virbius-mcp-proxy

# Build the PyO3 wheel (outputs to /build/target/wheels/).
RUN cd virbius-mcp-python && maturin build --release

# ---- Stage 2: runtime ----
# NOTE: must match the Python version used in the rust-build stage (bookworm = py3.11),
# because the PyO3 wheel is built with cp311 ABI tags and won't install on py3.12.
FROM python:3.11-slim
WORKDIR /app

# Optional build-time proxy (same ARG/ENV as the rust-build stage).
ARG http_proxy=""
ARG https_proxy=""
ENV HTTP_PROXY="$http_proxy" HTTPS_PROXY="$https_proxy" \
    http_proxy="$http_proxy" https_proxy="$https_proxy" \
    ALL_PROXY="" all_proxy=""

# curl for healthchecks/wait loops inside the entrypoint.
# libssl3: the Rust proxy (reqwest default-tls -> native-tls) links OpenSSL at runtime.
RUN apt-get update && apt-get install -y --no-install-recommends curl libssl3 \
    && rm -rf /var/lib/apt/lists/*

# Copy the built Rust artifacts.
COPY --from=rust-build /build/target/wheels/*.whl /tmp/wheels/
COPY --from=rust-build /build/target/release/virbius-mcp-proxy /usr/local/bin/virbius-mcp-proxy

# Python deps (Flask/requests + langchain for the ReAct agent + the PyO3 wheel).
COPY virbius-demo/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir --force-reinstall /tmp/wheels/*.whl \
    && rm -rf /tmp/wheels

# Demo app + data (rules manifest, level data, config, etc.).
COPY virbius-demo/ ./

# Entrypoint: start SSE upstream, then launch the demo app.
COPY virbius-demo/docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENV PORT=8000
EXPOSE 8000

ENTRYPOINT ["docker-entrypoint.sh"]