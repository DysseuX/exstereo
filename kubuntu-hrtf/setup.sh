#!/usr/bin/env bash
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
export CUDA_VISIBLE_DEVICES=""
exec bash "$project_dir/../scripts/run-limited.sh" \
    "${SPATIAL_BASE_PYTHON:-python3}" "$project_dir/bootstrap.py" setup "$@"
