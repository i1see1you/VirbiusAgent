# virbius-mcp-proxy

Security-aware MCP proxy (stdio/SSE) with a built-in tool-call pipeline:
License + allowlist check, engine adjudication, multi-upstream routing, audit
and decision tracing.

Part of [VirbiusAgent](https://github.com/i1see1you/VirbiusAgent) — an open-source
four-tier **Edge–Gateway–Kernel–Cloud** security platform for AI Agents.

## Build prerequisites

The proxy depends on `rdkafka` with the `sasl` feature, which compiles
librdkafka from source with SSL/SASL support. OpenSSL and Cyrus SASL
development files are therefore required at build time:

- **Debian/Ubuntu**: `sudo apt-get install libssl-dev libsasl2-dev pkg-config`
- **macOS (Homebrew)**: `brew install openssl@3 cyrus-sasl pkg-config`

OpenSSL 3.x is the validated default. On macOS, pin the tree explicitly
before `cargo install`:

```bash
export OPENSSL_DIR="$(brew --prefix openssl@3)"
export CFLAGS="-I$OPENSSL_DIR/include"
export LDFLAGS="-L$OPENSSL_DIR/lib"
export PKG_CONFIG_PATH="$OPENSSL_DIR/lib/pkgconfig:$(brew --prefix cyrus-sasl)/lib/pkgconfig"
```

Without this, `openssl-sys` picks Homebrew's `openssl@4` while librdkafka's
configure cannot see the keg-only tree on its own — the two crates end up on
different trees. Typical failures: `openssl/ssl.h` not found on a bare
install, or undefined ENGINE symbols at link time when @3 headers get
compiled against an @4 library (the mix; a pure @4 build compiles librdkafka's
ENGINE block out via `OPENSSL_NO_ENGINE` and links fine).

OpenSSL 4.x is experimental: librdkafka compiles its ENGINE block out when
`OPENSSL_NO_ENGINE` is defined, but other APIs removed in 4.0 are not yet
validated.

At runtime the binary links `libssl`/`libcrypto` 3.x and `libsasl2`
(Debian runtime packages: `libssl3`, `libsasl2-2`).

Building from the repository? `scripts/lib/openssl.sh` resolves and exports
all of the above automatically.

## Install

```bash
cargo install virbius-mcp-proxy
```

## Run

```bash
VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090 virbius-mcp-proxy
```

## Links

- Repository: <https://github.com/i1see1you/VirbiusAgent>
- Documentation: <https://github.com/i1see1you/VirbiusAgent#readme>
- Website: <https://www.virbius.cn>
- License: MIT
