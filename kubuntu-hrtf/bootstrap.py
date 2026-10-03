# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Portable HRTF environment selection and launcher."""
from __future__ import annotations

from pathlib import Path
import os
import shutil
import subprocess
import sys

PROJECT = Path(__file__).resolve().parent


def environment_dir() -> Path:
    data = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
    if not data.is_absolute():
        raise ValueError("XDG_DATA_HOME должен содержать абсолютный путь.")
    version = f"{sys.version_info.major}.{sys.version_info.minor}"
    return data / "3d-audio" / "venvs" / f"hrtf-py{version}"


def runtime_python() -> Path:
    override = os.environ.get("SPATIAL_PYTHON")
    if override:
        candidate = shutil.which(override)
        if not candidate:
            raise ValueError(f"SPATIAL_PYTHON не найден: {override}")
        # Resolving a venv executable symlink would select the base interpreter.
        return Path(candidate).absolute()
    environment = environment_dir()
    dedicated = environment / "bin/python"
    if dedicated.is_file():
        return dedicated
    fallback = environment.with_name(environment.name.replace("hrtf-", "upmixer-", 1))
    return fallback / "bin/python"


def main() -> int:
    action, *args = sys.argv[1:]
    if sys.version_info < (3, 11):
        raise ValueError("Нужен Python 3.11 или новее.")
    python = runtime_python()
    if action == "setup":
        android_export = args == ["--android-export"]
        if args and not android_export:
            raise ValueError("setup.sh принимает только --android-export; SPATIAL_PYTHON задаёт существующий venv для запуска.")
        if os.environ.get("SPATIAL_PYTHON"):
            raise ValueError("setup.sh создаёт отдельное HRTF-окружение. Уберите SPATIAL_PYTHON; эта переменная нужна только для запуска.")
        target = environment_dir()
        python = target / "bin/python"
        if (target / "pyvenv.cfg").is_file():
            config = (target / "pyvenv.cfg").read_text(encoding="utf-8").lower()
            if "include-system-site-packages = true" in config:
                raise ValueError(f"Окружение {target} наследует системные пакеты. Для setup требуется чистый venv.")
        elif not python.is_file():
            if target.exists() and any(target.iterdir()):
                raise ValueError(f"Каталог занят: {target}")
            subprocess.run([sys.executable, "-m", "venv", str(target)], check=True)
        probe = subprocess.run([str(python), "-c", "import torch; assert tuple(map(int, torch.__version__.split('.')[:2])) >= (2, 6)"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if probe.returncode:
            subprocess.run([str(python), "-m", "pip", "install", "--prefer-binary", "--index-url",
                            "https://download.pytorch.org/whl/cpu", "torch>=2.6"], check=True)
        subprocess.run([str(python), "-m", "pip", "install", "--prefer-binary", "--editable", str(PROJECT)], check=True)
        if android_export:
            subprocess.run([str(python), "-m", "pip", "install", "--only-binary=:all:",
                            "onnx>=1.17,<2", "onnxruntime>=1.20,<2", "h5py>=3.10,<4"], check=True)
        print("Готово. Запуск: bash launch.sh; проверка: bash launch.sh --check")
        return 0
    if args == ["--help"]:
        print("bash launch.sh — окно\nbash launch.sh --check — проверка\n"
              "bash launch.sh --cli build manifest.json --output DIR — генерация\n"
              "bash launch.sh --tool SCRIPT.py ARGS — инструменты экспорта в том же ограниченном окружении\n"
              "На новом ПК: bash setup.sh (отдельный venv). SPATIAL_PYTHON задаёт существующий venv для запуска.")
        return 0
    if not python.is_file():
        raise ValueError("Не найдено Python-окружение. Выполните bash setup.sh.")
    if args[:1] == ["--tool"]:
        if len(args) < 2:
            raise ValueError("Укажите путь к Python-скрипту после --tool.")
        tool = Path(args[1]).resolve()
        if not tool.is_file() or tool.suffix != ".py":
            raise ValueError(f"Не найден Python-скрипт: {tool}")
        os.execv(str(python), [str(python), str(tool), *args[2:]])
    if not args:
        args = ["gui"]
    elif args == ["--check"]:
        args = ["check"]
    elif args[:1] == ["--cli"]:
        args = args[1:]
    else:
        raise ValueError("Используйте --help, --check или --cli.")
    os.execv(str(python), [str(python), "-m", "spatial_hrtf", *args])
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Spatial HRTF: {error}", file=sys.stderr)
        raise SystemExit(1)
