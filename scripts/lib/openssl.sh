#!/usr/bin/env bash
# Canonical OpenSSL selection for librdkafka builds.
#
# rdkafka-sys compiles librdkafka with --enable-ssl (the `sasl` feature implies
# `ssl`), so an OpenSSL development tree is a hard build prerequisite. Two
# crates consume it and they must agree on exactly one tree:
#
#   openssl-sys  picks its own root. On macOS it prefers
#                /opt/homebrew/opt/openssl@4 over @3 (build/find_normal.rs lists
#                "openssl@4" first) and returns before pkg-config is consulted.
#                OPENSSL_DIR is the one variable that overrides this.
#   rdkafka-sys compiles librdkafka against whatever its mklove configure
#                finds, through up to three channels, in this priority:
#                1. pkg-config — preferred, returns on the first hit. On OSX
#                   mklove *appends* the unversioned alias
#                   /opt/homebrew/opt/openssl/lib/pkgconfig, which can point at
#                   a different major than the selected root.
#                2. CFLAGS/LDFLAGS compile checks.
#                3. A source-tarball installer, only reachable with mklove's
#                   --install-deps (rdkafka-sys never passes it).
#                openssl-sys 0.9.117 publishes cargo:include but no
#                cargo:root, so DEP_OPENSSL_ROOT never reaches rdkafka-sys and
#                the channels must be steered explicitly: OPENSSL_DIR for
#                openssl-sys, CFLAGS/LDFLAGS derived from the same root for the
#                compile checks, and a prepended $root/lib/pkgconfig for the
#                pkg-config channel.
#
# Hand-written OpenSSL flags from callers are what mix "@3 headers with @4
# library" — surfacing as undefined symbols at link time, or worse, linking
# silently against the wrong major. This library resolves one root, validates
# that its headers and library agree, exports it together with flags derived
# from that same root, and can assert the resulting binary links a single
# major.
#
# Supported majors: 3 (default) and 4 (experimental). Homebrew's openssl@4
# defines OPENSSL_NO_ENGINE, which makes librdkafka compile its ENGINE block
# out, so OpenSSL 4 is expected to build but is not the validated default.
#
# Usage:
#   source scripts/lib/openssl.sh
#   virbius_openssl_select                      # resolve + export, fail loudly
#   ...
#   virbius_openssl_assert_binary target/debug/virbius-mcp-proxy

virbius_openssl_die() {
  printf 'virbius-openssl: %s\n' "$*" >&2
  return 1
}

virbius_openssl_usable() {
  [[ -n "${1:-}" && -f "$1/include/openssl/ssl.h" ]]
}

virbius_openssl_header_major() {
  local root=${1:-} f major hex
  for f in "$root/include/openssl/configuration.h" "$root/include/openssl/opensslv.h"; do
    [[ -f "$f" ]] || continue
    major=$(sed -nE 's/^#[[:space:]]*define[[:space:]]+OPENSSL_VERSION_MAJOR[[:space:]]+([0-9]+).*/\1/p' "$f" | head -n1)
    if [[ -n "$major" ]]; then
      printf '%s' "$major"
      return 0
    fi
  done
  f="$root/include/openssl/opensslv.h"
  [[ -f "$f" ]] || return 0
  hex=$(sed -nE 's/^#[[:space:]]*define[[:space:]]+OPENSSL_VERSION_NUMBER[[:space:]]+0x([0-9a-fA-F]+).*/\1/p' "$f" | head -n1)
  [[ -n "$hex" ]] || return 0
  printf '%s' "$(( 16#$hex >> 28 ))"
}

virbius_openssl_lib_major() {
  local root=${1:-} f major
  for f in "$root"/lib*/libcrypto.*.dylib "$root"/lib*/libcrypto.so.* \
           "$root"/lib*/*/libcrypto.so.*; do
    [[ -e "$f" ]] || continue
    case "$f" in
      *.dylib) major=${f##*/libcrypto.}; major=${major%.dylib} ;;
      *)       major=${f##*/libcrypto.so.} ;;
    esac
    [[ "$major" =~ ^[0-9]+$ ]] || continue
    printf '%s' "$major"
    return 0
  done
}

# Prints the resolved major or '?' when the tree only ships headers.
virbius_openssl_validate() {
  local root=${1:-} header lib
  virbius_openssl_usable "$root" \
    || { virbius_openssl_die "root '$root' has no include/openssl/ssl.h"; return 1; }
  header=$(virbius_openssl_header_major "$root")
  lib=$(virbius_openssl_lib_major "$root")
  if [[ -n "$header" && -n "$lib" && "$header" != "$lib" ]]; then
    virbius_openssl_die "root '$root' is inconsistent: headers major $header, library major $lib"
    return 1
  fi
  printf '%s' "${header:-?}"
}

