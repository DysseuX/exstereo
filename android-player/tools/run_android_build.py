#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

"""Sequential native prerequisites + Gradle, within one resource-limited scope."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
# Standalone companion projects use the same mandatory limiter and wrapper,
# but must not build the paused player's native libraries or change its output.
arguments = sys.argv[1:]
for option in ("--project-dir", "-p"):
    if option in arguments:
        index = arguments.index(option)
        companion = Path(arguments[index + 1]).resolve()
        if companion == root.parent / "android-hrtf":
            from shutil import copyfile
            copyfile(root / "local.properties", companion / "local.properties")
            os.chdir(companion)
            os.execvp("bash", ["bash", str(companion / "gradlew"), "--no-daemon", "--no-parallel",
                               "--max-workers=1", "--console=plain", *arguments])
sdk = os.environ.get("ANDROID_HOME")
if not sdk:
    for line in (root / "local.properties").read_text().splitlines():
        if line.startswith("sdk.dir="):
            sdk = line.partition("=")[2].replace("\\:", ":").replace("\\ ", " ").replace("\\\\", "\\")
            break
if not sdk:
    raise SystemExit("Android SDK path missing; run scripts/setup-android.py first")
subprocess.run([sys.executable, str(root / "tools/build_filter_deps.py"),
                "--sdk", sdk, "arm64-v8a", "x86_64"], check=True, cwd=root)
if any("unittest" in arg.lower() or arg.split(":")[-1] == "test" for arg in sys.argv[1:]):
    # Format regression tests exercise the real JNI decoder on this host.
    subprocess.run([sys.executable, str(root / "tools/build_filter_deps.py"),
                    "--sdk", sdk, "linux-x64"], check=True, cwd=root)
    cmake = str(Path(sdk) / "cmake/3.22.1/bin/cmake")
    host = str(root / "build/native-widening")
    subprocess.run([cmake, "-S", str(root / "app/src/main/cpp"), "-B", host,
                    "-DCMAKE_BUILD_TYPE=Release"], check=True, cwd=root)
    subprocess.run([cmake, "--build", host, "--parallel", "1", "--target", "ffmpegJNI", "spatial_audio"], check=True, cwd=root)
os.chdir(root)
os.execvp("bash", ["bash", "./gradlew", "--no-daemon", "--no-parallel", "--max-workers=1",
                   "--console=plain", *sys.argv[1:]])
