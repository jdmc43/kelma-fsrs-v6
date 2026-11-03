#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
image="kelma-fsrs-optimizer-oracle:6.5.0"
platform="linux/amd64"
output_dir="${repo_root}/testdata"

mkdir -p "${output_dir}"
docker build --platform "${platform}" --tag "${image}" "${repo_root}/tools/optimizer_oracle"
docker run --rm --platform "${platform}" --volume "${output_dir}:/output" "${image}"
shasum -a 256 "${output_dir}/fsrs-optimizer-6.5.0-vectors.json"
