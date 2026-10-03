# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""File processing and lossless intermediate stages for the desktop upmixer."""

from __future__ import annotations

from contextlib import ExitStack
from dataclasses import dataclass
import errno
import codecs
import hashlib
import importlib.metadata
import importlib.util
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
from typing import Callable

import numpy as np
import soundfile as sf

from . import __version__
from .dsp import MixSettings, SurroundMixer
from .surroundupmix import CascadeSettings, SurroundUpmixMixer, analyze_files
from .stem_stereo import StemStereoMixer, StemStereoSettings
from .progress import ProgressEvent, ProgressReporter
from .demucs_worker import PREFIX

SAMPLE_RATE = 48000
REQUIRED_STEMS = ("bass", "drums", "vocals", "other")
OPTIONAL_STEMS = ("guitar", "piano")


class CancelledError(Exception):
    """The user cancelled; the destination is not published."""


@dataclass(frozen=True)
class JobConfig:
    input_path: Path
    output_path: Path
    stems_dir: Path | None = None
    model: str = "htdemucs_ft"
    device: str = "auto"
    bitrate_kbps: int | None = None
    surround_gain_db: float = -6.0
    lfe_gain_db: float = -10.0
    overwrite: bool = False
    cache_dir: Path | None = None
    upmix: str = "soft"
    cascade_preset: str = "Music"
    cascade_blend: str = "classic"
    stem_routes: tuple[tuple[str, str], ...] = ()
    output_mode: str = "surround"
    stereo_settings: StemStereoSettings = StemStereoSettings()

    @property
    def effective_bitrate_kbps(self) -> int:
        return self.bitrate_kbps if self.bitrate_kbps is not None else (256 if self.output_mode == "stereo" else 512)


def _check_cancel(cancel: threading.Event) -> None:
    if cancel.is_set():
        raise CancelledError("Обработка отменена.")


def _publish(encoded: Path, output: Path, overwrite: bool, cancel: threading.Event,
             progress: Callable[[float], None] | None = None) -> None:
    _check_cancel(cancel)
    if overwrite:
        os.replace(encoded, output)
        return
    try:
        # Atomic publication on filesystems with hard-link support.
        os.link(encoded, output)
    except OSError as error:
        if error.errno not in (errno.EPERM, errno.EOPNOTSUPP, errno.ENOSYS, errno.EXDEV):
            raise
        # FAT/exFAT cannot create hard links. Exclusive creation still prevents
        # overwriting another export; remove our partial copy on failure/cancel.
        with output.open("xb") as target:
            identity = os.fstat(target.fileno())
            try:
                with encoded.open("rb") as source:
                    copied, total = 0, encoded.stat().st_size
                    while block := source.read(1024 * 1024):
                        _check_cancel(cancel)
                        target.write(block)
                        copied += len(block)
                        if progress:
                            progress(copied / max(1, total))
                _check_cancel(cancel)
                target.flush()
                os.fsync(target.fileno())
            except BaseException:
                target.close()
                try:
                    current = output.stat(follow_symlinks=False)
                    if (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                        output.unlink()
                except FileNotFoundError:
                    pass
                raise
    encoded.unlink()


def _run(args: list[str], cancel: threading.Event, *, env=None, on_line=None) -> str:
    """No shell; spool child output to disk so logs cannot block the child."""
    _check_cancel(cancel)
    with tempfile.TemporaryFile(mode="w+b") as log:
        child = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=log,
                                 stderr=subprocess.STDOUT, env=env,
                                 start_new_session=True)
        offset = 0
        pending = ""
        decoder = codecs.getincrementaldecoder("utf-8")("replace")

        def drain(final=False):
            nonlocal offset, pending
            if on_line is None:
                return
            while data := os.pread(log.fileno(), 65536, offset):
                offset += len(data)
                pending += decoder.decode(data).replace("\r", "\n")
                lines = pending.split("\n")
                pending = lines.pop()[-16384:]
                for line in lines:
                    if line:
                        on_line(line[-16384:])
                _check_cancel(cancel)
            if final:
                pending += decoder.decode(b"", final=True)
                if pending:
                    on_line(pending)
                pending = ""
        try:
            while child.poll() is None:
                drain()
                if cancel.wait(0.1):
                    raise CancelledError("Обработка отменена.")
            drain(final=True)
        except BaseException:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
            raise
        log.seek(0, os.SEEK_END)
        size = log.tell()
        log.seek(max(0, size - 65536))
        output = log.read().decode("utf-8", errors="replace")
    _check_cancel(cancel)
    if child.returncode:
        raise RuntimeError(f"{Path(args[0]).name}: ошибка {child.returncode}\n{output[-6000:]}")
    return output


