#!/usr/bin/env bash
# Build/push images (optional) then helm upgrade --install the Virbius stack.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: k8s-deploy.sh --registry REGISTRY [options]

  --registry       Image registry prefix (required unless --skip-build and values already set it)
  --tag            Image tag (default: latest)
  --namespace      Kubernetes namespace (default: virbius)
  --release        Helm release name (default: virbius)
  --values         Extra Helm values file (repeatable)
  --skip-build     Do not build or push images
  --apt-mirror     Forwarded to k8s-build-push.sh
  --timeout        kubectl wait timeout (default: 180s)

Requires docker (unless --skip-build), helm, and kubectl.
Run `docker login` against the registry before building.
The cluster must already have an Ingress Controller.
EOF
}

REGISTRY=""
TAG="latest"
NAMESPACE="virbius"
RELEASE="virbius"
SKIP_BUILD=0
APT_MIRROR=""
TIMEOUT="180s"
VALUES_FILES=()

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
    --namespace)
      NAMESPACE="${2:-}"
      shift 2
      ;;
    --release)
      RELEASE="${2:-}"
      shift 2
      ;;
    --values)
      VALUES_FILES+=("${2:-}")
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --apt-mirror)
      APT_MIRROR="${2:-}"
      shift 2
      ;;
    --timeout)
      TIMEOUT="${2:-}"
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

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "error: required command not found: $1" >&2
    exit 1
  fi
}

need helm
need kubectl
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  need docker
  if [[ -z "$REGISTRY" ]]; then
    echo "error: --registry is required unless --skip-build" >&2
    usage >&2
    exit 1
  fi
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHART="${ROOT}/deploy/helm/virbius"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  BUILD_ARGS=(--registry "$REGISTRY" --tag "$TAG")
  if [[ -n "$APT_MIRROR" ]]; then
    BUILD_ARGS+=(--apt-mirror "$APT_MIRROR")
  fi
  bash "${ROOT}/deploy/scripts/k8s-build-push.sh" "${BUILD_ARGS[@]}"
fi

HELM_ARGS=(
  upgrade --install "$RELEASE" "$CHART"
  --namespace "$NAMESPACE"
  --create-namespace
  --set "global.imageTag=${TAG}"
)
if [[ -n "$REGISTRY" ]]; then
  HELM_ARGS+=(--set "global.imageRegistry=${REGISTRY}")
fi
for vf in "${VALUES_FILES[@]+"${VALUES_FILES[@]}"}"; do
  HELM_ARGS+=(-f "$vf")
done

echo "==> helm ${HELM_ARGS[*]}"
helm "${HELM_ARGS[@]}"

echo "==> waiting for Deployments (timeout ${TIMEOUT})"
kubectl wait --for=condition=Available deployment \
  -l "app.kubernetes.io/instance=${RELEASE}" \
  -n "$NAMESPACE" \
  --timeout="$TIMEOUT"

echo
echo "ok: release ${RELEASE} is available in namespace ${NAMESPACE}"
helm get notes "$RELEASE" -n "$NAMESPACE"
