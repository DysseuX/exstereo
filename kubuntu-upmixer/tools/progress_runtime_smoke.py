# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Offline real-Demucs smoke; run under scripts/run-limited.sh.

Uses existing htdemucs weights, a temporary one-second FLAC, and a fresh
stem cache. Explicitly requires unavailable CUDA to exercise fail-closed
behaviour; this is not a successful GPU inference test.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import tempfile

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from spatial_upmixer.pipeline import JobConfig, probe, run_job
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, required=True)
    args = parser.parse_args()
    models = args.models.resolve(strict=True)
    events = []

    def progress(event):
        if not events or events[-1].stage != event.stage or event.important:
            print(event.stage, event.message, "DEVICE:", event.device,
                  "DEMUCS:", event.demucs, "ETA:", event.eta_seconds, flush=True)
        events.append(event)

    with tempfile.TemporaryDirectory(prefix="spatial-progress-smoke-") as folder:
        work = Path(folder)
        cache = work / "cache"
        cache.mkdir()
        (cache / "models").symlink_to(models, target_is_directory=True)
        time = np.arange(48000) / 48000
        source = work / "fixture.flac"
        sf.write(source, np.column_stack((.1 * np.sin(2 * np.pi * 440 * time),
                                        .1 * np.sin(2 * np.pi * 554 * time))), 48000)
        output = work / "fixture.opus"
        common = dict(input_path=source, output_path=output, cache_dir=cache,
                      model="htdemucs", output_mode="stereo")
        try:
            run_job(JobConfig(**common, device="cuda"), progress)
        except RuntimeError as error:
            assert "Перехода на CPU не было" in str(error), str(error)
            assert not output.exists()
            assert not any("вычисление подтверждено" in (e.demucs or "") for e in events)
            print("PASS: unavailable CUDA fails explicitly, no CPU fallback or output", flush=True)
        else:
            raise AssertionError("This check requires unavailable CUDA; GPU became available")

        events.clear()
        run_job(JobConfig(**common, device="cpu"), progress)
        assert any("CPU: вычисление подтверждено" in (e.demucs or "") for e in events)
        assert any(e.stage == "separate" and 0 < (e.stage_fraction or 0) < 1 for e in events)
        assert events[-1].stage == "done"
        stream = probe(output, threading.Event())["streams"][0]
        assert stream["channels"] == 2 and stream["codec_name"] == "opus"
        manifests = list((cache / "stems").glob("*/complete.json"))
        assert len(manifests) == 1
        execution = json.loads(manifests[0].read_text())["execution"]
        assert execution["device"] == "cpu" and all(n > 0 for n in execution["verified_forwards"])
        print("PASS: real CPU Demucs → stereo Opus, intermediate progress and verified forwards:",
              execution, flush=True)

        # Reuse those actual stems even with CUDA selected; the UI must say
        # that no neural processing occurred in this second export.
        events.clear()
        run_job(JobConfig(**{**common, "output_path": work / "cached.opus"}, device="cuda"), progress)
        assert any(e.stage == "separate_skip" and "CUDA не используется" in e.demucs for e in events)
        assert not any("вычисление подтверждено" in (e.demucs or "") for e in events)
        print("PASS: actual cache hit never claims CUDA inference", flush=True)


if __name__ == "__main__":
    main()