def _ffmpeg(args: list[str], cancel: threading.Event, seconds: float | None,
            progress: Callable[[float | None], None] | None = None) -> None:
    def line(value):
        key, _, data = value.partition("=")
        if key == "out_time_us" and seconds and progress:
            try:
                position = int(data) / 1_000_000
            except ValueError:
                return
            progress(max(0., min(.999, position / seconds)))
    _run([args[0], "-nostats", "-stats_period", "0.25", "-progress", "pipe:1", *args[1:]],
         cancel, on_line=line)
    if progress:
        progress(1.)


def probe(path: Path, cancel: threading.Event | None = None) -> dict:
    event = cancel or threading.Event()
    output = _run(["ffprobe", "-v", "error", "-select_streams", "a:0",
                   "-show_entries", "stream=codec_name,channels,channel_layout,sample_rate,duration:format=duration:stream_tags:format_tags",
                   "-of", "json", str(path)], event)
    info = json.loads(output)
    if not info.get("streams"):
        raise ValueError(f"В файле нет аудиодорожки: {path.name}")
    return info


def _decode(source: Path, destination: Path, cancel: threading.Event,
            sample_rate: int | None = SAMPLE_RATE, *, seconds: float | None = None,
            progress: Callable[[float | None], None] | None = None) -> None:
    args = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-i", str(source), "-map", "0:a:0", "-vn", "-ac", "2"]
    if sample_rate is not None:
        args += ["-ar", str(sample_rate)]
    _ffmpeg(args + ["-c:a", "pcm_f32le", "-rf64", "auto", str(destination)], cancel, seconds, progress)


def _find_stems(folder: Path) -> dict[str, Path]:
    found = {}
    for name in REQUIRED_STEMS + OPTIONAL_STEMS:
        candidates = [p for p in folder.iterdir() if p.is_file()
                      and p.stem.lower() == name and p.suffix.lower() in (".wav", ".flac")]
        if len(candidates) > 1:
            raise ValueError(f"В папке несколько дорожек {name}; оставьте одну WAV или FLAC.")
        if candidates:
            found[name] = candidates[0]
    missing = set(REQUIRED_STEMS) - found.keys()
    if missing:
        raise ValueError("Не найдены дорожки Demucs: " + ", ".join(sorted(missing)))
    return found


