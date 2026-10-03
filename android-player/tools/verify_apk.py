#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

"""Check native dependency packaging and bundled HRTF bytes without Android."""
import hashlib
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def verify(path: str) -> None:
    system_libraries = {
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
        "libz.so", "libOpenSLES.so", "libjnigraphics.so",
    }
    with zipfile.ZipFile(path) as apk, tempfile.TemporaryDirectory() as temporary:
        names = set(apk.namelist())
        assert apk.testzip() is None, "APK contains a damaged ZIP entry"
        for abi in ("arm64-v8a", "x86_64"):
            prefix = f"lib/{abi}/"
            for library in ("libspatial_audio.so", "libphonon.so"):
                assert prefix + library in names, f"Missing {abi}/{library}"
            libraries = {name for name in names if name.startswith(prefix) and name.endswith(".so")}
            for name in sorted(libraries):
                local = Path(temporary) / Path(name).name
                local.write_bytes(apk.read(name))
                dynamic = subprocess.check_output(["readelf", "-dW", str(local)], text=True)
                for dependency in re.findall(r"Shared library: \[([^\]]+)\]", dynamic):
                    assert dependency in system_libraries or prefix + dependency in names, (
                        f"Missing dependency: {name} -> {dependency}"
                    )
            print(f"PASS {abi}: native libraries and their dependencies")
        source_directory = Path(__file__).resolve().parents[1] / "app/src/main/assets/hrtf"
        for source in sorted(source_directory.glob("*.sofa")):
            bundled = apk.read(f"assets/hrtf/{source.name}")
            assert hashlib.sha256(bundled).digest() == hashlib.sha256(source.read_bytes()).digest(), source.name
            print(f"PASS HRTF bytes: {source.name}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/verify_apk.py path/to/player.apk")
    verify(sys.argv[1])
