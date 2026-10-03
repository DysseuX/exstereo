#!/usr/bin/env bash
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
export PYTHONPATH="$project_dir/src"
# Preserve the caller's GPU visibility. Explicit CUDA is verified by the
# worker; Auto remains CPU under the conservative resource policy.
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 NUMEXPR_NUM_THREADS=1
exec bash "$project_dir/../scripts/run-limited.sh" \
    "${SPATIAL_BASE_PYTHON:-python3}" "$project_dir/bootstrap.py" launch "$@"
