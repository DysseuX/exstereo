# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Bounded-memory port of SurroundUpmix v3.1's complete 7.1 audio routing.

Reference URL and SHA-256 are recorded in third_party/sources.json. The
upstream PowerShell script is not bundled or executed. CenterCutCL/SoX file
stages become aligned float64 streams without intermediate PCM quantization.
"""

from __future__ import annotations

from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping

import numpy as np
from scipy.signal import butter, sosfilt
import soundfile as sf

from .centercut import CenterCut, WINDOW_SIZE
from .dsp import MixSettings, REQUIRED_STEMS, OPTIONAL_STEMS, _SampleDelay


ROUTES = ("4.0", "5.0", "mono", "front", "rear")
DEFAULT_ROUTES = {"bass": "4.0", "drums": "4.0", "vocals": "5.0", "other": "5.0",
                  "guitar": "4.0", "piano": "4.0"}
# delay L/R, crossover, high-band blend dB, center boost dB, front shelf dB
PRESETS = {"none": (0., 0., 4000., -8., 0., 0.),
           "Music": (.015, .017, 4000., -8., 0., -4.),
           "Movie": (.010, .012, 2500., -6., 1., -5.),
           "Anime": (.012, .014, 3000., -7., 2., -4.),
           "PLIIx": (.020, .023, 3500., -6., 0., -3.)}


def db(value: float) -> float:
    return 10.0 ** (value / 20.0)


@dataclass(frozen=True)
class CascadeSettings:
    preset: str = "Music"
    blend: str = "classic"
    routes: tuple[tuple[str, str], ...] = tuple(DEFAULT_ROUTES.items())

    def __post_init__(self) -> None:
        if self.preset not in PRESETS or self.blend not in ("classic", "full"):
            raise ValueError("Неизвестный пресет или режим смешивания SurroundUpmix.")
        if len(dict(self.routes)) != len(self.routes):
            raise ValueError("Маршрут одной дорожки задан несколько раз.")
        for name, route in self.routes:
            if name not in DEFAULT_ROUTES or route not in ROUTES:
                raise ValueError(f"Недопустимый маршрут {name}={route}.")


@dataclass(frozen=True)
class Analysis:
    width: float = .5
    brightness: float = .5
    center_db: float = 2.
    surround_db: float = 0.
    delay_l: float = .015
    delay_r: float = .017


def analyze_files(paths: Mapping[str, Path], preset: str,
                  check_cancel: Callable[[], None], progress: Callable[[float], None] | None = None) -> Analysis:
    """Original v3.1 full-track RMS analysis, with bounded memory and cancellation."""
    delay_l, delay_r, hz, _, boost, _ = PRESETS[preset]
    if preset == "none":
        return Analysis(center_db=2., delay_l=0., delay_r=0.)
    hp = np.vstack([butter(2, hz, btype="highpass", fs=48000, output="sos")] * 2)
    state = np.zeros((len(hp), 2, 2))
    energies = {name: 0. for name in paths}
    mid_energy = side_energy = full_energy = high_energy = 0.
    count = 0
    with ExitStack() as stack:
        tracks = {name: stack.enter_context(sf.SoundFile(path)) for name, path in paths.items()}
        if any(track.samplerate != 48000 for track in tracks.values()):
            raise ValueError("Анализ ожидает подготовленные дорожки 48 кГц.")
        max_frames = max(len(track) for track in tracks.values())
        for offset in range(0, max_frames, 8192):
            check_cancel()
            n = min(8192, max_frames - offset)
            reconstructed = np.zeros((n, 2))
            for name, track in tracks.items():
                data = track.read(n, dtype="float64", always_2d=True)
                if data.shape[1] != 2 or not np.isfinite(data).all():
                    raise ValueError(f"Некорректная дорожка {name}.")
                energies[name] += float(np.sum(data * data))
                reconstructed[:len(data)] += data / len(tracks)
            mid = reconstructed.mean(axis=1)
            side = (reconstructed[:, 0] - reconstructed[:, 1]) * .5
            high, state = sosfilt(hp, reconstructed, axis=0, zi=state)
            mid_energy += float(mid @ mid)
            side_energy += float(side @ side)
            full_energy += float(np.sum(reconstructed * reconstructed))
            high_energy += float(np.sum(high * high))
            count += n
            if progress:
                progress(count / max_frames)
    if not count:
        raise ValueError("Дорожки Demucs пусты.")
    mid_rms, side_rms = np.sqrt([mid_energy / count, side_energy / count])
    width_raw = .5 if (mid_rms + side_rms) / 2 < .001 or mid_rms < .0001 else round(side_rms / mid_rms, 3)
    width = float(np.sqrt(min(1.5, width_raw)))
    center = max(-4., -4. * ((width - .9) / .5)) if width > .9 else min(3., 3. * ((.3 - width) / .3)) if width < .3 else 0.
    brightness = .5 if np.sqrt(full_energy / (count * 2)) < .001 else round(float(np.sqrt(high_energy / full_energy)), 3)
    voice = np.sqrt(energies.get("vocals", 0.) / (count * 2))
    accompaniment = np.sqrt(sum(energies.get(name, 0.) for name in ("other", "guitar", "piano")) / (count * 2))
    adjustment = 0.
    if voice > 0 and accompaniment > 0:
        ratio = voice / accompaniment
        adjustment = max(-3., -3. * ((ratio - 1.5) / 1.5)) if ratio > 1.5 else min(3., 3. * ((.5 - ratio) / .5)) if ratio < .5 else 0.
    surround = round(round(adjustment, 1) + np.clip(round((brightness - .5) * 4., 1), -2., 2.), 1)
    width_factor = np.clip(width, 0., 1.)
    return Analysis(width, brightness, 2. + boost + round(center, 1), float(surround),
                    round(delay_l * (.7 + width_factor * .6), 4),
                    round(delay_r * (.7 + width_factor * .6), 4))


def _allpass(hz: float, rate: int) -> np.ndarray:
    # v3.1 passes "allpass <frequency> 1.0" without a unit. SoX 14.4.2
    # allpass_getopts defaults to Hz ("hkqo"), not Q. Preserve that narrow
    # one-Hz bandwidth; using Q=1 would silently change the original preset.
    w = 2 * np.pi * hz / rate
    alpha = np.sin(w) / (2 * hz)
    a = np.array([1 + alpha, -2 * np.cos(w), 1 - alpha])
    return np.array([[(1 - alpha) / a[0], a[1] / a[0], 1., 1., a[1] / a[0], a[2] / a[0]]])


def _high_shelf(hz: float, gain_db: float, rate: int) -> np.ndarray:
    # SoX treble defaults to shelf slope S=0.5 (not Q=0.707).
    a = 10 ** (gain_db / 40)
    w = 2 * np.pi * hz / rate
    c, s = np.cos(w), np.sin(w)
    alpha = s / 2 * np.sqrt((a + 1 / a) * (1 / .5 - 1) + 2)
    t = 2 * np.sqrt(a) * alpha
    b = a * np.array([(a + 1) + (a - 1) * c + t, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - t])
    denominator = np.array([(a + 1) - (a - 1) * c + t, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - t])
    return np.array([np.concatenate((b, denominator)) / denominator[0]])


class _Filter:
    def __init__(self, sos: np.ndarray, channels: int) -> None:
        self.sos = sos
        self.state = np.zeros((len(sos), 2, channels))

    def process(self, values: np.ndarray) -> np.ndarray:
        if not len(values):
            return values.copy()
        result, self.state = sosfilt(self.sos, values, axis=0, zi=self.state)
        return result


class _Group:
    def __init__(self, method: str, mix: MixSettings, settings: CascadeSettings, analysis: Analysis) -> None:
        self.method, self.settings, self.analysis = method, settings, analysis
        self.cc = [CenterCut() for _ in range(5)] if method in ("4.0", "5.0") else []
        self.original_delay = _SampleDelay(3 * WINDOW_SIZE, 2)
        self.first_delay = _SampleDelay(2 * WINDOW_SIZE, 3)
        self.second_delay = _SampleDelay(WINDOW_SIZE, 2)
        self.surround_gain = db(mix.surround_gain_db)
        self.working_gain = db(-6.02)
        _, _, hz, self.high_db, _, shelf_db = PRESETS[settings.preset]
        hz = min(hz, mix.sample_rate * .45)
        self.high = _Filter(np.vstack([butter(2, hz, btype="highpass", fs=mix.sample_rate, output="sos")] * 2), 2)
        self.shelf = _Filter(_high_shelf(hz, shelf_db, mix.sample_rate), 2)
        delays = (analysis.delay_l, analysis.delay_r, analysis.delay_l * .6, analysis.delay_r * .6)
        centers = ((200, 3000), (180, 2800), (240, 3400), (160, 2600))
        # Original Format-Inv "0.0###" rounds seconds before SoX sees them.
        self.delays = [_SampleDelay(round(round(delay, 4) * mix.sample_rate), 1) for delay in delays]
        self.allpass = [_Filter(np.vstack([_allpass(f, mix.sample_rate) for f in pair]), 1) for pair in centers]

    def process(self, source: np.ndarray) -> np.ndarray:
        source = source * self.working_gain
        original = self.original_delay.process(source)
        result = np.zeros((len(source), 8))
        if not self.cc:
            if self.method == "mono":
                result[:, 2] = original.mean(axis=1) * db(3.01)
            elif self.method == "front":
                result[:, :2] = original
            else:
                result[:, 4:6] = original * self.surround_gain
            return result
        first = self.cc[0].process(source)
        second_l = self.cc[1].process(first[:, [0, 2]])
        second_r = self.cc[2].process(first[:, [2, 1]])
        third_l = self.cc[3].process(second_l[:, [2, 1]])[:, 2]
        third_r = self.cc[4].process(second_r[:, [0, 2]])[:, 2]
        first = self.first_delay.process(first)
        second = self.second_delay.process(np.column_stack((second_l[:, 2], second_r[:, 2])))
        thirds = np.column_stack((third_l, third_r))
        front = (second * (2 / 3) + thirds) * db(9)
        center = (first[:, 2] - second.sum(axis=1) - thirds.sum(axis=1)) * db(self.analysis.center_db)
        back = first[:, :2] - second * (2 / 3)
        sides = first[:, :2] * db(-3)
        if self.settings.preset != "none":
            front = self.shelf.process(front)
            high = self.high.process(original) * db(self.high_db + self.analysis.surround_db)
            weight = .5 if self.settings.blend == "classic" else 1.
            back = back * weight + high * .5
            sides = sides * weight + high * .5
            surrounds = np.column_stack((back, sides))
            for channel in range(4):
                surrounds[:, channel:channel + 1] = self.allpass[channel].process(
                    self.delays[channel].process(surrounds[:, channel:channel + 1]))
        else:
            surrounds = np.column_stack((back, sides))
        result[:, :2] = front
        if self.method == "4.0":
            result[:, :2] += center[:, None] / np.sqrt(2)
        else:
            result[:, 2] = center
        result[:, 4:] = surrounds * self.surround_gain
        return result


class SurroundUpmixMixer:
    """All three CenterCut stages, grouped stem routing, presets and separate LFE."""

    latency_samples = 3 * WINDOW_SIZE

    def __init__(self, settings: MixSettings | None = None,
                 cascade: CascadeSettings | None = None, analysis: Analysis | None = None) -> None:
        self.settings = settings or MixSettings(surround_gain_db=0.)
        self.cascade = cascade or CascadeSettings()
        self.analysis = analysis or Analysis(center_db=2. + PRESETS[self.cascade.preset][4],
                                            delay_l=PRESETS[self.cascade.preset][0], delay_r=PRESETS[self.cascade.preset][1])
        # Two second-order 90 Hz Butterworth passes, as SoX lowpass 90 twice.
        low = butter(2, 90, fs=self.settings.sample_rate, output="sos")
        self.lfe = _Filter(np.vstack((low, low)), 1)
        self.lfe_delay = _SampleDelay(self.latency_samples, 1)
        self._names: tuple[str, ...] | None = None
        self._groups: dict[str, tuple[tuple[str, ...], _Group]] = {}
        self._closed = False

    def process(self, stems: Mapping[str, np.ndarray], original: np.ndarray) -> np.ndarray:
        if self._closed:
            raise ValueError("Обработчик уже завершён.")
        names = tuple(sorted(stems))
        if not REQUIRED_STEMS.issubset(names) or set(names) - REQUIRED_STEMS - OPTIONAL_STEMS:
            raise ValueError("Нужны дорожки bass/drums/vocals/other; дополнительно guitar/piano.")
        original = np.asarray(original)
        if original.ndim != 2 or original.shape[1] != 2 or not np.isfinite(original).all():
            raise ValueError("Некорректный исходный PCM.")
        for name in names:
            data = np.asarray(stems[name])
            if data.shape != original.shape or not np.isfinite(data).all():
                raise ValueError(f"Некорректная дорожка {name}.")
        if self._names is None:
            self._names = names
            routing = {**DEFAULT_ROUTES, **dict(self.cascade.routes)}
            for route in ROUTES:
                members = tuple(name for name in names if routing[name] == route)
                if members:
                    self._groups[route] = (members, _Group(route, self.settings, self.cascade, self.analysis))
        elif self._names != names:
            raise ValueError("Нельзя менять набор дорожек посреди трека.")
        output = np.zeros((len(original), 8))
        reconstructed = np.zeros(original.shape, dtype=np.float64)
        for members, group in self._groups.values():
            combined = sum((np.asarray(stems[name], dtype=np.float64) for name in members), start=np.zeros(original.shape))
            reconstructed += combined
            output += group.process(combined)
        low = self.lfe.process(reconstructed.mean(axis=1, keepdims=True))
        output[:, 3:4] = self.lfe_delay.process(low) * db(self.settings.lfe_gain_db)
        if not np.isfinite(output).all() or np.max(np.abs(output), initial=0) > np.finfo(np.float32).max:
            raise ValueError("Недопустимый уровень PCM после обработки.")
        return output.astype(np.float32)

    def flush(self) -> np.ndarray:
        if self._closed or self._names is None:
            self._closed = True
            return np.empty((0, 8), dtype=np.float32)
        empty = np.zeros((self.latency_samples, 2))
        result = self.process({name: empty for name in self._names}, empty)
        self._closed = True
        return result
