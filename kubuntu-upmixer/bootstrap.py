# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Portable bootstrap; shell entry points run this inside the resource limiter."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


PROJECT = Path(__file__).resolve().parent


def environment_dir() -> Path:
    data = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
    if not data.is_absolute():
        raise ValueError("XDG_DATA_HOME должен содержать абсолютный путь.")
    version = f"{sys.version_info.major}.{sys.version_info.minor}"
    return data / "3d-audio" / "venvs" / f"upmixer-py{version}"


def runtime_python() -> Path:
    override = os.environ.get("SPATIAL_PYTHON")
    if override:
        candidate = shutil.which(override)
        if not candidate:
            raise ValueError(f"SPATIAL_PYTHON не найден: {override}")
        # Keep the venv executable path: resolving its symlink loses the venv.
        return Path(candidate).absolute()
    return environment_dir() / "bin/python"


CHECK = r'''
import importlib.metadata
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys

failed = False
print("Python:", sys.executable)
for name in ("numpy", "scipy", "soundfile", "tkinter", "demucs", "torch", "spatial_upmixer"):
    found = importlib.util.find_spec(name) is not None
    optional = name in ("demucs", "torch")
    if not found and not optional:
        failed = True
    try:
        version = importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        version = ""
    if name == "spatial_upmixer" and found:
        # launch.sh uses adjacent sources, which can be newer than editable
        # install metadata on this computer. Importing __init__ is lightweight.
        import spatial_upmixer
        version = spatial_upmixer.__version__
        print("Исходники:", spatial_upmixer.__file__)
    print(f"{name}: {'OK' if found else 'нет'} {version}".strip())
for tool in ("ffmpeg", "ffprobe"):
    executable = shutil.which(tool)
    print(f"{tool}: {executable or 'нет'}")
    failed |= executable is None
ffmpeg = shutil.which("ffmpeg")
if ffmpeg:
    result = subprocess.run([ffmpeg, "-hide_banner", "-encoders"],
                            capture_output=True, text=True, timeout=15)
    opus = result.returncode == 0 and any(
        line.split()[1:2] == ["libopus"] for line in result.stdout.splitlines())
    print("libopus:", "OK" if opus else "нет")
    failed |= not opus
print("Demucs/Torch нужны для разделения; готовые дорожки работают без них.")
print("Проверены наличие пакетов и FFmpeg; модель и графическое окно не запускались.")
raise SystemExit(1 if failed else 0)
'''


def setup(args: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Установка локального окружения Spatial Upmixer")
    parser.add_argument("--core", action="store_true", help="Только сборка из готовых дорожек, без Demucs/Torch")
    options = parser.parse_args(args)
    if sys.version_info < (3, 10):
        raise ValueError("Нужен Python 3.10 или новее.")
    target = environment_dir()
    python = target / "bin/python"
    if (target / "pyvenv.cfg").exists():
        config = (target / "pyvenv.cfg").read_text(encoding="utf-8").lower()
        if "include-system-site-packages = true" in config:
            raise ValueError(f"Окружение {target} использует системные пакеты. Создайте чистое окружение отдельно.")
    elif target.exists() and any(target.iterdir()):
        raise ValueError(f"Каталог {target} занят и не является venv.")
    else:
        subprocess.run([sys.executable, "-m", "venv", str(target)], check=True)
    subprocess.run([str(python), "-m", "pip", "install", "--upgrade", "pip"], check=True)
    if not options.core:
        # Install CPU wheels first so Demucs does not pull CUDA distributions.
        subprocess.run([str(python), "-m", "pip", "install", "--prefer-binary",
                        "--index-url", "https://download.pytorch.org/whl/cpu", "torch>=2.1"], check=True)
    package = str(PROJECT) + ("" if options.core else "[demucs]")
    subprocess.run([str(python), "-m", "pip", "install", "--prefer-binary", "--editable", package], check=True)
    print(f"Готово: {target}\nПроверка: bash launch.sh --check", flush=True)
    return 0


def launch(args: list[str]) -> int:
    if args == ["--help"]:
        print("bash launch.sh                  — окно\n"
              "bash launch.sh --check          — проверка окружения без запуска модели/окна\n"
              "bash launch.sh --cli INPUT OUTPUT [OPTIONS] — командная строка\n"
              "SPATIAL_PYTHON=/путь/к/venv/bin/python — использовать существующее окружение")
        return 0
    python = runtime_python()
    if not python.is_file():
        raise ValueError(f"Окружение не найдено: {python}\nСначала выполните bash setup.sh на этом компьютере.")
    if args == ["--check"]:
        command = [str(python), "-c", CHECK]
    elif args[:1] == ["--cli"]:
        command = [str(python), "-m", "spatial_upmixer.cli", *args[1:]]
    elif not args:
        command = [str(python), "-m", "spatial_upmixer.gui"]
    else:
        raise ValueError("Используйте --help, --check или --cli.")
    os.execv(str(python), command)
    return 0


if __name__ == "__main__":
    try:
        action, *arguments = sys.argv[1:]
        raise SystemExit({"setup": setup, "launch": launch}[action](arguments))
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Spatial Upmixer: {error}", file=sys.stderr)
        raise SystemExit(1)
