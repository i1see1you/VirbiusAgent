#!/usr/bin/env bash
# Build and push the four Virbius images used by the Helm chart.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: k8s-build-push.sh --registry REGISTRY [--tag TAG] [--apt-mirror cn]

  --registry     Image registry prefix, e.g. registry.example.com/virbius
                 Images: {registry}/virbius-{engine,control,mcp-proxy,demo}:{tag}
  --tag          Image tag (default: latest)
  --apt-mirror   Passed to Dockerfiles as APT_MIRROR (e.g. cn)

Requires docker. Run `docker login` against the registry first.
EOF
}

REGISTRY=""
TAG="latest"
APT_MIRROR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --registry)
      REGISTRY="${2:-}"
      shift 2
      ;;
    --tag)
      TAG="${2:-}"
      shift 2
      ;;
    --apt-mirror)
      APT_MIRROR="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$REGISTRY" ]]; then
  echo "error: --registry is required" >&2
  usage >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

BUILD_ARGS=()
if [[ -n "$APT_MIRROR" ]]; then
  BUILD_ARGS+=(--build-arg "APT_MIRROR=${APT_MIRROR}")
fi

echo "==> building ${REGISTRY}/virbius-engine:${TAG}"
docker build "${BUILD_ARGS[@]}" --target virbius-engine \
  -t "${REGISTRY}/virbius-engine:${TAG}" .

echo "==> building ${REGISTRY}/virbius-control:${TAG}"
docker build "${BUILD_ARGS[@]}" --target virbius-control \
  -t "${REGISTRY}/virbius-control:${TAG}" .

echo "==> building ${REGISTRY}/virbius-mcp-proxy:${TAG}"
docker build "${BUILD_ARGS[@]}" --target virbius-mcp-proxy \
  -t "${REGISTRY}/virbius-mcp-proxy:${TAG}" .

echo "==> building ${REGISTRY}/virbius-demo:${TAG}"
docker build "${BUILD_ARGS[@]}" -f virbius-demo/Dockerfile \
  -t "${REGISTRY}/virbius-demo:${TAG}" .

echo "==> pushing images"
docker push "${REGISTRY}/virbius-engine:${TAG}"
docker push "${REGISTRY}/virbius-control:${TAG}"
docker push "${REGISTRY}/virbius-mcp-proxy:${TAG}"
docker push "${REGISTRY}/virbius-demo:${TAG}"

echo "ok: pushed four images under ${REGISTRY} tag ${TAG}"