def _separate(config: JobConfig, work: Path, cancel: threading.Event,
              emit: Callable[[ProgressEvent], None]) -> dict[str, Path]:
    if config.stems_dir is not None:
        emit(ProgressEvent("separate_skip", .25, "Используются выбранные готовые дорожки; Demucs не запускается.",
                           stage_fraction=1., demucs="Готовые дорожки · CPU/CUDA для нейросети не используются", important=True))
        return _find_stems(Path(config.stems_dir).expanduser().resolve())
    if importlib.util.find_spec("demucs") is None:
        raise RuntimeError("Demucs не установлен. Установите пакет с дополнением [demucs] "
                           "или выберите папку готовых дорожек.")
    version = importlib.metadata.version("demucs")
    cache = Path(config.cache_dir or Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache")))
                 / "spatial-upmixer").expanduser().resolve()
    digest = hashlib.sha256(f"{config.model}|{version}|float32-no-rescale-v1".encode())
    size, read = config.input_path.stat().st_size, 0
    emit(ProgressEvent("cache_check", None, "Проверка кэша: чтение отпечатка исходного файла…", stage_fraction=0., device="CPU · диск"))
    with config.input_path.open("rb") as src:
        while block := src.read(1024 * 1024):
            _check_cancel(cancel)
            digest.update(block)
            read += len(block)
            emit(ProgressEvent("cache_check", None, f"Проверка кэша: {read / 2**20:.1f} / {size / 2**20:.1f} МиБ",
                               stage_fraction=read / max(1, size), device="CPU · диск"))
    key = digest.hexdigest()
    cache.mkdir(parents=True, exist_ok=True)
    cached = cache / "stems" / key
    if (cached / "complete.json").is_file():
        emit(ProgressEvent("separate_skip", .25, "Используются сохранённые дорожки Demucs; нового вычисления нет.",
                           stage_fraction=1., demucs="Кэш · Demucs сейчас не запускался, CUDA не используется", important=True))
        return _find_stems(cached)
    separator_input = work / "demucs_input.wav"
    emit(ProgressEvent("separate_decode", None, "Подготовка исходника для Demucs…", stage_fraction=0., device="CPU · FFmpeg"))
    seconds = sf.info(work / "original.wav").duration
    _decode(config.input_path, separator_input, cancel, sample_rate=None, seconds=seconds,
            progress=lambda f: emit(ProgressEvent("separate_decode", None, "Подготовка исходника для Demucs…",
                                                   stage_fraction=f, device="CPU · FFmpeg")))
    environment = os.environ.copy()
    environment["HF_HOME"] = str(cache / "models" / "huggingface")
    environment["TORCH_HOME"] = str(cache / "models" / "torch")
    destination = work / "separated" / config.model / "demucs_input"
    latest = ProgressEvent("separate_load", None, "Запуск Demucs…")

    def worker_line(line):
        nonlocal latest
        if line.startswith(PREFIX):
            event = json.loads(line[len(PREFIX):])
            latest = ProgressEvent(stage=event["stage"], fraction=None, message=event["message"],
                                   stage_fraction=event.get("stage_fraction"), device=event.get("device", "CPU"),
                                   demucs=event.get("demucs"), important=event.get("important", False))
            emit(latest)
        elif line.strip():
            # Downloads and library diagnostics stay visible, even before the
            # first completed segment can provide a meaningful ETA.
            emit(ProgressEvent(latest.stage, None, "Demucs: " + line[-500:],
                               stage_fraction=latest.stage_fraction, device=latest.device))

    _run([sys.executable, "-u", "-m", "spatial_upmixer.demucs_worker", "--model", config.model,
          "--device", config.device, str(separator_input), str(destination)],
         cancel, env=environment, on_line=worker_line)
    proof = json.loads((destination / "execution.json").read_text(encoding="utf-8"))
    if not proof.get("verified_forwards") or not all(n > 0 for n in proof["verified_forwards"]):
        raise RuntimeError("Demucs не подтвердил выполнение моделей.")
    if config.device == "cuda" and not proof["device"].startswith("cuda:"):
        raise RuntimeError("Выбрана CUDA, но устройство вычисления не подтверждено. Результат отклонён.")
    stems = _find_stems(destination)
    cached.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".stems-", dir=cached.parent) as staging:
        stage = Path(staging) / "ready"
        stage.mkdir()
        total_bytes = sum(source.stat().st_size for source in stems.values())
        copied = 0
        emit(ProgressEvent("cache_write", None, "Сохранение дорожек в кэш…", stage_fraction=0., device="CPU · диск"))
        for name, source in stems.items():
            _check_cancel(cancel)
            target = stage / f"{name}{source.suffix}"
            with source.open("rb") as incoming, target.open("wb") as outgoing:
                while block := incoming.read(1024 * 1024):
                    _check_cancel(cancel)
                    outgoing.write(block)
                    copied += len(block)
                    emit(ProgressEvent("cache_write", None, f"Запись в кэш: {name}",
                                       stage_fraction=copied / max(1, total_bytes), device="CPU · диск"))
            shutil.copystat(source, target)
        (stage / "complete.json").write_text(json.dumps({"model": config.model, "demucs": version,
                                                         "execution": proof}), encoding="utf-8")
        try:
            stage.rename(cached)
        except OSError:
            # A second process may have populated the same cache meanwhile.
            if not (cached / "complete.json").is_file():
                raise
    return _find_stems(cached)


