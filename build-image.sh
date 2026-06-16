#!/usr/bin/env bash
# ============================================================
# Research Gateway — build the Docker image and export it as a
# portable archive you can copy to another server and load with:
#   docker load -i research-gateway-<tag>.tar.gz
#   docker run -p 8080:8080 research-gateway:<tag>
#
# IMPORTANT: the image is built for a CPU architecture. If the
# target server has a different CPU than this machine you MUST
# build for the target arch, otherwise you get on startup:
#   exec /usr/bin/sh: exec format error
#
# Set the target platform with PLATFORM (default: this host).
#   PLATFORM=linux/arm64 ./build-image.sh      # for ARM servers (e.g. NVIDIA DGX, Apple, Graviton)
#   PLATFORM=linux/amd64 ./build-image.sh      # for x86 servers
# ============================================================
set -euo pipefail

# Run from the project root (the directory this script lives in).
cd "$(dirname "$0")"

IMAGE_NAME="${IMAGE_NAME:-research-gateway}"
TAG="${1:-latest}"
OUTPUT="${IMAGE_NAME}-${TAG}.tar.gz"

if [ -n "${PLATFORM:-}" ]; then
  echo ">> Building ${IMAGE_NAME}:${TAG} for platform ${PLATFORM} ..."
  # buildx can cross-build (uses QEMU emulation when arch differs from host).
  docker buildx build --platform "${PLATFORM}" -t "${IMAGE_NAME}:${TAG}" --load .
else
  echo ">> Building ${IMAGE_NAME}:${TAG} for the native host platform ..."
  docker build -t "${IMAGE_NAME}:${TAG}" .
fi

echo ">> Exporting image to ${OUTPUT} ..."
docker save "${IMAGE_NAME}:${TAG}" | gzip > "${OUTPUT}"

echo ""
echo ">> Done. Created: ${OUTPUT}"
echo ""
echo "   Copy it to the target server, then run there:"
echo "     docker load -i ${OUTPUT}"
echo "     docker run -d -p 8080:8080 ${IMAGE_NAME}:${TAG}"