# Prints the selected prefix, or an empty string when nothing usable is found.
# Returns non-zero only for an explicitly broken configuration.
virbius_openssl_resolve() {
  local root="${OPENSSL_DIR:-}" target_upper want other os
  if [[ -z "$root" && -n "${OPENSSL_INCLUDE_DIR:-}" && -f "$OPENSSL_INCLUDE_DIR/openssl/ssl.h" ]]; then
    root=$(cd "$OPENSSL_INCLUDE_DIR/.." 2>/dev/null && pwd)
  fi
  if [[ -z "$root" && -n "${TARGET:-}" ]]; then
    # bash 3.2 (the /bin/bash baseline on macOS) has no ${var^^}; transliterate
    # with tr and read the variable through indirect expansion instead of eval.
    target_upper=$(printf '%s' "$TARGET" | tr '[:lower:]-' '[:upper:]_')
    local env_var="${target_upper}_OPENSSL_DIR"
    root=${!env_var:-}
  fi
  if [[ -n "$root" ]]; then
    virbius_openssl_validate "$root" >/dev/null || return 1
    printf '%s' "$root"
    return 0
  fi

  want="${VIRBIUS_OPENSSL_MAJOR:-3}"
  case "$want" in
    3|4) ;;
    *) virbius_openssl_die "VIRBIUS_OPENSSL_MAJOR must be 3 or 4 (got '$want')"; return 1 ;;
  esac
  other=$([[ "$want" == 3 ]] && printf 4 || printf 3)
  os=$(uname -s)

  if [[ "$os" == Darwin ]] && command -v brew >/dev/null 2>&1; then
    local major
    for major in "$want" "$other"; do
      root=$(brew --prefix "openssl@$major" 2>/dev/null || true)
      if virbius_openssl_usable "$root"; then
        if [[ "$major" != "$want" ]]; then
          printf 'virbius-openssl: openssl@%s not found, using openssl@%s\n' "$want" "$major" >&2
        fi
        virbius_openssl_validate "$root" >/dev/null || return 1
        printf '%s' "$root"
        return 0
      fi
    done
  fi

  if [[ "$os" == Linux ]] && command -v pkg-config >/dev/null 2>&1 \
     && pkg-config --exists openssl 2>/dev/null; then
    root=$(pkg-config --variable=prefix openssl 2>/dev/null || true)
    [[ -n "$root" ]] || root=/usr
    if virbius_openssl_usable "$root"; then
      virbius_openssl_validate "$root" >/dev/null || return 1
      printf '%s' "$root"
      return 0
    fi
  fi

  printf ''
}

virbius_openssl_export() {
  local root=${1:-} inc lib sasl
  [[ -n "$root" ]] || return 0
  export OPENSSL_DIR="$root"
  export VIRBIUS_OPENSSL_ROOT="$root"
  VIRBIUS_OPENSSL_MAJOR_RESOLVED=$(virbius_openssl_validate "$root") || return 1
  export VIRBIUS_OPENSSL_MAJOR_RESOLVED

  # openssl-sys 0.9.117 publishes cargo:include but NOT cargo:root, so
  # rdkafka-sys cannot derive -I/-L from DEP_OPENSSL_ROOT and librdkafka's
  # configure fails to find <openssl/ssl.h> in a keg-only Homebrew tree.
  # Both flags are generated from this same $root (never hand-written), so
  # they cannot introduce a @3/@4 mix; virbius_openssl_conflicts still guards
  # caller-supplied paths.
  inc="-I$root/include"
  lib="-L$root/lib"
  case " ${CFLAGS:-} " in *" $inc "*) ;; *) export CFLAGS="${CFLAGS:+$CFLAGS }$inc" ;; esac
  case " ${LDFLAGS:-} " in *" $lib "*) ;; *) export LDFLAGS="${LDFLAGS:+$LDFLAGS }$lib" ;; esac

  # librdkafka's mklove configure prefers the pkg-config channel and returns
  # on the first hit (mkl_pkg_config_check), and on OSX it *appends* the
  # unversioned Homebrew alias /opt/homebrew/opt/openssl/lib/pkgconfig —
  # which can point at a different major than $root. pkg-config searches
  # PKG_CONFIG_PATH in order, so prepending $root's own pkgconfig dir keeps
  # that channel on the same tree too. Without this, any machine with
  # pkg-config installed builds librdkafka against the alias while
  # openssl-sys follows OPENSSL_DIR: the "@4 headers, @3 library" mix.
  if [[ -d "$root/lib/pkgconfig" ]]; then
    case ":${PKG_CONFIG_PATH:-}:" in
      *":$root/lib/pkgconfig:"*) ;;
      *) export PKG_CONFIG_PATH="$root/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}" ;;
    esac
  fi

  # cyrus-sasl is keg-only too; rdkafka-sys sasl2-sys probes it via pkg-config.
  if [[ "$(uname -s)" == Darwin ]] && command -v brew >/dev/null 2>&1; then
    sasl="$(brew --prefix cyrus-sasl 2>/dev/null || true)"
    if [[ -n "$sasl" && -d "$sasl/lib/pkgconfig" ]]; then
      case ":${PKG_CONFIG_PATH:-}:" in
        *":$sasl/lib/pkgconfig:"*) ;;
        *) export PKG_CONFIG_PATH="${PKG_CONFIG_PATH:+$PKG_CONFIG_PATH:}$sasl/lib/pkgconfig" ;;
      esac
    fi
  fi

  virbius_openssl_sync_native_cache
}