def _validate(config: JobConfig) -> tuple[Path, Path]:
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            raise RuntimeError(f"Не найден {tool}. Установите FFmpeg.")
    source = Path(config.input_path).expanduser().resolve()
    output = Path(config.output_path).expanduser().resolve()
    if not source.is_file():
        raise ValueError("Выберите существующий исходный аудиофайл.")
    if source.suffix.lower() not in (".flac", ".mp3", ".opus", ".ogg"):
        raise ValueError("Поддерживаются исходники FLAC, MP3 и Ogg Opus.")
    if output.suffix.lower() != ".opus":
        raise ValueError("Выходной файл должен иметь расширение .opus.")
    if source == output:
        raise ValueError("Выходной файл не может заменять исходный.")
    if output.exists() and not config.overwrite:
        raise FileExistsError(f"Файл уже существует: {output}")
    if config.model not in ("htdemucs", "htdemucs_ft", "htdemucs_6s"):
        raise ValueError("Неизвестная модель Demucs.")
    if config.device not in ("auto", "cpu", "cuda"):
        raise ValueError("Устройство: auto, cpu или cuda.")
    if config.upmix not in ("soft", "surroundupmix"):
        raise ValueError("Неизвестный алгоритм upmix.")
    if config.output_mode not in ("surround", "stereo"):
        raise ValueError("Режим файла: surround или stereo.")
    if not isinstance(config.stereo_settings, StemStereoSettings):
        raise ValueError("Некорректные настройки расширения стемов.")
    CascadeSettings(config.cascade_preset, config.cascade_blend, config.stem_routes)
    bitrate = config.effective_bitrate_kbps
    if isinstance(bitrate, bool) or not isinstance(bitrate, int) or not 128 <= bitrate <= 1024:
        raise ValueError("Битрейт всего файла: от 128 до 1024 кбит/с.")
    for label, value in (("Surround", config.surround_gain_db), ("LFE", config.lfe_gain_db)):
        if not math.isfinite(value) or not -60 <= value <= 6:
            raise ValueError(f"{label}: уровень должен быть от −60 до +6 дБ.")
    return source, output


