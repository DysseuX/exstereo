# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Russian Tk desktop interface; all Tk calls stay on the main thread."""

from __future__ import annotations

import math
from pathlib import Path
import queue
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, font, messagebox, scrolledtext, ttk
from typing import Mapping

from .pipeline import CancelledError, JobConfig, ProgressEvent, run_job
from .progress import duration, timing
from .surroundupmix import PRESETS
from .stem_stereo import STEM_LABELS, STEM_NAMES, StemStereoControl, StemStereoSettings


_DEVICES = {"Авто": "auto", "CPU": "cpu", "NVIDIA CUDA": "cuda"}
_MODELS = ("htdemucs_ft", "htdemucs", "htdemucs_6s")
_UPMIX = {"SurroundUpmix / CenterCutCL": "surroundupmix", "Мягкий upmix (прежний)": "soft"}
_OUTPUTS = {"7.1 · семь каналов + LFE": "surround", "Стерео по стемам": "stereo"}


def job_config_from_form(values: Mapping[str, object]) -> JobConfig:
    """Validate user-entered values without requiring a Tk root or running DSP."""
    source_text = str(values.get("input_path", "")).strip()
    destination_text = str(values.get("output_path", "")).strip()
    if not source_text:
        raise ValueError("Выберите исходный файл FLAC, MP3 или Opus.")
    if not destination_text:
        raise ValueError("Укажите, куда сохранить готовый файл .opus.")
    source = Path(source_text).expanduser().resolve()
    destination = Path(destination_text).expanduser().resolve()
    if not source.is_file():
        raise ValueError("Исходный файл не найден. Проверьте путь или выберите файл заново.")
    if source.suffix.lower() not in (".flac", ".mp3", ".opus", ".ogg"):
        raise ValueError("Исходный файл должен быть FLAC, MP3 или Ogg Opus.")
    if destination.suffix.lower() != ".opus":
        raise ValueError("Имя готового файла должно заканчиваться на .opus.")
    if source == destination:
        raise ValueError("Выберите другое имя: исходный файл заменять нельзя.")
    if destination.exists():
        raise ValueError("Файл с таким именем уже существует. Выберите другое имя или папку.")
    stems = None
    if values.get("use_stems", False):
        stems_text = str(values.get("stems_dir", "")).strip()
        if not stems_text:
            raise ValueError("Выберите папку готовых дорожек или отключите этот режим.")
        stems = Path(stems_text).expanduser().resolve()
        if not stems.is_dir():
            raise ValueError("Папка готовых дорожек не найдена.")
    model = str(values.get("model", "htdemucs_ft"))
    device_label = str(values.get("device", "Авто"))
    if model not in _MODELS:
        raise ValueError("Выберите модель Demucs из списка.")
    if device_label not in _DEVICES:
        raise ValueError("Выберите устройство из списка.")
    upmix_label = str(values.get("upmix", "SurroundUpmix / CenterCutCL"))
    preset = str(values.get("cascade_preset", "Music"))
    blend = str(values.get("cascade_blend", "classic"))
    if upmix_label not in _UPMIX or preset not in PRESETS or blend not in ("classic", "full"):
        raise ValueError("Выберите алгоритм и пресет из списка.")
    output_label = str(values.get("output_mode", next(iter(_OUTPUTS))))
    if output_label not in _OUTPUTS:
        raise ValueError("Выберите формат результата из списка.")
    stereo_output = _OUTPUTS[output_label] == "stereo"
    try:
        bitrate = int(str(values.get("bitrate_kbps", "256" if _OUTPUTS[output_label] == "stereo" else "512")).strip())
    except ValueError as error:
        raise ValueError("Битрейт должен быть целым числом от 128 до 1024 кбит/с.") from error
    if not 128 <= bitrate <= 1024:
        raise ValueError("Битрейт всего файла: от 128 до 1024 кбит/с.")

    def gain(key: str, label: str, default: str) -> float:
        try:
            result = float(str(values.get(key, default)).strip().replace(",", "."))
        except ValueError as error:
            raise ValueError(f"{label}: введите число от −60 до +6 дБ.") from error
        if not math.isfinite(result) or not -60 <= result <= 6:
            raise ValueError(f"{label}: уровень должен быть от −60 до +6 дБ.")
        return result

    stereo_controls = []
    for name, default in StemStereoSettings().resolved().items():
        if not stereo_output:
            stereo_controls.append((name, default))
            continue
        try:
            width = float(str(values.get(f"{name}_width", default.width * 100)).replace(",", ".")) / 100
            pan = float(str(values.get(f"{name}_pan", default.pan * 100)).replace(",", ".")) / 100
            level = float(str(values.get(f"{name}_gain", default.gain_db)).replace(",", "."))
            stereo_controls.append((name, StemStereoControl(width, pan, level)))
        except (ValueError, TypeError) as error:
            raise ValueError(f"{STEM_LABELS[name]}: ширина 0…200%, панорама −100…100%, уровень −60…+6 дБ.") from error
    return JobConfig(
        input_path=source,
        output_path=destination,
        stems_dir=stems,
        model=model,
        device=_DEVICES[device_label],
        bitrate_kbps=bitrate,
        surround_gain_db=0.0 if stereo_output else gain("surround_gain_db", "Окружение", "0"),
        lfe_gain_db=-10.0 if stereo_output else gain("lfe_gain_db", "LFE", "-10"),
        upmix=_UPMIX[upmix_label], cascade_preset=preset, cascade_blend=blend,
        output_mode=_OUTPUTS[output_label], stereo_settings=StemStereoSettings(tuple(stereo_controls)),
        overwrite=False,
    )