# One-shot selection for call sites that run cargo directly (CI steps, e2e
# scripts): resolve, export, and fail loudly. A bare
# `virbius_openssl_export "$(virbius_openssl_resolve)"` silently no-ops when
# resolution comes back empty, which is how an unselected build slips through.
virbius_openssl_select() {
  local root
  root="$(virbius_openssl_resolve)" || return 1
  if [[ -z "$root" ]]; then
    virbius_openssl_die "no usable OpenSSL tree found"
    virbius_openssl_hint >&2
    return 1
  fi
  virbius_openssl_export "$root"
}

# librdkafka is a C library compiled by rdkafka-sys, whose build script does
# not declare rerun-if-env-changed for OPENSSL_DIR/CFLAGS. Cargo therefore
# reuses the cached archive after the tree changes, which is exactly how you
# get "@4-built archive linked against libcrypto.3". Drop the cached native
# crates whenever the OpenSSL major flips (or when the marker is missing but
# archives from before this guard already exist).
virbius_openssl_sync_native_cache() {
  local major=${VIRBIUS_OPENSSL_MAJOR_RESOLVED:-?} marker td prev
  [[ "$major" != "?" ]] || return 1
  td="${CARGO_TARGET_DIR:-target}"
  [[ -d "$td" ]] || return 0
  marker="$td/.virbius-openssl-major"
  prev=""
  [[ -f "$marker" ]] && prev="$(cat "$marker" 2>/dev/null || true)"
  if [[ "$prev" != "$major" ]]; then
    if [[ -n "$prev" ]] || compgen -G "$td/*/build/rdkafka-sys-*" >/dev/null; then
      rm -rf "$td"/*/build/rdkafka-sys-* "$td"/*/build/openssl-sys-* 2>/dev/null || true
    fi
    printf '%s' "$major" >"$marker" 2>/dev/null || true
  fi
}

# Fails when the caller still carries OpenSSL flags for a different tree. That
# combination is exactly the "@3 headers, @4 library" mix.
virbius_openssl_conflicts() {
  local root=${1:-} real flag path parent
  [[ -n "$root" ]] || return 0
  real=$(cd "$root" 2>/dev/null && pwd -P || printf '%s' "$root")
  for flag in ${CFLAGS:-} ${LDFLAGS:-}; do
    case "$flag" in
      -I*openssl*|-L*openssl*) ;;
      *) continue ;;
    esac
    path=${flag#-I}
    path=${path#-L}
    parent=$(cd "$path/.." 2>/dev/null && pwd -P || true)
    [[ -n "$parent" ]] || continue
    if [[ "$parent" != "$real" ]]; then
      virbius_openssl_die "conflicting flag '$flag' (tree $parent) while OPENSSL_DIR is $real"
      return 1
    fi
  done
  return 0
}

# Fails when the binary links more than one libcrypto major, or a major that
# differs from the tree we exported. Statically linked binaries (vendored)
# report nothing and pass.
virbius_openssl_assert_binary() {
  local bin=${1:-} majors
  [[ -f "$bin" ]] || { virbius_openssl_die "binary not found: $bin"; return 1; }
  if [[ "$(uname -s)" == Darwin ]]; then
    majors=$(otool -L "$bin" 2>/dev/null \
      | sed -nE 's/.*libcrypto\.([0-9]+)\.dylib.*/\1/p' | sort -u | tr '\n' ' ')
  else
    majors=$(ldd "$bin" 2>/dev/null \
      | sed -nE 's/.*libcrypto\.so\.([0-9]+).*/\1/p' | sort -u | tr '\n' ' ')
  fi
  majors=${majors% }
  case "$majors" in
    "") return 0 ;;
    *" "*) virbius_openssl_die "binary links multiple libcrypto majors: $majors"; return 1 ;;
  esac
  if [[ -n "${VIRBIUS_OPENSSL_MAJOR_RESOLVED:-}" \
        && "${VIRBIUS_OPENSSL_MAJOR_RESOLVED}" != "?" \
        && "$majors" != "${VIRBIUS_OPENSSL_MAJOR_RESOLVED}" ]]; then
    virbius_openssl_die "binary links libcrypto major $majors but ${VIRBIUS_OPENSSL_ROOT:-OPENSSL_DIR} provides major ${VIRBIUS_OPENSSL_MAJOR_RESOLVED}"
    return 1
  fi
}

virbius_openssl_hint() {
  case "$(uname -s)" in
    Darwin)
      printf '  brew install openssl@3 cyrus-sasl\n'
      printf '  # experimental: brew install openssl@4 && VIRBIUS_OPENSSL_MAJOR=4\n'
      ;;
    Linux)
      printf '  sudo apt-get install libssl-dev pkg-config   # or dnf/yum/pacman equivalents\n'
      ;;
    *)
      printf '  set OPENSSL_DIR to a prefix containing include/openssl and lib\n'
      ;;
  esac
}
