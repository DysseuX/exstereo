# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import threading

from .pipeline import CancelledError, JobConfig, ProgressEvent, run_job
from .progress import timing
from .surroundupmix import PRESETS
from .stem_stereo import parse_stem_controls


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Demucs → стерео по стемам или 7.1 → Opus для Android")
    parser.add_argument("input", type=Path, help="FLAC, MP3 или Opus")
    parser.add_argument("output", type=Path, help="Выходной файл .opus")
    parser.add_argument("--stems", type=Path, help="Готовые bass/drums/vocals/other WAV/FLAC вместо запуска Demucs")
    parser.add_argument("--model", default="htdemucs_ft", choices=["htdemucs", "htdemucs_ft", "htdemucs_6s"])
    parser.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    parser.add_argument("--bitrate", type=int, default=None, help="кбит/с на весь файл; по умолчанию 256 stereo / 512 surround")
    parser.add_argument("--output-mode", choices=("surround", "stereo"), default="surround",
                        help="7.1 или двухканальный ремикс с расширением отдельных стемов")
    parser.add_argument("--stereo-neutral", action="store_true",
                        help="Начать с исходной ширины/панорамы/уровней; далее применить --stereo-stem")
    parser.add_argument("--stereo-stem", action="append", default=[], metavar="NAME=WIDTH,PAN,DB",
                        help="Например other=1.4,0,-1; ширина 0…2, панорама −1…1, уровень −60…+6 дБ")
    parser.add_argument("--upmix", choices=("surroundupmix", "soft"), default="surroundupmix",
                        help="Три каскада CenterCutCL или прежний мягкий режим")
    parser.add_argument("--preset", choices=tuple(PRESETS), default="Music", help="Пресет SurroundUpmix")
    parser.add_argument("--blend", choices=("classic", "full"), default="classic")
    parser.add_argument("--route", action="append", default=[], metavar="STEM=METHOD",
                        help="Например vocals=5.0; методы: 4.0, 5.0, mono, front, rear")
    parser.add_argument("--surround-db", type=float, default=None,
                        help="Поправка окружения: по умолчанию 0 дБ для SurroundUpmix, −6 для soft")
    parser.add_argument("--lfe-db", type=float, default=-10)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--overwrite", action="store_true", help="Разрешить заменить существующий выходной файл")
    args = parser.parse_args(argv)
    previous = None

    def report(event: ProgressEvent) -> None:
        nonlocal previous
        bucket = (event.stage, int((event.stage_fraction or 0) * 20))
        if event.important or bucket != previous:
            print(f"{event.message}\n{timing(event)}\nСейчас: {event.device}. Demucs: {event.demucs}.",
                  file=sys.stderr, flush=True)
            previous = bucket

    try:
        if args.output_mode != "stereo" and (args.stereo_stem or args.stereo_neutral):
            raise ValueError("Параметры --stereo-stem / --stereo-neutral требуют --output-mode stereo.")
        stereo_settings = parse_stem_controls(args.stereo_stem, neutral=args.stereo_neutral)
        routes = []
        for route in args.route:
            if "=" not in route:
                raise ValueError("Маршрут задаётся как vocals=5.0.")
            routes.append(tuple(route.split("=", 1)))
        result = run_job(JobConfig(args.input, args.output, stems_dir=args.stems,
                                  model=args.model, device=args.device, bitrate_kbps=args.bitrate,
                                  surround_gain_db=args.surround_db if args.surround_db is not None else (0 if args.upmix == "surroundupmix" else -6),
                                  lfe_gain_db=args.lfe_db, upmix=args.upmix,
                                  cascade_preset=args.preset, cascade_blend=args.blend, stem_routes=tuple(routes),
                                  output_mode=args.output_mode, stereo_settings=stereo_settings,
                                  overwrite=args.overwrite, cache_dir=args.cache_dir), report, threading.Event())
        print(result)
        return 0
    except (CancelledError, KeyboardInterrupt):
        print("Отменено.", file=sys.stderr)
        return 130
    except (ValueError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
