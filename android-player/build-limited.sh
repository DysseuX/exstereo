#!/usr/bin/env bash
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail
cd -- "$(dirname -- "$0")"

# Prepare this computer's SDK path before entering the bounded build.
python3 ../scripts/setup-android.py --check
if (($# == 0)); then
    set -- :app:assembleFossDebug
fi
exec bash ../scripts/run-limited.sh python3 tools/run_android_build.py "$@"
