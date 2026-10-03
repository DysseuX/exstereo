# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Tk photo preparation and local HRTF worker launcher.

``main()`` opens the window. Invoke it through the project's limited launcher;
the child Python process inherits its CPU and memory cgroup. Photo coordinates
refer to the full-resolution image *after* EXIF orientation is applied.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
import json
import os
from pathlib import Path
import queue
import signal
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk

from PIL import Image, ImageTk

from .photos import open_photo, prepare_photo


@dataclass
class EarPhoto:
    path: Path | None = None
    image: Image.Image | None = None
    points: list[tuple[float, float]] = field(default_factory=list)


class HrtfWindow:
    """Prepare two calibrated ear images, save a manifest, run one worker."""

    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("Spatial HRTF — профиль по фото ушей")
        root.geometry("1160x860")
        root.minsize(940, 740)
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.photos = {"left": EarPhoto(), "right": EarPhoto()}
        self.current = tk.StringVar(value="left")
        self.reference_mm = {ear: tk.StringVar(value="30") for ear in self.photos}
        self.threshold = {ear: tk.StringVar(value="0.15") for ear in self.photos}
        self.mirror = {ear: tk.BooleanVar(value=ear == "right") for ear in self.photos}
        self.head_width = tk.StringVar()
        self.head_depth = tk.StringVar()
        default_output = Path(__file__).resolve().parents[2] / "outputs"
        self.output_directory = tk.StringVar(value=str(default_output))
        self.photo_path = tk.StringVar(value="Фото не выбрано")
        self.instruction = tk.StringVar()
        self.status = tk.StringVar(value="Выберите фото левого и правого уха, отметьте масштаб.")
        self.process: subprocess.Popen | None = None
        self.events: queue.Queue = queue.Queue()
        self.cancelled = False
        self.closing = False
        self.latest_output: Path | None = None
        self.canvas_scale = 1.0
        self.canvas_offset = (0.0, 0.0)
        self._photo_tk = None
        self._preview_tk = None
        self._inputs: list[ttk.Widget] = []
        self._build()
        self.select_ear()
        root.after(120, self._drain_events)

    def _input(self, widget):
        self._inputs.append(widget)
        return widget

    def _build(self):
        outer = ttk.Frame(self.root, padding=12)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="Фото → контуры ушей → персональный HRTF", font=("Sans", 17, "bold")).pack(anchor="w")
        ttk.Label(outer, text="Сфотографируйте каждое ухо сбоку вместе с линейкой. Контуры проверяются перед расчётом на этом ПК.", wraplength=1100).pack(anchor="w", pady=(5, 10))
        toolbar = ttk.Frame(outer)
        toolbar.pack(fill="x")
        for ear, label in [("left", "Левое ухо"), ("right", "Правое ухо")]:
            self._input(ttk.Radiobutton(toolbar, text=label, value=ear, variable=self.current, command=self.select_ear)).pack(side="left", padx=(0, 15))
        self._input(ttk.Button(toolbar, text="Выбрать фото…", command=self.choose_photo)).pack(side="left", padx=5)
        self._input(ttk.Button(toolbar, text="Сбросить точки", command=self.reset_points)).pack(side="left", padx=5)
        ttk.Label(outer, textvariable=self.photo_path, foreground="#555555", wraplength=1100).pack(anchor="w", pady=5)

        body = ttk.Frame(outer)
        body.pack(fill="both", expand=True)
        body.columnconfigure(0, weight=1)
        body.rowconfigure(0, weight=1)
        self.canvas = tk.Canvas(body, background="#20242a", highlightthickness=0, width=720, height=420)
        self.canvas.grid(row=0, column=0, sticky="nsew", padx=(0, 12))
        self.canvas.bind("<Configure>", lambda _event: self.draw_photo())
        self.canvas.bind("<Button-1>", self.click_photo)
        sidebar = ttk.Frame(body, width=280)
        sidebar.grid(row=0, column=1, sticky="ns")
        ttk.Label(sidebar, text="Три отметки на фото", font=("Sans", 11, "bold")).pack(anchor="w")
        ttk.Label(sidebar, text="1. Вход в слуховой проход\n2. Начало отрезка на линейке\n3. Конец этого отрезка", justify="left").pack(anchor="w", pady=(5, 10))
        ttk.Label(sidebar, text="Длина отрезка между 2 и 3, мм").pack(anchor="w")
        self.reference_entry = self._input(ttk.Entry(sidebar, width=12))
        self.reference_entry.pack(anchor="w", pady=(3, 8))
        self.mirror_checkbox = self._input(ttk.Checkbutton(sidebar, text="Отразить фото по горизонтали"))
        self.mirror_checkbox.pack(anchor="w", pady=(0, 8))
        ttk.Label(sidebar, text="Порог выделения контуров").pack(anchor="w")
        self.threshold_entry = self._input(ttk.Spinbox(sidebar, from_=0.02, to=0.9, increment=0.01, width=12))
        self.threshold_entry.pack(anchor="w", pady=(3, 8))
        self._input(ttk.Button(sidebar, text="Показать контуры", command=self.preview)).pack(anchor="w", pady=(0, 8))
        self.preview_label = ttk.Label(sidebar, text="Предпросмотр 256 × 256", anchor="center")
        self.preview_label.pack(fill="both", expand=True)
        ttk.Label(sidebar, text="Нужны контуры раковины, завитка и прохода. Тени и волосы могут дать лишние линии.", wraplength=270, foreground="#555555").pack(anchor="w", pady=(8, 0))
        ttk.Label(outer, textvariable=self.instruction, foreground="#215b93").pack(anchor="w", pady=(7, 9))

        measurements = ttk.Frame(outer)
        measurements.pack(fill="x")
        ttk.Label(measurements, text="Ширина головы, см:").pack(side="left")
        self._input(ttk.Entry(measurements, textvariable=self.head_width, width=7)).pack(side="left", padx=(5, 20))
        ttk.Label(measurements, text="Глубина головы, см:").pack(side="left")
        self._input(ttk.Entry(measurements, textvariable=self.head_depth, width=7)).pack(side="left", padx=5)
        ttk.Label(measurements, text="Измерьте от лба до затылка.", foreground="#555555").pack(side="left", padx=10)
        output = ttk.Frame(outer)
        output.pack(fill="x", pady=(10, 4))
        ttk.Label(output, text="Результаты:").pack(side="left")
        self._input(ttk.Entry(output, textvariable=self.output_directory)).pack(side="left", fill="x", expand=True, padx=7)
        self._input(ttk.Button(output, text="Папка…", command=self.choose_output)).pack(side="left")
        ttk.Label(outer, text="Каждый расчёт сохраняется в отдельную подпапку с датой и временем.", foreground="#555555").pack(anchor="w")
        actions = ttk.Frame(outer)
        actions.pack(fill="x", pady=(8, 5))
        self.start_button = self._input(ttk.Button(actions, text="Создать профиль SOFA", command=self.start))
        self.start_button.pack(side="left")
        self.cancel_button = ttk.Button(actions, text="Отмена", command=self.cancel, state="disabled")
        self.cancel_button.pack(side="left", padx=8)
        self.open_button = ttk.Button(actions, text="Открыть папку результата", command=self.open_output, state="disabled")
        self.open_button.pack(side="left")
        self.progress = ttk.Progressbar(actions, mode="indeterminate", length=120)
        self.progress.pack(side="right")
        ttk.Label(outer, textvariable=self.status, wraplength=1100).pack(anchor="w", pady=4)
        self.log = scrolledtext.ScrolledText(outer, height=6, wrap="word", state="disabled", font=("Monospace", 9))
        self.log.pack(fill="x")

    def select_ear(self):
        ear = self.current.get()
        self.reference_entry.configure(textvariable=self.reference_mm[ear])
        self.threshold_entry.configure(textvariable=self.threshold[ear])
        self.mirror_checkbox.configure(variable=self.mirror[ear])
        self.photo_path.set(str(self.photos[ear].path or "Фото не выбрано"))
        self._preview_tk = None
        self.preview_label.configure(image="", text="Предпросмотр 256 × 256")
        self.draw_photo()
        self.update_instruction()

    def choose_photo(self):
        path = filedialog.askopenfilename(parent=self.root, title="Фото уха с линейкой", filetypes=[("Изображения", "*.jpg *.jpeg *.png *.webp *.bmp *.tif *.tiff"), ("Все файлы", "*")])
        if not path:
            return
        try:
            oriented = open_photo(path)
            previous = self.photos[self.current.get()].image
            if previous is not None:
                previous.close()
            self.photos[self.current.get()] = EarPhoto(Path(path).resolve(), oriented)
            self.select_ear()
        except Exception as exc:
            messagebox.showerror("Не удалось открыть фото", str(exc), parent=self.root)

    def draw_photo(self):
        self.canvas.delete("all")
        photo = self.photos[self.current.get()]
        width, height = max(1, self.canvas.winfo_width()), max(1, self.canvas.winfo_height())
        if photo.image is None:
            self.canvas.create_text(width / 2, height / 2, text="Выберите фото уха", fill="white", font=("Sans", 16))
            return
        self.canvas_scale = min(width / photo.image.width, height / photo.image.height)
        target = (max(1, round(photo.image.width * self.canvas_scale)), max(1, round(photo.image.height * self.canvas_scale)))
        resized = photo.image.resize(target, Image.Resampling.LANCZOS)
        self._photo_tk = ImageTk.PhotoImage(resized)
        ox, oy = (width - target[0]) / 2, (height - target[1]) / 2
        self.canvas_offset = (ox, oy)
        self.canvas.create_image(ox, oy, image=self._photo_tk, anchor="nw")
        displayed = [(ox + x * self.canvas_scale, oy + y * self.canvas_scale) for x, y in photo.points]
        if len(displayed) == 3:
            self.canvas.create_line(*displayed[1], *displayed[2], fill="#5dd8ff", width=3)
        for index, (x, y) in enumerate(displayed):
            color = "#ffbf47" if index == 0 else "#5dd8ff"
            self.canvas.create_oval(x - 6, y - 6, x + 6, y + 6, fill=color, outline="black", width=2)
            self.canvas.create_text(x + 13, y - 13, text=str(index + 1), fill=color, font=("Sans", 13, "bold"))

    def click_photo(self, event):
        if self.process is not None:
            return
        photo = self.photos[self.current.get()]
        if photo.image is None or len(photo.points) == 3:
            return
        ox, oy = self.canvas_offset
        x, y = (event.x - ox) / self.canvas_scale, (event.y - oy) / self.canvas_scale
        if not (0 <= x < photo.image.width and 0 <= y < photo.image.height):
            return
        photo.points.append((round(x, 3), round(y, 3)))
        self.draw_photo()
        self.update_instruction()
        if len(photo.points) == 3:
            self.preview()

    def update_instruction(self):
        count = len(self.photos[self.current.get()].points)
        prompts = ["Нажмите на вход в слуховой проход (точка 1).", "Отметьте начало известного отрезка на линейке (точка 2).", "Отметьте конец отрезка на линейке (точка 3).", "Три точки отмечены. Проверьте длину отрезка и контуры; затем подготовьте второе ухо."]
        self.instruction.set(prompts[count])

    def reset_points(self):
        self.photos[self.current.get()].points.clear()
        self.select_ear()

    @staticmethod
    def number(value: str, label: str) -> float:
        try:
            number = float(value.strip().replace(",", "."))
        except ValueError as exc:
            raise ValueError(f"{label}: введите число") from exc
        if not (0 < number < float("inf")):
            raise ValueError(f"{label}: требуется положительное конечное число")
        return number

    def ear_manifest(self, ear: str) -> dict:
        photo = self.photos[ear]
        label = "Левое ухо" if ear == "left" else "Правое ухо"
        if photo.path is None or len(photo.points) != 3:
            raise ValueError(f"{label}: выберите фото и отметьте все три точки")
        return {
            "path": str(photo.path), "canal": list(photo.points[0]),
            "reference": [list(photo.points[1]), list(photo.points[2])],
            "reference_mm": self.number(self.reference_mm[ear].get(), f"{label}, длина отрезка"),
            "mirror": self.mirror[ear].get(),
            "edge_threshold": self.number(self.threshold[ear].get(), f"{label}, порог контуров"),
        }

    @staticmethod
    def prepared(manifest: dict):
        return prepare_photo(
            manifest["path"], canal=tuple(manifest["canal"]),
            reference=tuple(tuple(point) for point in manifest["reference"]),
            reference_mm=manifest["reference_mm"], mirror=manifest["mirror"],
            edge_threshold=manifest["edge_threshold"],
        )

    def preview(self):
        try:
            preview = self.prepared(self.ear_manifest(self.current.get()))
            self._preview_tk = ImageTk.PhotoImage(preview)
            self.preview_label.configure(image=self._preview_tk, text="")
            self.status.set("Проверьте контуры. Порог можно изменить и построить предпросмотр снова.")
        except Exception as exc:
            self.status.set(str(exc))

    def choose_output(self):
        directory = filedialog.askdirectory(parent=self.root, title="Папка с результатами", initialdir=self.output_directory.get())
        if directory:
            self.output_directory.set(directory)

    def start(self):
        if self.process is not None:
            return
        try:
            manifest = {ear: self.ear_manifest(ear) for ear in ("left", "right")}
            manifest["head_width_cm"] = self.number(self.head_width.get(), "Ширина головы")
            manifest["head_depth_cm"] = self.number(self.head_depth.get(), "Глубина головы")
            if not (8 <= manifest["head_width_cm"] <= 30 and 8 <= manifest["head_depth_cm"] <= 35):
                raise ValueError("Проверьте размеры головы в сантиметрах")
            for ear in ("left", "right"):
                self.prepared(manifest[ear])
            folder_text = self.output_directory.get().strip()
            if not folder_text:
                raise ValueError("Укажите папку с результатами")
            base = Path(folder_text).expanduser().resolve()
            base.mkdir(parents=True, exist_ok=True)
            stem = datetime.now().strftime("%Y%m%d-%H%M%S")
            output = base / stem
            serial = 1
            while output.exists():
                output = base / f"{stem}-{serial}"
                serial += 1
            output.mkdir()
            manifest["output_directory"] = str(output)
            manifest_path = output / "manifest.json"
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            command = [sys.executable, "-u", "-m", "spatial_hrtf", "build", str(manifest_path), "--output", str(output)]
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                                       bufsize=1, start_new_session=(os.name == "posix"))
        except Exception as exc:
            messagebox.showerror("Не удалось начать расчёт", str(exc), parent=self.root)
            return
        self.process = process
        self.latest_output = output
        self.cancelled = False
        self._set_busy(True)
        self.status.set("Создаётся профиль. Фотографии обрабатываются на этом ПК.")
        self._append_log(f"\nПапка результата: {output}\n")
        threading.Thread(target=self._read_worker, args=(process, output), daemon=True).start()

    def _set_busy(self, busy: bool):
        for widget in self._inputs:
            widget.configure(state="disabled" if busy else "normal")
        self.cancel_button.configure(state="normal" if busy else "disabled")
        if busy:
            self.progress.start(12)
        else:
            self.progress.stop()
        self.open_button.configure(state="normal" if self.latest_output else "disabled")

    def _read_worker(self, process, output: Path):
        logfile = None
        written = 0
        limit = 1024 * 1024
        truncated = False
        marker = "\n[Журнал ограничен 1 МиБ; дальнейший вывод показан только в окне.]\n".encode("utf-8")
        try:
            logfile = (output / "worker.log").open("wb")
        except OSError as exc:
            self.events.put(("line", f"Не удалось сохранить worker.log: {exc}\n"))
        try:
            assert process.stdout is not None
            while line := process.stdout.readline(8192):
                self.events.put(("line", line))
                if logfile is not None and not truncated:
                    chunk = line.encode("utf-8", errors="replace")
                    available = limit - written
                    if len(chunk) > available - len(marker):
                        # Keep the truncated file valid UTF-8 and below 1 MiB.
                        chunk = chunk[:max(0, available - len(marker))].decode("utf-8", errors="ignore").encode("utf-8") + marker
                        truncated = True
                    try:
                        logfile.write(chunk)
                        logfile.flush()
                        written += len(chunk)
                    except OSError as exc:
                        self.events.put(("line", f"Не удалось дописать worker.log: {exc}\n"))
                        try:
                            logfile.close()
                        except OSError:
                            pass
                        logfile = None
        except Exception as exc:
            self.events.put(("line", f"Ошибка чтения журнала: {exc}\n"))
        finally:
            if logfile is not None:
                try:
                    logfile.close()
                except OSError as exc:
                    self.events.put(("line", f"Не удалось закрыть worker.log: {exc}\n"))
            if process.stdout is not None:
                process.stdout.close()
            self.events.put(("done", process.wait()))

    def _append_log(self, text: str):
        self.log.configure(state="normal")
        self.log.insert("end", text)
        if int(self.log.index("end-1c").split(".")[0]) > 1200:
            self.log.delete("1.0", "300.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _drain_events(self):
        for _ in range(150):
            try:
                kind, payload = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "line":
                self._append_log(payload)
            elif kind == "done":
                self.process = None
                self._set_busy(False)
                if self.closing:
                    self.root.destroy()
                    return
                if self.cancelled:
                    self.status.set("Расчёт отменён. Журнал и исходные настройки остались в папке результата.")
                elif payload == 0 and self.latest_output and (self.latest_output / "personal.sofa").is_file():
                    self.status.set(f"Готово: {self.latest_output / 'personal.sofa'}")
                else:
                    self.status.set(f"Профиль не создан, код завершения {payload}. Причина указана в журнале.")
        self.root.after(120, self._drain_events)

    @staticmethod
    def _signal_process(process, force: bool = False):
        if process.poll() is not None:
            return
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL if force else signal.SIGTERM)
            elif force:
                process.kill()
            else:
                process.terminate()
        except ProcessLookupError:
            pass

    def cancel(self):
        if self.process is None:
            return
        self.cancelled = True
        self.cancel_button.configure(state="disabled")
        self.status.set("Останавливаю расчёт…")
        process = self.process
        self._signal_process(process)
        self.root.after(3000, lambda: self._signal_process(process, force=True))

    def open_output(self):
        if self.latest_output is None:
            return
        try:
            subprocess.Popen(["xdg-open", str(self.latest_output)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError as exc:
            self.status.set(f"Папка: {self.latest_output}. Не удалось открыть автоматически: {exc}")

    def close(self):
        if self.process is not None:
            self.closing = True
            self.cancel()
        else:
            self.root.destroy()


def main() -> None:
    """Open the Russian desktop UI; project launchers apply resource limits."""
    root = tk.Tk()
    HrtfWindow(root)
    root.mainloop()


if __name__ == "__main__":
    main()
