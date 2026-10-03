# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Functional Tk/worker check using a synthetic drawing, never a user profile.

Run with the project limiter and HRTF runtime Python. Needs a DISPLAY, but the
window stays hidden. No controls in other applications are touched.
"""
from pathlib import Path
import os
import sys
import tempfile
import time
import tkinter as tk

from PIL import Image, ImageDraw

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "src"))
os.environ["PYTHONPATH"] = str(PROJECT / "src")
from spatial_hrtf.gui import HrtfWindow, EarPhoto


def main():
    with tempfile.TemporaryDirectory(prefix="spatial-hrtf-gui-") as directory:
        folder = Path(directory)
        picture = Image.new("RGB", (600, 600), (120, 120, 120))
        draw = ImageDraw.Draw(picture)
        draw.ellipse((170, 90, 420, 500), outline="white", width=10)
        draw.arc((225, 150, 405, 440), 40, 330, fill="black", width=8)
        picture.save(folder / "synthetic-photo.png")
        root = tk.Tk()
        root.withdraw()
        window = HrtfWindow(root)
        try:
            assert not window.head_width.get() and not window.head_depth.get()
            window.head_width.set("16")
            window.head_depth.set("20")
            window.output_directory.set(str(folder))
            for ear in ("left", "right"):
                window.photos[ear] = EarPhoto(folder / "synthetic-photo.png", picture.copy(),
                                              [(300, 300), (40, 20), (240, 20)])
                window.reference_mm[ear].set("40")
            window.select_ear()
            window.preview()
            assert window._preview_tk is not None
            window.start()
            assert window.process is not None, window.status.get()
            deadline = time.monotonic() + 45
            while window.process is not None and time.monotonic() < deadline:
                root.update()
                time.sleep(0.05)
            assert window.process is None, "GUI worker timed out"
            result = window.latest_output
            assert (result / "personal.sofa").is_file(), window.log.get("1.0", "end")
            log = (result / "worker.log").read_text(encoding="utf-8")
            assert "Готово:" in log
            assert (result / "worker.log").stat().st_size <= 1024 * 1024
            print("PASS: Tk init, empty head fields, EXIF/photo preview, real worker, SOFA and saved log")
        finally:
            if window.process is not None:
                window._signal_process(window.process, force=True)
                window.process.wait(timeout=5)
            root.destroy()


if __name__ == "__main__":
    main()
