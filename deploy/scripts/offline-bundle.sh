#!/usr/bin/env bash
# 离线交付包构建：构建 5 个组件镜像（可选 demo/ollama）→ docker save → tar.gz。
# 产物：
#   dist/offline/virbius-images-<TAG>.tar.gz   镜像包（现场 docker load）
#   dist/offline/virbius-onprem-<TAG>.tar.gz   部署资产（deploy/onprem/* + 校验和）
# 用法：
#   ./deploy/scripts/offline-bundle.sh [--tag v1.0.0] [--with-demo] [--with-ollama]
#                                      [--apt-mirror cn] [--crates-mirror cn]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

TAG="v1.0.0"
REGISTRY="hb.grainmind.cn/library"
WITH_DEMO=0
WITH_OLLAMA=0
OLLAMA_IMAGE="ollama/ollama:0.11.10"
BUILD_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) TAG="${2:-}"; shift 2 ;;
    --registry) REGISTRY="${2:-}"; shift 2 ;;
    --with-demo) WITH_DEMO=1; shift ;;
    --with-ollama) WITH_OLLAMA=1; shift ;;
    --apt-mirror) BUILD_ARGS+=(--build-arg "APT_MIRROR=${2:-}"); shift 2 ;;
    --crates-mirror) BUILD_ARGS+=(--build-arg "CRATES_MIRROR=${2:-}"); shift 2 ;;
    -h|--help) sed -n '1,10p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

COMPONENTS=(virbius-engine virbius-control virbius-auth virbius-mcp-proxy)
IMAGES=()
for c in "${COMPONENTS[@]}"; do
  echo "==> build ${REGISTRY}/${c}:${TAG}"
  docker build "${BUILD_ARGS[@]}" --target "$c" -t "${REGISTRY}/${c}:${TAG}" .
  IMAGES+=("${REGISTRY}/${c}:${TAG}")
done

if [ "$WITH_DEMO" = "1" ]; then
  echo "==> build ${REGISTRY}/virbius-demo:${TAG}"
  docker build "${BUILD_ARGS[@]}" -f virbius-demo/Dockerfile -t "${REGISTRY}/virbius-demo:${TAG}" .
  IMAGES+=("${REGISTRY}/virbius-demo:${TAG}")
fi

if [ "$WITH_OLLAMA" = "1" ]; then
  echo "==> pull ${OLLAMA_IMAGE} (模型选项A需要)"
  docker pull "$OLLAMA_IMAGE"
  IMAGES+=("$OLLAMA_IMAGE")
fi

OUT="dist/offline"
mkdir -p "$OUT"
IMG_TAR="$OUT/virbius-images-${TAG}.tar"
echo "==> docker save -> ${IMG_TAR}"
docker save -o "$IMG_TAR" "${IMAGES[@]}"
gzip -f "$IMG_TAR"

echo "==> 打包部署资产 -> ${OUT}/virbius-onprem-${TAG}.tar.gz"
tar -czf "${OUT}/virbius-onprem-${TAG}.tar.gz" -C deploy onprem

( cd "$OUT" && sha256sum "virbius-images-${TAG}.tar.gz" "virbius-onprem-${TAG}.tar.gz" > "SHA256SUMS-${TAG}.txt" )

echo
echo "完成。交付物："
ls -lh "$OUT" | grep -E "$TAG|SHA256"
echo
echo "现场侧：docker load -i virbius-images-${TAG}.tar.gz && tar -xzf virbius-onprem-${TAG}.tar.gz"
echo "GGUF 模型（选项A）另行下载：见 deploy/onprem/README.zh.md §4"
