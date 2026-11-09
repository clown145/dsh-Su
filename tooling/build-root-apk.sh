#!/usr/bin/env bash

#   tooling/build-root-apk.sh [build-apk options]
#   DSH_UPDATE_REPO=owner/repo selects a personal fork's release feed.
#   DSH_UNSIGNED_RELEASE=1 builds an unsigned APK for inspection or offline signing.

set -euo pipefail

pre=()
post=()
in_post=0
for arg in "$@"; do
  if ((in_post)); then
    post+=("$arg")
  elif [[ $arg == -- ]]; then
    in_post=1
  else
    pre+=("$arg")
  fi
done

unsigned=()
case "${DSH_UNSIGNED_RELEASE:-0}" in
  1) unsigned=(--android-project-arg=dsh.unsignedRelease=true) ;;
  0) ;;
  *) echo "build-root-apk: DSH_UNSIGNED_RELEASE must be 0 or 1" >&2; exit 64 ;;
esac

for arg in "${pre[@]}"; do
  if [[ ${DSH_UNSIGNED_RELEASE:-0} == 1 && ( $arg == --debug || $arg == --profile ) ]]; then
    echo "build-root-apk: unsigned delivery requires a release build" >&2
    exit 64
  fi
  if [[ $arg == --beta ]]; then
    echo "build-root-apk: dsh-Su builds use this fork's release feed; do not pass --beta" >&2
    exit 64
  fi
done

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
prepare_runtime=1
for arg in "${pre[@]}"; do
  case "$arg" in
    --dry-run | -h | --help) prepare_runtime=0 ;;
  esac
done
if ((prepare_runtime)); then
  "$ROOT/tooling/fetch-runc-assets.sh"
fi
exec "$ROOT/tooling/build-apk.sh" \
  "${pre[@]}" \
  -- \
  --android-project-arg=dsh.rootRuntime=true \
  "${unsigned[@]}" \
  "--dart-define=DSH_UPDATE_REPO=${DSH_UPDATE_REPO:-${GITHUB_REPOSITORY:-clown145/dsh-Su}}" \
  "--dart-define=DSH_UPDATE_MIRROR=" \
  "--dart-define=DSH_UPDATE_FEED=" \
  "--dart-define=DSH_UPDATE_MIRRORS=" \
  "--dart-define=DSH_ROOT_RUNTIME=true" \
  "${post[@]}"
