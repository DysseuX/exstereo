# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Bounded Tk control/layout check, without loading Demucs or starting a job.

Needs a graphical DISPLAY. Optional PNGs use existing FFmpeg x11grab and
capture only this script's owned Tk window ID, not the desktop. Run through
scripts/run-limited.sh and the existing Upmixer Python environment.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import tkinter as tk
from unittest.mock import patch


PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "src"))

from spatial_upmixer.gui import UpmixerWindow
from spatial_upmixer.stem_stereo import STEM_NAMES
from spatial_upmixer.progress import ProgressEvent


def visible_inside(widget: tk.Widget, root: tk.Widget) -> None:
    assert widget.winfo_ismapped(), f"Control hidden: {widget}"
    left = widget.winfo_rootx() - root.winfo_rootx()
    top = widget.winfo_rooty() - root.winfo_rooty()
    width, height = widget.winfo_width(), widget.winfo_height()
    assert width > 1 and height > 1, f"Control collapsed: {widget}"
    assert left >= 0 and top >= 0, f"Control outside window: {widget}"
    assert left + width <= root.winfo_width(), f"Control clipped horizontally: {widget}"
    assert top + height <= root.winfo_height(), f"Control clipped vertically: {widget}"


def screenshot(root: tk.Tk, directory: Path | None, name: str) -> None:
    if directory is None:
        return
    if not shutil.which("ffmpeg") or not os.environ.get("DISPLAY"):
        raise RuntimeError("Для снимка нужны FFmpeg с x11grab и DISPLAY.")
    directory.mkdir(parents=True, exist_ok=True)
    root.update_idletasks()
    root.update()
    destination = directory / f"{name}.png"
    command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
               "-filter_threads", "1", "-filter_complex_threads", "1",
               "-f", "x11grab", "-window_id", str(root.winfo_id()),
               "-video_size", f"{root.winfo_width()}x{root.winfo_height()}",
               "-draw_mouse", "0", "-framerate", "1", "-i", os.environ["DISPLAY"],
               "-frames:v", "1", "-threads", "1", "-update", "1", str(destination)]
    subprocess.run(command, check=True, timeout=15, stdin=subprocess.DEVNULL)
    assert destination.is_file() and destination.stat().st_size > 100, "Empty screenshot"
    print(f"PNG: {destination}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Проверка GUI stereo/7.1 без запуска аудиообработки")
    parser.add_argument("--screenshot-dir", type=Path)
    parser.add_argument("--geometry", default="1020x940")
    args = parser.parse_args()
    root = tk.Tk()
    window = None
    try:
        with patch("spatial_upmixer.gui.run_job", side_effect=AssertionError("Audio jobs are forbidden in this GUI check")):
            window = UpmixerWindow(root)
            root.geometry(args.geometry + "+0+0")
            root.lift()
            root.update()
            visible_inside(window.start_button, root)
            visible_inside(window.cancel_button, root)
            visible_inside(window.progressbar, root)
            assert window.surround_frame.winfo_ismapped()
            assert not window.stereo_frame.winfo_ismapped()
            assert str(window.values["bitrate_kbps"].get()) == "512"
            assert str(window.preset_box.cget("state")) == "readonly"
            screenshot(root, args.screenshot_dir, "surround")

            window.values["input_path"].set("/tmp/gui-fixture.flac")
            window.values["output_path"].set("/tmp/gui-fixture.7.1.opus")
            window.values["output_mode"].set("Стерео по стемам")
            window.output_box.event_generate("<<ComboboxSelected>>")
            root.update()
            assert window.stereo_frame.winfo_ismapped()
            assert not window.surround_frame.winfo_ismapped()
            assert window.values["bitrate_kbps"].get() == "256"
            assert window.values["output_path"].get() == "/tmp/gui-fixture.stereo.opus"
            controls = {}
            for name in STEM_NAMES:
                for field in ("width", "pan", "gain"):
                    variable = window.values[f"{name}_{field}"]
                    matches = [widget for widget, _ in window._inputs
                               if "textvariable" in widget.keys() and str(widget.cget("textvariable")) == str(variable)]
                    assert len(matches) == 1, f"Missing/duplicate {name}/{field}"
                    widget = matches[0]
                    # Keyboard navigation must scroll every off-screen field
                    # into view while the fixed Start/Cancel remain accessible.
                    widget.focus_force()
                    root.update()
                    assert root.focus_get() is widget, f"Cannot focus {name}/{field}"
                    visible_inside(widget, window.form_canvas)
                    visible_inside(window.start_button, root)
                    visible_inside(window.cancel_button, root)
                    visible_inside(window.progressbar, root)
                    assert str(widget.cget("state")) == "normal"
                    controls[f"{name}_{field}"] = widget
            visible_inside(window.start_button, root)
            assert window.values["vocals_width"].get() == "0"
            assert window.values["bass_width"].get() == "0"
            assert window.values["other_width"].get() == "140"
            screenshot(root, args.screenshot_dir, "stem-stereo")

            # Wheel/scrollbar access must coexist with focus-following and must
            # not move the fixed footer even at either end of the long form.
            before_wheel = window.form_canvas.yview()
            window.form_canvas.event_generate("<Button-4>")
            root.update()
            assert window.form_canvas.yview()[0] <= before_wheel[0]
            for end in (0, 1):
                window.form_canvas.yview_moveto(end)
                root.update()
                visible_inside(window.start_button, root)
                visible_inside(window.cancel_button, root)
            screenshot(root, args.screenshot_dir, "stem-stereo-bottom")

            preset_buttons = [widget for widget, _ in window._inputs
                              if "text" in widget.keys() and str(widget.cget("text")) == "Исходный баланс"]
            assert len(preset_buttons) == 1
            preset_buttons[0].focus_force()
            root.update()
            visible_inside(preset_buttons[0], window.form_canvas)
            preset_buttons[0].invoke()
            root.update()
            for name in STEM_NAMES:
                assert window.values[f"{name}_width"].get() == "100"
                assert window.values[f"{name}_pan"].get() == "0"
                assert window.values[f"{name}_gain"].get() == "0"
            window._running = True
            window._update_input_states()
            assert all(str(widget.cget("state")) == "disabled" for widget in controls.values())
            assert str(window.output_box.cget("state")) == "disabled"
            assert str(window.start_button.cget("state")) == "disabled"
            window._running = False
            window._update_input_states()

            window.values["output_path"].set("/tmp/my-custom-name.opus")
            window.output_box.focus_force()
            root.update()
            visible_inside(window.output_box, window.form_canvas)
            window.values["output_mode"].set("7.1 · семь каналов + LFE")
            window.output_box.event_generate("<<ComboboxSelected>>")
            root.update()
            assert window.surround_frame.winfo_ismapped() and not window.stereo_frame.winfo_ismapped()
            assert window.values["output_path"].get() == "/tmp/my-custom-name.opus"
            assert window.values["bitrate_kbps"].get() == "512"
            window.values["upmix"].set("Мягкий upmix (прежний)")
            window.upmix_box.event_generate("<<ComboboxSelected>>")
            root.update()
            assert str(window.preset_box.cget("state")) == "disabled"
            window.values["upmix"].set("SurroundUpmix / CenterCutCL")
            window.upmix_box.event_generate("<<ComboboxSelected>>")
            root.update()
            assert str(window.preset_box.cget("state")) == "readonly"
            # Deliberate display fixtures, not evidence of an actual GPU run.
            window._running = True
            window._job_started = time.monotonic() - 35
            window._update_input_states()
            window._handle_progress(ProgressEvent("separate_load", None, "Загрузка весов (проверка интерфейса)…",
                demucs="CUDA выбрана, первый проход ещё не выполнен", elapsed_seconds=35))
            assert window._indeterminate
            assert "пока неизвестно" in window.timing_text.get()
            assert "подтверждено" not in window.device_text.get()
            window._handle_progress(ProgressEvent("separate", None, "Модель 2/4, фрагмент 6/20 — пример интерфейса",
                stage_fraction=.4, device="cuda:0 · тестовый GPU", demucs="CUDA: вычисление подтверждено (тест UI)",
                elapsed_seconds=35, stage_elapsed_seconds=20, eta_seconds=30, important=True))
            assert not window._indeterminate
            assert float(window.progressbar.cget("value")) == 40
            assert "00:30" in window.timing_text.get()
            assert "подтверждено" in window.device_text.get()
            root.update()
            visible_inside(window.start_button, root)
            visible_inside(window.cancel_button, root)
            visible_inside(window.progressbar, root)
            screenshot(root, args.screenshot_dir, "progress-cuda-display-fixture")
            window._handle_progress(ProgressEvent("encode", None, "Кодирование Opus: 50% — пример интерфейса",
                stage_fraction=.5, device="CPU · libopus", demucs="CUDA: вычисление подтверждено (тест UI)",
                elapsed_seconds=50, eta_seconds=5))
            assert "CPU · libopus" in window.device_text.get() and "CUDA" in window.device_text.get()
            window._cancel.set()
            window._finish("cancelled", "Тестовое завершение")
            assert not window._running and not window._indeterminate
            assert "До конца этапа" not in window.timing_text.get()
            window._cancel.clear()
            print(json.dumps({"result": "PASS", "geometry": f"{root.winfo_width()}x{root.winfo_height()}",
                              "stem_rows": 6, "stem_controls": 18,
                              "checks": ["stereo/surround switching", "default controls", "neutral preset button",
                                         "running-state controls", "auto/custom filenames", "legacy cascade/soft switching",
                                         "keyboard access to all 18 controls via scrolling", "wheel/scrollbar access",
                                         "Start/Cancel/progress always visible", "live progress/ETA/device/cancel",
                                         "CUDA display fixture is NOT a real GPU test"], "audio_jobs_started": 0}, ensure_ascii=False), flush=True)
    finally:
        if window is not None and window._poll_id is not None:
            root.after_cancel(window._poll_id)
        root.destroy()


if __name__ == "__main__":
    main()
