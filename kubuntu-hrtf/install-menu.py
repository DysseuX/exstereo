#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Generate a menu entry tied to this checkout, with proper Desktop quoting."""
import argparse
import importlib.util
import os
from pathlib import Path

project = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("upmixer_menu", project.parent / "kubuntu-upmixer/install-menu.py")
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)


def main():
    parser = argparse.ArgumentParser(description="Ярлык Spatial HRTF для этой папки проекта")
    parser.add_argument("--install", action="store_true")
    options = parser.parse_args()
    output = project / "spatial-hrtf.desktop"
    if options.install:
        data = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
        if not data.is_absolute():
            parser.error("XDG_DATA_HOME должен быть абсолютным")
        output = data / "applications/spatial-hrtf.desktop"
    text = "\n".join([
        "[Desktop Entry]", "Version=1.0", "Type=Application", "Name=Spatial HRTF",
        "Name[ru]=Spatial HRTF — профиль по фото ушей",
        "Comment=Create an experimental personal SOFA profile from ear photographs",
        "Comment[ru]=Локальное создание экспериментального HRTF по фотографиям ушей",
        "Exec=/bin/bash " + helpers.argument(str(project / "launch.sh")),
        "TryExec=/bin/bash", "Path=" + helpers.value(str(project)),
        "Icon=audio-card", "Terminal=false", "StartupNotify=false",
        "Categories=AudioVideo;Audio;AudioVideoEditing;",
        "Keywords=HRTF;SOFA;spatial;headphones;ears;", "",
    ])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