class UpmixerWindow:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self._running = False
        self._closing = False
        self._cancel = threading.Event()
        self._events: queue.Queue[tuple[str, object]] = queue.Queue()
        self._inputs: list[tuple[ttk.Widget, str]] = []
        self._last_log_stage = ""
        self._last_log_fraction = -1.0
        self._indeterminate = False
        self._last_progress: ProgressEvent | None = None
        self._progress_received = 0.
        self._job_started = 0.
        self._last_device = ""
        self._poll_id: str | None = None
        defaults = {
            "input_path": "", "output_path": "", "stems_dir": "",
            "model": "htdemucs_ft", "device": "Авто", "bitrate_kbps": "512",
            "surround_gain_db": "0", "lfe_gain_db": "-10",
            "upmix": "SurroundUpmix / CenterCutCL", "cascade_preset": "Music", "cascade_blend": "classic",
            "output_mode": next(iter(_OUTPUTS)),
        }
        for name, control in StemStereoSettings().resolved().items():
            defaults.update({f"{name}_width": f"{100 * control.width:g}",
                             f"{name}_pan": f"{100 * control.pan:g}", f"{name}_gain": f"{control.gain_db:g}"})
        self.values = {name: tk.StringVar(root, value) for name, value in defaults.items()}
        self.use_stems = tk.BooleanVar(root, False)
        self.status = tk.StringVar(root, "Выберите исходный файл и место сохранения.")
        self.timing_text = tk.StringVar(root, "Прошло: 00:00  ·  ETA появится после начала обработки")
        self.device_text = tk.StringVar(root, "Авто — CPU. CUDA используется только при явном выборе NVIDIA CUDA.")
        root.title("Spatial Upmixer — стерео по стемам и 7.1")
        root.geometry("1020x940")
        root.minsize(920, 640)
        root.columnconfigure(0, weight=1)
        root.rowconfigure(0, weight=1)
        root.protocol("WM_DELETE_WINDOW", self._request_close)
        self._title_font = font.nametofont("TkDefaultFont").copy()
        self._title_font.configure(size=18, weight="bold")
        self._build()
        self._update_input_states()
        self._poll_id = root.after(100, self._poll)

    def _register(self, widget: ttk.Widget, state: str = "normal") -> ttk.Widget:
        self._inputs.append((widget, state))
        return widget

    def _build(self) -> None:
        # Keep transport/progress controls outside the scrolling form. A short
        # laptop display must not hide Start or Cancel behind the stem table.
        viewport = ttk.Frame(self.root)
        viewport.grid(row=0, column=0, sticky="nsew")
        viewport.columnconfigure(0, weight=1)
        viewport.rowconfigure(0, weight=1)
        self.form_canvas = tk.Canvas(viewport, highlightthickness=0, borderwidth=0,
                                     yscrollincrement=20, takefocus=False)
        self.form_canvas.grid(row=0, column=0, sticky="nsew")
        self.form_scrollbar = ttk.Scrollbar(viewport, orient="vertical", command=self.form_canvas.yview)
        self.form_scrollbar.grid(row=0, column=1, sticky="ns")
        self.form_canvas.configure(yscrollcommand=self.form_scrollbar.set)
        body = self.form_body = ttk.Frame(self.form_canvas, padding=20)
        self._form_window = self.form_canvas.create_window(0, 0, window=body, anchor="nw")
        body.bind("<Configure>", lambda _event: self.form_canvas.configure(scrollregion=self.form_canvas.bbox("all")))
        self.form_canvas.bind("<Configure>", lambda event: self.form_canvas.itemconfigure(self._form_window, width=event.width))
        self.root.bind("<MouseWheel>", self._scroll_form, add="+")
        self.root.bind("<Button-4>", self._scroll_form, add="+")
        self.root.bind("<Button-5>", self._scroll_form, add="+")
        self.root.bind("<FocusIn>", lambda event: self._show_control(event.widget), add="+")
        body.columnconfigure(0, weight=1)
        ttk.Label(body, text="Стерео по стемам и музыка в 7.1", font=self._title_font).grid(row=0, column=0, sticky="w")
        ttk.Label(
            body, text="Готовый Ogg Opus для Android: расширенное стерео или 7.1, 48 кГц."
        ).grid(row=1, column=0, sticky="w", pady=(4, 16))

        files = ttk.LabelFrame(body, text="Файлы", padding=12)
        files.grid(row=2, column=0, sticky="ew")
        files.columnconfigure(1, weight=1)
        self._path_row(files, 0, "Исходная музыка", "input_path", self._browse_input)
        self._path_row(files, 1, "Сохранить .opus", "output_path", self._browse_output)
        self._register(ttk.Checkbutton(
            files, text="Использовать готовые дорожки Demucs", variable=self.use_stems,
            command=self._update_input_states,
        )).grid(row=2, column=0, columnspan=3, sticky="w", pady=(8, 4))
        ttk.Label(files, text="Папка дорожек").grid(row=3, column=0, sticky="w", padx=(0, 12))
        self.stems_entry = ttk.Entry(files, textvariable=self.values["stems_dir"])
        self.stems_entry.grid(row=3, column=1, sticky="ew", pady=4)
        self.stems_browse = ttk.Button(files, text="Обзор…", command=self._browse_stems)
        self.stems_browse.grid(row=3, column=2, padx=(8, 0), pady=4)
        ttk.Label(
            files,
            text="В папке: bass, drums, vocals, other (WAV/FLAC); также допустимы guitar и piano.",
            wraplength=760,
        ).grid(row=4, column=0, columnspan=3, sticky="w", pady=(4, 0))

        settings = ttk.LabelFrame(body, text="Обработка", padding=12)
        settings.grid(row=3, column=0, sticky="ew", pady=(12, 0))
        for column in (1, 3):
            settings.columnconfigure(column, weight=1)
        self.model_box = self._combo(settings, 0, 0, "Модель Demucs", "model", _MODELS)
        self.device_box = self._combo(settings, 0, 2, "Вычисления", "device", tuple(_DEVICES))
        self._combo(settings, 1, 0, "Битрейт, кбит/с", "bitrate_kbps", (128, 192, 256, 384, 512, 640, 768, 1024))
        self.output_box = self._combo(settings, 1, 2, "Результат", "output_mode", tuple(_OUTPUTS))
        self.output_box.bind("<<ComboboxSelected>>", self._output_changed)
        self.surround_frame = ttk.Frame(settings)
        self.surround_frame.grid(row=2, column=0, columnspan=4, sticky="ew")
        for column in (1, 3):
            self.surround_frame.columnconfigure(column, weight=1)
        for column, label, key in ((0, "Окружение, дБ", "surround_gain_db"), (2, "LFE, дБ", "lfe_gain_db")):
            ttk.Label(self.surround_frame, text=label).grid(row=0, column=column, sticky="w", padx=(14 if column else 0, 10), pady=5)
            spin = self._register(ttk.Spinbox(self.surround_frame, from_=-60, to=6, increment=1, width=12, textvariable=self.values[key]))
            spin.grid(row=0, column=column + 1, sticky="ew", pady=5)
        self.upmix_box = self._combo(self.surround_frame, 1, 0, "Алгоритм", "upmix", tuple(_UPMIX))
        self.preset_box = self._combo(self.surround_frame, 1, 2, "Пресет", "cascade_preset", tuple(PRESETS))
        self.blend_box = self._combo(self.surround_frame, 2, 0, "ВЧ окружения", "cascade_blend", ("classic", "full"))
        self.upmix_box.bind("<<ComboboxSelected>>", self._upmix_changed)
        ttk.Label(
            self.surround_frame,
            text="htdemucs_ft работает дольше; htdemucs_6s дополнительно выделяет гитару и пианино.\n"
                 "SurroundUpmix: три каскада CenterCut, LFE 90 Гц ×2. Мягкий режим: LFE 120 Гц.\n"
                 "classic — исходный баланс пресета; full — более сильное окружение. none — без пресета.",
            wraplength=900,
        ).grid(row=3, column=0, columnspan=4, sticky="w", pady=(6, 0))

        self.stereo_frame = ttk.Frame(settings)
        self.stereo_frame.grid(row=2, column=0, columnspan=4, sticky="ew")
        for column, label in enumerate(("Дорожка Demucs", "Ширина, %", "Панорама, % · ← − / + →", "Уровень, дБ")):
            self.stereo_frame.columnconfigure(column, weight=1)
            ttk.Label(self.stereo_frame, text=label).grid(row=0, column=column, sticky="w", padx=(0, 12), pady=(6, 3))
        for row, name in enumerate(STEM_NAMES, 1):
            ttk.Label(self.stereo_frame, text=STEM_LABELS[name]).grid(row=row, column=0, sticky="w", pady=2)
            for column, key, low, high, step in ((1, "width", 0, 200, 5), (2, "pan", -100, 100, 5), (3, "gain", -60, 6, 1)):
                spin = self._register(ttk.Spinbox(self.stereo_frame, from_=low, to=high, increment=step,
                                                 width=12, textvariable=self.values[f"{name}_{key}"]))
                spin.grid(row=row, column=column, sticky="ew", padx=(0, 12), pady=2)
        buttons = ttk.Frame(self.stereo_frame)
        buttons.grid(row=7, column=0, columnspan=4, sticky="ew", pady=(5, 0))
        self._register(ttk.Button(buttons, text="Вокал/бас в центре", command=lambda: self._stereo_preset(False))).pack(side="left")
        self._register(ttk.Button(buttons, text="Исходный баланс", command=lambda: self._stereo_preset(True))).pack(side="left", padx=8)
        ttk.Label(self.stereo_frame,
                  text="100% — исходная ширина; 0% — моно. * Если есть отдельные стемы (модель 6s).\n"
                       "Остаток разделения сохраняется; HRTF в файл не записывается. Панорама перемещает весь стем.",
                  wraplength=890).grid(row=8, column=0, columnspan=4, sticky="w", pady=(6, 0))

        ttk.Label(
            body,
            text="При первом запуске Demucs скачивает веса модели. Затем они используются из кэша. "
                 "Обработка на CPU может занять продолжительное время. Готовые дорожки позволяют пропустить Demucs.",
            wraplength=820,
        ).grid(row=4, column=0, sticky="ew", pady=(12, 10))

        footer = ttk.Frame(self.root, padding=(20, 8, 20, 12))
        footer.grid(row=1, column=0, sticky="ew")
        footer.columnconfigure(0, weight=1)
        progress = ttk.Frame(footer)
        progress.grid(row=0, column=0, sticky="ew")
        progress.columnconfigure(0, weight=1)
        ttk.Label(progress, textvariable=self.status, wraplength=790).grid(row=0, column=0, sticky="w", pady=(0, 6))
        ttk.Label(progress, textvariable=self.timing_text, wraplength=790).grid(row=1, column=0, sticky="w", pady=(0, 4))
        ttk.Label(progress, textvariable=self.device_text, wraplength=790).grid(row=2, column=0, sticky="w", pady=(0, 6))
        self.progressbar = ttk.Progressbar(progress, maximum=100, mode="determinate")
        self.progressbar.grid(row=3, column=0, sticky="ew")
        self.log = scrolledtext.ScrolledText(body, height=7, wrap="word", state="disabled", font="TkFixedFont")
        self.log.grid(row=5, column=0, sticky="ew", pady=(10, 0))
        actions = ttk.Frame(footer)
        actions.grid(row=1, column=0, sticky="ew", pady=(10, 0))
        actions.columnconfigure(0, weight=1)
        ttk.Label(actions, text="Исходники сохраняются. Существующие файлы не заменяются.").grid(row=0, column=0, sticky="w")
        self.cancel_button = ttk.Button(actions, text="Отмена", command=self._cancel_job, state="disabled")
        self.cancel_button.grid(row=0, column=1, padx=(12, 8))
        self.start_button = ttk.Button(actions, text="Создать Opus", command=self._start_job)
        self.start_button.grid(row=0, column=2)

    def _in_form(self, widget: tk.Widget) -> bool:
        return widget is self.form_body or str(widget).startswith(str(self.form_body) + ".")

    def _scroll_form(self, event) -> str | None:
        if not self._in_form(event.widget) and event.widget is not self.form_canvas:
            return None
        if event.widget is self.log:
            return None  # The journal keeps its own wheel scrolling.
        if self.form_canvas.yview() == (0.0, 1.0):
            return None
        delta = -1 if getattr(event, "num", None) == 4 else 1 if getattr(event, "num", None) == 5 else (-1 if event.delta > 0 else 1)
        self.form_canvas.yview_scroll(delta * 3, "units")
        return "break"

    def _show_control(self, widget: tk.Widget) -> None:
        """Bring keyboard-focused form fields into the visible canvas area."""
        if not self._in_form(widget) or not widget.winfo_ismapped():
            return
        self.root.update_idletasks()
        canvas = self.form_canvas
        top = widget.winfo_rooty() - self.form_body.winfo_rooty()
        bottom = top + widget.winfo_height()
        visible_top = canvas.canvasy(0)
        visible_height = canvas.winfo_height()
        requested = None
        # One canvas unit is 20 px; a larger margin prevents quantization from
        # leaving the focused row partly clipped at the viewport edge.
        if top < visible_top + 24:
            requested = max(0, top - 24)
        elif bottom > visible_top + visible_height - 24:
            requested = max(0, bottom - visible_height + 24)
        if requested is not None:
            canvas.yview_moveto(requested / max(1, self.form_body.winfo_height()))

    def _path_row(self, parent, row, label, variable, browse) -> None:
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=(0, 12))
        self._register(ttk.Entry(parent, textvariable=self.values[variable])).grid(row=row, column=1, sticky="ew", pady=4)
        self._register(ttk.Button(parent, text="Обзор…", command=browse)).grid(row=row, column=2, padx=(8, 0), pady=4)

    def _combo(self, parent, row, column, label, variable, options):
        ttk.Label(parent, text=label).grid(row=row, column=column, sticky="w", padx=(14 if column else 0, 10), pady=5)
        widget = self._register(ttk.Combobox(parent, textvariable=self.values[variable], values=options, width=20, state="readonly"), "readonly")
        widget.grid(row=row, column=column + 1, sticky="ew", pady=5)
        return widget

    def _browse_input(self) -> None:
        selected = filedialog.askopenfilename(
            parent=self.root, title="Выберите исходную музыку",
            filetypes=(("Музыка FLAC / MP3 / Opus", "*.flac *.mp3 *.opus *.ogg"), ("Все файлы", "*")),
        )
        if selected:
            self.values["input_path"].set(selected)
            if not self.values["output_path"].get().strip():
                source = Path(selected)
                suffix = ".stereo.opus" if _OUTPUTS[self.values["output_mode"].get()] == "stereo" else ".7.1.opus"
                self.values["output_path"].set(str(source.with_name(source.stem + suffix)))

    def _browse_output(self) -> None:
        current = self.values["output_path"].get().strip()
        options = {}
        if current:
            destination = Path(current).expanduser()
            options = {"initialdir": str(destination.parent), "initialfile": destination.name}
        selected = filedialog.asksaveasfilename(
            parent=self.root, title="Сохранить готовый файл", defaultextension=".opus",
            filetypes=(("Ogg Opus", "*.opus"),), confirmoverwrite=False, **options,
        )
        if selected:
            self.values["output_path"].set(selected)

    def _browse_stems(self) -> None:
        selected = filedialog.askdirectory(parent=self.root, title="Папка дорожек этого трека", mustexist=True)
        if selected:
            self.values["stems_dir"].set(selected)

    def _update_input_states(self) -> None:
        for widget, default in self._inputs:
            widget.configure(state="disabled" if self._running else default)
        ready_stems = self.use_stems.get() and not self._running
        self.stems_entry.configure(state="normal" if ready_stems else "disabled")
        self.stems_browse.configure(state="normal" if ready_stems else "disabled")
        if self.use_stems.get():
            self.model_box.configure(state="disabled")
            self.device_box.configure(state="disabled")
        if self.values["upmix"].get() != "SurroundUpmix / CenterCutCL":
            self.preset_box.configure(state="disabled")
            self.blend_box.configure(state="disabled")
        stereo = _OUTPUTS[self.values["output_mode"].get()] == "stereo"
        if stereo:
            self.surround_frame.grid_remove()
            self.stereo_frame.grid()
        else:
            self.stereo_frame.grid_remove()
            self.surround_frame.grid()
        self.start_button.configure(state="disabled" if self._running else "normal")
        self.cancel_button.configure(state="normal" if self._running and not self._cancel.is_set() else "disabled")

    def _upmix_changed(self, _event=None) -> None:
        self.values["surround_gain_db"].set("0" if self.values["upmix"].get() == "SurroundUpmix / CenterCutCL" else "-6")
        self._update_input_states()

    def _output_changed(self, _event=None) -> None:
        stereo = _OUTPUTS[self.values["output_mode"].get()] == "stereo"
        self.values["bitrate_kbps"].set("256" if stereo else "512")
        source_text = self.values["input_path"].get().strip()
        if source_text:
            source = Path(source_text)
            current = self.values["output_path"].get().strip()
            automatic = {str(source.with_name(source.stem + suffix)) for suffix in (".7.1.opus", ".stereo.opus")}
            if not current or current in automatic:
                self.values["output_path"].set(str(source.with_name(source.stem + (".stereo.opus" if stereo else ".7.1.opus"))))
        self._update_input_states()

    def _stereo_preset(self, neutral: bool) -> None:
        settings = StemStereoSettings.neutral() if neutral else StemStereoSettings()
        for name, control in settings.resolved().items():
            self.values[f"{name}_width"].set(f"{100 * control.width:g}")
            self.values[f"{name}_pan"].set(f"{100 * control.pan:g}")
            self.values[f"{name}_gain"].set(f"{control.gain_db:g}")

    def _append_log(self, message: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", time.strftime("[%H:%M:%S] ") + message.rstrip() + "\n")
        if int(self.log.index("end-1c").split(".")[0]) > 1000:
            self.log.delete("1.0", "200.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _start_job(self) -> None:
        if self._running:
            return
        form = {name: variable.get() for name, variable in self.values.items()}
        form["use_stems"] = self.use_stems.get()
        try:
            config = job_config_from_form(form)
        except (ValueError, OSError) as error:
            messagebox.showerror("Проверьте параметры", str(error), parent=self.root)
            return
        self._cancel = threading.Event()
        self._running = True
        self._last_log_stage = ""
        self._last_log_fraction = -1.0
        self._last_progress = None
        self._last_device = ""
        self._job_started = time.monotonic()
        self.timing_text.set("Прошло: 00:00  ·  До конца этапа: пока неизвестно")
        selected = "CUDA (требуется подтверждение)" if config.device == "cuda" else "CPU"
        self.device_text.set(f"Выбрано: {selected}. Demucs ещё не запускался.")
        self.progressbar.configure(value=0)
        self.status.set("Подготовка обработки…")
        self._append_log(f"Исходник: {config.input_path}\nРезультат: {config.output_path}")
        self._update_input_states()
        threading.Thread(target=self._worker, args=(config, self._cancel), name="upmix-worker", daemon=True).start()

    def _worker(self, config: JobConfig, cancel: threading.Event) -> None:
        # This function and its callback must never access Tk widgets/variables.
        last_stage = ""
        last_time = 0.0

        def emit(event: ProgressEvent) -> None:
            nonlocal last_stage, last_time
            now = time.monotonic()
            if (event.important or event.stage != last_stage or now - last_time >= 0.10
                    or event.stage_fraction == 1.0 or event.fraction == 1.0):
                self._events.put(("progress", event))
                last_stage, last_time = event.stage, now

        try:
            result = run_job(config, progress=emit, cancel=cancel)
        except CancelledError as error:
            self._events.put(("cancelled", str(error)))
        except Exception as error:
            self._events.put(("error", str(error) or type(error).__name__))
        else:
            self._events.put(("done", result))

    def _cancel_job(self) -> None:
        if self._running and not self._cancel.is_set():
            self._cancel.set()
            self.status.set("Отмена… Ожидается остановка обработки.")
            self.cancel_button.configure(state="disabled")
            self._append_log("Запрошена отмена.")

    def _handle_progress(self, event: ProgressEvent) -> None:
        self._last_progress = event
        self._progress_received = time.monotonic()
        if not self._cancel.is_set():
            self.status.set(event.message)
        self.timing_text.set(timing(event))
        device = f"Сейчас: {event.device}. Demucs: {event.demucs or 'ещё не запускался'}."
        self.device_text.set(device)
        fraction = event.stage_fraction
        if fraction is None:
            if not self._indeterminate:
                self.progressbar.configure(mode="indeterminate")
                self.progressbar.start(15)
                self._indeterminate = True
        else:
            self.progressbar.stop()
            self._indeterminate = False
            self.progressbar.configure(mode="determinate", value=max(0, min(1, fraction)) * 100)
        if event.important or event.stage != self._last_log_stage or (
            fraction is not None and fraction - self._last_log_fraction >= 0.10
        ):
            self._append_log(f"{event.message}\n{timing(event)}")
            if device != self._last_device:
                self._append_log(device)
                self._last_device = device
            self._last_log_stage = event.stage
            self._last_log_fraction = fraction if fraction is not None else -1.0

    def _finish(self, kind: str, payload: object) -> None:
        self._running = False
        self.progressbar.stop()
        self._indeterminate = False
        self.progressbar.configure(mode="determinate")
        self.timing_text.set(f"Прошло: {duration(time.monotonic() - self._job_started)}")
        if self._last_progress:
            self.device_text.set(f"Demucs: {self._last_progress.demucs or 'вычисление не подтверждено'}.")
        if kind == "done":
            self.progressbar.configure(value=100)
            self.status.set("Готово. Файл Opus сохранён.")
            self._append_log(f"Готово: {payload}")
        elif kind == "cancelled":
            self.status.set("Обработка отменена.")
            self._append_log(str(payload))
        else:
            self.status.set("Не удалось завершить обработку. Подробности в журнале.")
            self._append_log(f"Ошибка: {payload}")
        if self._closing:
            self.root.destroy()
            return
        self._update_input_states()
        if kind == "error":
            message = str(payload)
            if len(message) > 1800:
                message = message[:1800] + "\n… Полный текст находится в журнале."
            messagebox.showerror("Ошибка обработки", message, parent=self.root)

    def _poll(self) -> None:
        self._poll_id = None
        for _ in range(200):
            try:
                kind, payload = self._events.get_nowait()
            except queue.Empty:
                break
            if kind == "progress":
                self._handle_progress(payload)
            else:
                self._finish(kind, payload)
                if self._closing:
                    return
        if self._running and self._last_progress:
            now = time.monotonic()
            text = timing(self._last_progress, now - self._progress_received)
            if self._cancel.is_set():
                text = f"Прошло: {duration(now - self._job_started)}  ·  Остановка процесса…"
            if self.timing_text.get() != text:
                self.timing_text.set(text)
        self._poll_id = self.root.after(100, self._poll)

    def _request_close(self) -> None:
        if self._running:
            self._closing = True
            self._cancel_job()
            self.status.set("Закрытие… Ожидается остановка обработки.")
        else:
            if self._poll_id is not None:
                self.root.after_cancel(self._poll_id)
            self.root.destroy()


def main() -> int:
    try:
        root = tk.Tk()
    except tk.TclError as error:
        print(f"Не удалось открыть окно. Запустите приложение в графическом сеансе Kubuntu.\n{error}", file=sys.stderr)
        return 2
    UpmixerWindow(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
