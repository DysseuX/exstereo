# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

from __future__ import annotations

import argparse
from pathlib import Path
import signal
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description="Spatial HRTF — локальный ПК-прототип профиля по фото ушей")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("gui", help="Открыть графическое окно")
    commands.add_parser("check", help="Проверить зависимости и контрольные суммы, без инференса")
    build = commands.add_parser("build", help="Создать personal.sofa из JSON с параметрами фото")
    build.add_argument("manifest", type=Path)
    build.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "gui":
            from .gui import main as gui_main
            gui_main()
        elif args.command == "check":
            import importlib.metadata
            import tkinter
            from .neural import verify_assets, UPSTREAM
            from .hybrid import load_reference
            for package in ("numpy", "scipy", "Pillow", "torch", "netCDF4"):
                print(package, importlib.metadata.version(package))
            info = verify_assets()
            positions, _, rate = load_reference(UPSTREAM)
            print(f"Веса: OK ({info['commit']}); SOFA: {len(positions)} направлений, {rate} Гц")
        else:
            from .pipeline import build_profile
            def cancelled(signum, frame):
                raise KeyboardInterrupt
            signal.signal(signal.SIGTERM, cancelled)
            build_profile(args.manifest, args.output)
        return 0
    except KeyboardInterrupt:
        print("Обработка отменена.", file=sys.stderr)
        return 130
    except (ValueError, OSError, KeyError, TypeError, ImportError, RuntimeError) as error:
        print(f"Spatial HRTF: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
