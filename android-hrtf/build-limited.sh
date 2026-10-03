#!/usr/bin/env bash
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
if (($# == 0)); then set -- :app:assembleDebug; fi
# Use the project's mandatory Android entry point and its shared resource lock.
cd -- "$project_dir/../android-player"
exec bash build-limited.sh --project-dir "$project_dir" "$@"
