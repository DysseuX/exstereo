#!/usr/bin/env bash
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail
if (($# == 0)); then
    printf 'Usage: bash scripts/run-limited.sh COMMAND [ARGS...]\n' >&2
    exit 2
fi
for program in systemd-run systemctl flock nice ionice taskset python3; do
    if ! command -v "$program" >/dev/null; then
        printf 'Нужен %s. Запуск без ограничений запрещён. См. RESOURCE_LIMITS.md.\n' "$program" >&2
        exit 1
    fi
done
if ! systemctl --user show-environment >/dev/null 2>&1; then
    printf 'Недоступен systemd user manager. Запуск без лимитов отменён.\n' >&2
    exit 1
fi
cpu_set=$(python3 -c 'import os; print(",".join(map(str, sorted(os.sched_getaffinity(0))[:3])))')
export CMAKE_BUILD_PARALLEL_LEVEL=1 MAKEFLAGS=-j1
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 NUMEXPR_NUM_THREADS=1
ulimit -c 0
# One lock for all copies of this project on this user's computer.
lock_directory="${XDG_RUNTIME_DIR:-/tmp}"
exec flock --nonblock --conflict-exit-code 75 "$lock_directory/spatial-audio-heavy-$UID.lock" \
    systemd-run --user --scope --quiet --unit="spatial-audio-job-$$" \
    -p CPUQuota=210% -p MemoryHigh=2500M -p MemoryMax=3G \
    -p MemorySwapMax=0 -p TasksMax=128 \
    taskset -c "$cpu_set" nice -n 15 ionice -c 2 -n 7 "$@"
