#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

"""Locate the local SDK; never download tools or accept licenses implicitly."""
import argparse
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "android-player"
REQUIRED = (
    "platforms/android-36/android.jar",
    "build-tools/36.0.0/aapt2",
    "ndk/28.2.13676358/build/cmake/android.toolchain.cmake",
    "cmake/3.22.1/bin/cmake",
    "platform-tools/adb",
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, help="Путь Android SDK на этом компьютере")
    parser.add_argument("--check", action="store_true", help="Только проверка, без записи файлов")
    args = parser.parse_args()
    candidates = [args.sdk]
    candidates += [Path(value) for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME") if (value := os.environ.get(name))]
    # The default locations are relative to this user's home, not the old computer.
    candidates += [Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk"]
    properties = PROJECT / "local.properties"
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                candidates.append(Path(line[8:].replace("\\:", ":").replace("\\\\", "\\")))
    if args.sdk is not None:
        candidates = [args.sdk]
    sdk = next((item.expanduser().resolve() for item in candidates if item and item.expanduser().is_dir()), None)
    if sdk is None:
        print("Android SDK не найден. Установите компоненты из BUILDING.md и задайте --sdk.", file=sys.stderr)
        return 1
    missing = [name for name in REQUIRED if not (sdk / name).is_file()]
    for name in missing:
        print(f"Нужен компонент: {sdk / name}", file=sys.stderr)
    if not shutil.which("java"):
        print("JDK не найден в PATH. Нужен JDK 17 или новее (проверялся JDK 25).", file=sys.stderr)
        missing.append("java")
    if missing:
        return 1
    text = "sdk.dir=" + str(sdk).replace("\\", "\\\\").replace(":", "\\:") + "\n"
    if args.check:
        if not properties.is_file() or properties.read_text() != text:
            print("Нужен локальный путь SDK. Выполните python3 scripts/setup-android.py --sdk '<путь SDK>'.", file=sys.stderr)
            return 1
    else:
        properties.write_text(text)
    print(f"Android SDK готов: {sdk}")
    print(f"Проект: {PROJECT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
