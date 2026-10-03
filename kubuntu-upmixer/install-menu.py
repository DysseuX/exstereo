#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Generate a launcher for this checkout; installation is explicitly requested."""

from __future__ import annotations

import argparse
import os
from pathlib import Path


def value(text: str) -> str:
    return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")


def argument(text: str) -> str:
    # Desktop Entry Exec has its own quoting, followed by string unescaping.
    quoted = "".join("\\" + char if char in '\\"`$' else char for char in text)
    return value('"' + quoted.replace("%", "%%") + '"')


def main() -> None:
    project = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Создать ярлык для текущей папки проекта")
    destination = parser.add_mutually_exclusive_group()
    destination.add_argument("--output", type=Path, default=None,
                             help="Только записать файл для проверки; по умолчанию ./spatial-upmixer.desktop")
    destination.add_argument("--install", action="store_true", help="Установить в меню приложений этого пользователя")
    options = parser.parse_args()
    template = (project / "spatial-upmixer.desktop").read_text(encoding="utf-8")
    lines = [line for line in template.splitlines()
             if not line.startswith(("Exec=", "TryExec=", "Path="))]
    lines.extend((f"Exec=/bin/bash {argument(str(project / 'launch.sh'))}",
                  "TryExec=/bin/bash", f"Path={value(str(project))}"))
    if options.install:
        data = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
        if not data.is_absolute():
            parser.error("XDG_DATA_HOME должен содержать абсолютный путь")
        output = data / "applications/spatial-upmixer.desktop"
    else:
        output = options.output or Path.cwd() / "spatial-upmixer.desktop"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(output.resolve())


if __name__ == "__main__":
    main()