def run_job(config: JobConfig, progress: Callable[[ProgressEvent], None] | None = None,
            cancel: threading.Event | None = None) -> Path:
    cancel = cancel or threading.Event()
    emit = ProgressReporter(progress or (lambda event: None))
    source, output = _validate(config)
    config = JobConfig(**{**config.__dict__, "input_path": source, "output_path": output})
    _check_cancel(cancel)
    emit(ProgressEvent("probe", None, "Проверка исходного аудиофайла…", device="CPU · FFprobe", important=True))
    input_info = probe(source, cancel)
    stream = input_info["streams"][0]
    if stream.get("codec_name") not in ("flac", "mp3", "opus"):
        raise ValueError("Внутри файла должен быть звук FLAC, MP3 или Opus.")
    if stream["channels"] not in (1, 2):
        raise ValueError("Для разделения нужен моно- или стереоисточник; готовый surround повторно не обрабатывается.")
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        seconds = float(stream.get("duration") or input_info.get("format", {}).get("duration", 0))
        if not math.isfinite(seconds) or seconds <= 0:
            seconds = None
    except (ValueError, TypeError):
        seconds = None
    with tempfile.TemporaryDirectory(prefix=".spatial-upmix-", dir=output.parent) as folder:
        work = Path(folder)
        emit(ProgressEvent("decode", 0.02, "Декодирование исходного файла…", stage_fraction=0. if seconds else None,
                           device="CPU · FFmpeg"))
        original_path = work / "original.wav"
        _decode(source, original_path, cancel, seconds=seconds,
                progress=lambda f: emit(ProgressEvent("decode", .02, "Декодирование исходного файла…",
                                                      stage_fraction=f, device="CPU · FFmpeg")))
        stems = _separate(config, work, cancel, emit)
        prepared = {}
        seconds = sf.info(original_path).duration
        for index, (name, stem) in enumerate(stems.items()):
            def preparing(f, index=index, name=name):
                emit(ProgressEvent("prepare", .3 + index / len(stems) * .1,
                                   f"Подготовка {index + 1}/{len(stems)}: {name} → стерео 48 кГц",
                                   stage_fraction=(index + (f or 0.)) / len(stems), device="CPU · FFmpeg"))
            preparing(0.)
            prepared[name] = work / f"stem-{name}.wav"
            _decode(stem, prepared[name], cancel, seconds=sf.info(stem).duration, progress=preparing)
        mix_settings = MixSettings(sample_rate=SAMPLE_RATE,
                                   surround_gain_db=config.surround_gain_db,
                                   lfe_gain_db=config.lfe_gain_db)
        stereo_output = config.output_mode == "stereo"
        channels, layout, mapping_family = (2, "stereo", 0) if stereo_output else (8, "7.1", 1)
        bitrate = config.effective_bitrate_kbps
        if stereo_output:
            emit(ProgressEvent("analysis", .4, "Стерео по стемам: ширина, панорама и уровень; остаток Demucs сохраняется."))
            mixer = StemStereoMixer(config.stereo_settings)
        elif config.upmix == "surroundupmix":
            emit(ProgressEvent("analysis", .4, "SurroundUpmix: анализ ширины, яркости и энергии дорожек…", stage_fraction=0.))
            analysis = analyze_files(prepared, config.cascade_preset, lambda: _check_cancel(cancel),
                                     lambda f: emit(ProgressEvent("analysis", .4,
                                         f"Анализ ширины, яркости и энергии дорожек: {f:.0%}", stage_fraction=f)))
            emit(ProgressEvent("analysis", .4,
                               f"Ширина {analysis.width:.3f}; яркость {analysis.brightness:.3f}; "
                               f"центр {analysis.center_db:+.1f} дБ; ВЧ окружения {analysis.surround_db:+.1f} дБ.",
                               stage_fraction=1., important=True))
            mixer = SurroundUpmixMixer(mix_settings,
                CascadeSettings(config.cascade_preset, config.cascade_blend, config.stem_routes), analysis)
        else:
            mixer = SurroundMixer(mix_settings)
        raw = work / "mix.f32"
        peak = 0.0
        written = 0
        skip = mixer.latency_samples
        emit(ProgressEvent("mix", .4, f"Сборка {layout}: начало…", stage_fraction=0., device="CPU · микшер"))
        with ExitStack() as stack:
            original = stack.enter_context(sf.SoundFile(original_path))
            inputs = {name: stack.enter_context(sf.SoundFile(path)) for name, path in prepared.items()}
            frames = len(original)
            if frames == 0:
                raise ValueError("Исходный файл пуст.")
            for name, track in inputs.items():
                if abs(len(track) - frames) > 48:
                    raise ValueError(f"Дорожка {name} не совпадает по длительности с исходником. "
                                     "Выберите дорожки того же трека без обрезки и сдвига.")
            target = stack.enter_context(raw.open("wb"))

            def write_chunk(chunk: np.ndarray) -> None:
                nonlocal skip, written, peak
                if chunk.ndim != 2 or chunk.shape[1] != channels or not np.isfinite(chunk).all():
                    raise ValueError(f"Обработчик вернул некорректный звук: ожидается {channels} каналов.")
                removed = min(skip, len(chunk))
                skip -= removed
                chunk = chunk[removed:removed + max(0, frames - written)]
                if len(chunk):
                    peak = max(peak, float(np.max(np.abs(chunk))))
                    target.write(chunk.astype("<f4", copy=False).tobytes())
                    written += len(chunk)

            read = 0
            while read < frames:
                _check_cancel(cancel)
                chunk = original.read(min(8192, frames - read), dtype="float32", always_2d=True)
                blocks = {}
                for name, track in inputs.items():
                    block = track.read(len(chunk), dtype="float32", always_2d=True)
                    if len(block) < len(chunk):
                        block = np.pad(block, ((0, len(chunk) - len(block)), (0, 0)))
                    blocks[name] = block
                write_chunk(mixer.process(blocks, chunk))
                read += len(chunk)
                emit(ProgressEvent("mix", 0.4 + read / frames * 0.4,
                                   f"Сборка {layout}: {read / frames:.0%} · {read / SAMPLE_RATE:.1f} / {frames / SAMPLE_RATE:.1f} с",
                                   stage_fraction=read / frames, device="CPU · микшер"))
            write_chunk(mixer.flush())
            if written != frames:
                raise RuntimeError(f"Нарушена длительность: {written} вместо {frames} отсчётов.")
        # Original SurroundUpmix restores its internal working headroom through
        # final peak normalization. Keep a 2 dB Opus margin and one linked gain.
        gain = 10 ** (-2.0 / 20) / peak if peak else 1.0
        if stereo_output or config.upmix == "soft":
            gain = min(1.0, gain)
        emit(ProgressEvent("encode", 0.82,
                           f"Кодирование Opus {layout}, {bitrate} кбит/с; "
                           f"защита пиков {20 * math.log10(gain):+.2f} дБ…", stage_fraction=0., device="CPU · libopus"))
        encoded = work / "result.opus"
        args = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "f32le", "-ar", str(SAMPLE_RATE), "-ac", str(channels), "-channel_layout", layout,
                "-i", str(raw), "-af", f"volume={gain:.10f}", "-c:a", "libopus",
                "-application", "audio", "-mapping_family", str(mapping_family), "-b:a", f"{bitrate}k",
                "-vbr", "on", "-compression_level", "10", "-frame_duration", "20"]
        # Ogg Opus comments are stream metadata, while formats such as MP3
        # commonly expose global tags. Prefer the selected audio stream and
        # normalize case so TITLE/title cannot produce duplicate assignments.
        tags = {
            key.lower(): value
            for source_tags in (input_info.get("format", {}).get("tags", {}),
                                stream.get("tags", {}))
            for key, value in source_tags.items()
        }
        for key, value in tags.items():
            if key.lower() in ("title", "artist", "album", "album_artist", "track", "disc", "date", "genre"):
                args += ["-metadata", f"{key}={value}"]
        args += ["-metadata", f"SPATIAL_UPMIXER_LAYOUT={layout}",
                 "-metadata", "SPATIAL_UPMIXER_RENDERING=channel_mix_no_hrtf",
                 "-metadata", f"SPATIAL_UPMIXER_ALGORITHM={'stem_stereo' if stereo_output else config.upmix}",
                 "-metadata", f"SPATIAL_UPMIXER_PRESET={config.cascade_preset if not stereo_output and config.upmix == 'surroundupmix' else 'none'}",
                 "-metadata", f"SPATIAL_UPMIXER_CHANNELS={channels}",
                 "-metadata", f"SPATIAL_UPMIXER_MAPPING_FAMILY={mapping_family}",
                 "-metadata", f"SPATIAL_UPMIXER_FRAMES={frames}",
                 "-metadata", f"SPATIAL_UPMIXER_PEAK_GAIN={gain:.10f}",
                 "-metadata", f"SPATIAL_UPMIXER_VERSION={__version__}"]
        if stereo_output:
            controls = {name: {"width": float(value.width), "pan": float(value.pan), "gain_db": float(value.gain_db)}
                        for name, value in config.stereo_settings.resolved().items() if name in prepared}
            args += ["-metadata", "SPATIAL_UPMIXER_STEMS=" + json.dumps(controls, separators=(",", ":")),
                     "-metadata", "SPATIAL_UPMIXER_RESIDUAL=original_minus_stems_preserved"]
        args += [str(encoded)]
        _ffmpeg(args, cancel, frames / SAMPLE_RATE,
                lambda f: emit(ProgressEvent("encode", .82,
                                            f"Кодирование Opus {layout}, {bitrate} кбит/с: {f:.0%}",
                                            stage_fraction=f, device="CPU · libopus")))
        emit(ProgressEvent("verify", None, "Проверка готового Opus: кодек, частота и карта каналов…", device="CPU · FFprobe"))
        result = probe(encoded, cancel)["streams"][0]
        if result.get("codec_name") != "opus" or result.get("channels") != channels or int(result["sample_rate"]) != SAMPLE_RATE:
            raise RuntimeError(f"Выходной файл не прошёл проверку Opus {layout} / 48 кГц.")
        with encoded.open("rb") as check:
            header = check.read(256)
        head = header.find(b"OpusHead")
        if head < 0 or len(header) < head + 19 or header[head + 9] != channels or header[head + 18] != mapping_family:
            raise RuntimeError("Выходной файл содержит неверную карту каналов Opus.")
        _check_cancel(cancel)
        emit(ProgressEvent("publish", None, "Сохранение готового файла…", stage_fraction=0., device="CPU · диск"))
        _publish(encoded, output, config.overwrite, cancel,
                 lambda f: emit(ProgressEvent("publish", None, f"Сохранение готового файла: {f:.0%}",
                                              stage_fraction=f, device="CPU · диск")))
        emit(ProgressEvent("done", 1.0, f"Готово: {output}", stage_fraction=1., important=True))
    return output
