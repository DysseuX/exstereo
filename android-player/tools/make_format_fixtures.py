#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

"""Small synthetic format regression files. Run through scripts/run-limited.sh."""
from pathlib import Path
import json
import subprocess

root = Path(__file__).resolve().parents[1] / "app/src/test/resources/audio-formats"
root.mkdir(parents=True, exist_ok=True)
manifest = []
layouts = {1: "mono", 2: "stereo", 3: "3.0", 4: "quad", 5: "5.0", 6: "5.1", 7: "6.1", 8: "7.1"}


def make(name, codec, rate=48000, channels=2, extra=()):
    tones = "|".join(f"0.1*sin(2*PI*{80 if channels >= 6 and c == 3 else 330+110*c}*t)" for c in range(channels))
    source = f"aevalsrc={tones}:s={rate}:d=0.3:c={layouts[channels]}"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-filter_threads", "1", "-f", "lavfi", "-i", source, "-threads", "1", "-c:a", codec,
        *extra, str(root / name)], check=True)
    manifest.append(dict(name=name, rate=48000 if codec == "libopus" else rate, channels=channels))


for rate in (44100, 48000):
    make(f"aac-lc-{rate}.m4a", "aac", rate, extra=("-b:a", "160k"))
for codec, ext in (("alac", "m4a"), ("flac", "flac")):
    for rate, fmt in ((44100, "s16p" if codec == "alac" else "s16"), (96000, "s32p" if codec == "alac" else "s32"),
                      (192000, "s32p" if codec == "alac" else "s32")):
        make(f"{codec}-{rate}.{ext}", codec, rate, extra=("-sample_fmt", fmt))
for channels in range(1, 9):
    make(f"opus-{channels}ch.opus", "libopus", channels=channels,
         extra=("-mapping_family", "0" if channels <= 2 else "1", "-b:a", f"{64*channels}k"))
for frame in (2.5, 5, 10, 20, 40, 60, 120):
    make(f"opus-frame-{frame}-cbr.opus", "libopus", extra=("-frame_duration", str(frame), "-vbr", "off", "-b:a", "96k"))
make("opus-voip-8k.opus", "libopus", 8000, 1, ("-application", "voip", "-b:a", "12k"))
make("opus-constrained-vbr.opus", "libopus", extra=("-vbr", "constrained", "-b:a", "96k"))
for ext in ("ogg", "oga", "webm", "mka", "m4a"):
    make(f"opus-stereo.{ext}", "libopus", extra=("-f", "mp4") if ext == "m4a" else ())
make("ac3-6ch.ac3", "ac3", channels=6, extra=("-b:a", "448k"))
make("eac3-6ch.eac3", "eac3", channels=6, extra=("-b:a", "768k"))
make("eac3-6ch.m4a", "eac3", channels=6, extra=("-b:a", "768k", "-f", "mp4"))
(root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Generated {len(manifest)} synthetic fixtures; {sum(p.stat().st_size for p in root.iterdir())} bytes")
