# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Bounded-memory, stem-aware surround mixing for the desktop prototype.

This is an independent experimental algorithm, not a port of CenterCut,
FreeSurround, SurroundUpmix, or a primary/ambient separation implementation.
Only the vocal stem receives spectral center estimation. Stereo residuals
provide modest, decorrelated surround feeds; they are not recovered recordings
of real rear speakers or independent instruments.

``process`` accepts any number of aligned stereo frames and returns the same
number of eight-channel frames in WAV order: FL FR FC LFE BL BR SL SR.
The entire mix has ``latency_samples`` of delay. Append ``flush()``, discard
that initial delay, and retain the original frame count. Flush drains that
latency; ambience/filter tails beyond the original duration are intentionally
not appended to the track. One mixer instance is used for one track.
"""

from __future__ import annotations

from dataclasses import dataclass
from numbers import Real

import numpy as np
from scipy.signal import butter, sosfilt


CHANNEL_ORDER = ("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")
REQUIRED_STEMS = frozenset({"bass", "drums", "vocals", "other"})
OPTIONAL_STEMS = frozenset({"guitar", "piano"})
_FFT_SIZE = 1024
_HOP_SIZE = 256
_SQRT_TWO = np.sqrt(2.0)


def _check_number(name: str, value: Real, low: float, high: float) -> None:
    if (
        isinstance(value, (bool, np.bool_))
        or not isinstance(value, Real)
        or not np.isfinite(value)
        or not low <= value <= high
    ):
        raise ValueError(f"{name} must be a finite number between {low} and {high}")


@dataclass(frozen=True)
class MixSettings:
    sample_rate: int = 48_000
    surround_gain_db: float = -6.0
    lfe_gain_db: float = -10.0
    lfe_cutoff_hz: float = 120.0

    def __post_init__(self) -> None:
        if isinstance(self.sample_rate, (bool, np.bool_)) or not isinstance(
            self.sample_rate, (int, np.integer)
        ):
            raise ValueError("sample_rate must be an integer")
        _check_number("sample_rate", self.sample_rate, 8_000, 192_000)
        _check_number("surround_gain_db", self.surround_gain_db, -60.0, 6.0)
        _check_number("lfe_gain_db", self.lfe_gain_db, -60.0, 6.0)
        _check_number("lfe_cutoff_hz", self.lfe_cutoff_hz, 20.0, 250.0)


class _SampleDelay:
    def __init__(self, samples: int, channels: int) -> None:
        self._samples = samples
        self._history = np.zeros((samples, channels), dtype=np.float64)

    def process(self, values: np.ndarray) -> np.ndarray:
        joined = np.concatenate((self._history, values), axis=0)
        result = joined[: len(values)].copy()
        self._history = joined[len(values) :].copy()
        return result


class _VocalCenter:
    """STFT coherent-center estimate with fixed analysis timing.

    A real, positive interchannel coherence estimate and level balance form a
    soft mask on (L+R)/2. This rejects hard-panned and opposite-polarity content,
    but cannot distinguish a coherent reverberation from a centered singer.
    Smoothed cross/power spectra reduce rapid changes in the estimate.
    """

    def __init__(self, sample_rate: int) -> None:
        self._history = np.zeros((_FFT_SIZE, 2), dtype=np.float64)
        self._pending = np.empty((0, 2), dtype=np.float64)
        self._overlap = np.zeros(_FFT_SIZE, dtype=np.float64)
        # Four overlapped windows have a squared sum of one.
        hann = 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(_FFT_SIZE) / _FFT_SIZE)
        self._window = np.sqrt(hann / 2.0)
        self._powers = np.zeros((_FFT_SIZE // 2 + 1, 2), dtype=np.float64)
        self._cross = np.zeros(_FFT_SIZE // 2 + 1, dtype=np.complex128)
        self._smoothing = np.exp(-_HOP_SIZE / (sample_rate * 0.050))
        # WOLA contributes N-H samples of delay; this FIFO adds H so arbitrary
        # input chunk boundaries do not change output or require look-ahead.
        self._ready = np.zeros(_HOP_SIZE, dtype=np.float64)

    def process(self, stereo: np.ndarray) -> np.ndarray:
        pending = np.concatenate((self._pending, stereo), axis=0)
        blocks = len(pending) // _HOP_SIZE
        generated = np.empty(blocks * _HOP_SIZE, dtype=np.float64)
        alpha = self._smoothing
        for block in range(blocks):
            offset = block * _HOP_SIZE
            self._history[:-_HOP_SIZE] = self._history[_HOP_SIZE:]
            self._history[-_HOP_SIZE:] = pending[offset : offset + _HOP_SIZE]
            spectrum = np.fft.rfft(self._history * self._window[:, None], axis=0)
            self._powers *= alpha
            self._powers += (1.0 - alpha) * np.abs(spectrum) ** 2
            self._cross *= alpha
            self._cross += (1.0 - alpha) * spectrum[:, 0] * spectrum[:, 1].conj()
            geometric_power = np.sqrt(self._powers[:, 0] * self._powers[:, 1])
            coherence = np.clip(self._cross.real / (geometric_power + 1e-18), 0.0, 1.0)
            balance = 2.0 * geometric_power / (self._powers.sum(axis=1) + 1e-18)
            center_spectrum = spectrum.mean(axis=1) * coherence * balance
            center = np.fft.irfft(center_spectrum, n=_FFT_SIZE) * self._window
            self._overlap += center
            generated[offset : offset + _HOP_SIZE] = self._overlap[:_HOP_SIZE]
            self._overlap[:-_HOP_SIZE] = self._overlap[_HOP_SIZE:]
            self._overlap[-_HOP_SIZE:] = 0.0
        self._pending = pending[blocks * _HOP_SIZE :].copy()
        ready = np.concatenate((self._ready, generated))
        result = ready[: len(stereo)].copy()
        self._ready = ready[len(stereo) :].copy()
        return result


class _AllPassDelay:
    """Stable all-pass delay, computed in vector slices rather than per sample."""

    def __init__(self, samples: int, coefficient: float) -> None:
        self._x = np.zeros(samples, dtype=np.float64)
        self._y = np.zeros(samples, dtype=np.float64)
        self._position = 0
        self._coefficient = coefficient

    def process(self, values: np.ndarray) -> np.ndarray:
        result = np.empty_like(values)
        consumed = 0
        while consumed < len(values):
            count = min(len(self._x) - self._position, len(values) - consumed)
            ring_slice = slice(self._position, self._position + count)
            chunk = values[consumed : consumed + count]
            output = self._x[ring_slice] + self._coefficient * (self._y[ring_slice] - chunk)
            result[consumed : consumed + count] = output
            self._x[ring_slice] = chunk
            self._y[ring_slice] = output
            self._position = (self._position + count) % len(self._x)
            consumed += count
        return result


class SurroundMixer:
    """Convert aligned Demucs stems to experimental 7.1 PCM without clipping.

    Main-channel bass remains full range. LFE is an additional fourth-order
    low-pass feed from the original stereo sum, not a replacement for bass.
    Center extraction preserves the front reconstruction identity:
    FL + FC/sqrt(2) and FR + FC/sqrt(2) equal the delayed stem sums.
    Final linked gain/peak management belongs to the export pipeline.
    """

    latency_samples = _FFT_SIZE

    def __init__(self, settings: MixSettings | None = None) -> None:
        self.settings = settings if settings is not None else MixSettings()
        if not isinstance(self.settings, MixSettings):
            raise TypeError("settings must be MixSettings")
        self._center = _VocalCenter(self.settings.sample_rate)
        self._delay: _SampleDelay | None = None
        self._stem_names: tuple[str, ...] | None = None
        self._closed = False
        self._frames = 0
        self._surround_gain = 10.0 ** (self.settings.surround_gain_db / 20.0)
        self._lfe_gain = 10.0 ** (self.settings.lfe_gain_db / 20.0)
        self._lfe_sos = butter(
            4, self.settings.lfe_cutoff_hz, btype="lowpass", fs=self.settings.sample_rate, output="sos"
        )
        self._lfe_state = np.zeros((len(self._lfe_sos), 2), dtype=np.float64)
        self._ambience_sos = butter(
            2,
            [150.0, min(9_000.0, self.settings.sample_rate * 0.4)],
            btype="bandpass",
            fs=self.settings.sample_rate,
            output="sos",
        )
        self._ambience_state = np.zeros((len(self._ambience_sos), 2, 2), dtype=np.float64)
        self._decorrelators = [
            _AllPassDelay(max(1, round(samples * self.settings.sample_rate / 48_000)), coefficient)
            for samples, coefficient in ((431, 0.55), (617, 0.61), (823, 0.57), (1061, 0.63))
        ]

    @staticmethod
    def _stereo_array(name: str, values: np.ndarray, frames: int | None = None) -> np.ndarray:
        array = np.asarray(values)
        if array.ndim != 2 or array.shape[1] != 2:
            raise ValueError(f"{name} must have shape (frames, 2)")
        if frames is not None and len(array) != frames:
            raise ValueError(f"{name} has a different frame count from original")
        if not np.issubdtype(array.dtype, np.number) or np.iscomplexobj(array):
            raise ValueError(f"{name} must contain real numeric audio samples")
        if not np.isfinite(array).all():
            raise ValueError(f"{name} contains non-finite audio samples")
        return array.astype(np.float64, copy=False)

    def process(self, stems: dict[str, np.ndarray], original: np.ndarray) -> np.ndarray:
        if self._closed:
            raise ValueError("cannot process after flush; create a mixer for the next track")
        if not isinstance(stems, dict):
            raise TypeError("stems must be a dictionary")
        names = frozenset(stems)
        if not REQUIRED_STEMS.issubset(names):
            raise ValueError(f"missing stems: {sorted(REQUIRED_STEMS - names)}")
        if names - REQUIRED_STEMS - OPTIONAL_STEMS:
            raise ValueError(f"unsupported stems: {sorted(names - REQUIRED_STEMS - OPTIONAL_STEMS)}")
        if self._stem_names is not None and names != frozenset(self._stem_names):
            raise ValueError("stem names must remain the same throughout a track")
        original = self._stereo_array("original", original)
        arrays = {name: self._stereo_array(name, values, len(original)) for name, values in stems.items()}
        if not len(original):
            return np.empty((0, 8), dtype=np.float32)
        if self._stem_names is None:
            self._stem_names = tuple(sorted(names))
            self._delay = _SampleDelay(self.latency_samples, (len(names) + 1) * 2)
        self._frames += len(original)
        return self._mix(arrays, original)

    def _mix(self, stems: dict[str, np.ndarray], original: np.ndarray) -> np.ndarray:
        assert self._stem_names is not None and self._delay is not None
        center = self._center.process(stems["vocals"])
        delayed = self._delay.process(
            np.concatenate([stems[name] for name in self._stem_names] + [original], axis=1)
        )
        aligned = {
            name: delayed[:, 2 * index : 2 * index + 2]
            for index, name in enumerate(self._stem_names)
        }
        front = sum(aligned.values())
        output = np.zeros((len(original), 8), dtype=np.float64)
        output[:, :2] = front - center[:, None]
        output[:, 2] = _SQRT_TWO * center
        lfe, self._lfe_state = sosfilt(
            self._lfe_sos, delayed[:, -2:].mean(axis=1), zi=self._lfe_state
        )
        output[:, 3] = self._lfe_gain * lfe

        # A stereo difference is only an ambience candidate. It also contains
        # panned instruments; the low default gain deliberately limits leakage.
        side = np.zeros(len(original), dtype=np.float64)
        for name, weight in (("other", 1.0), ("guitar", 0.7), ("piano", 0.7), ("drums", 0.2)):
            if name in aligned:
                side += weight * 0.5 * (aligned[name][:, 0] - aligned[name][:, 1])
        ambience = np.column_stack((side, -side))
        ambience += 0.25 * (aligned["vocals"] - center[:, None])
        ambience, self._ambience_state = sosfilt(
            self._ambience_sos, ambience, axis=0, zi=self._ambience_state
        )
        output[:, 6] = self._surround_gain * self._decorrelators[0].process(ambience[:, 0])
        output[:, 7] = self._surround_gain * self._decorrelators[1].process(ambience[:, 1])
        output[:, 4] = self._surround_gain / _SQRT_TWO * self._decorrelators[2].process(ambience[:, 0])
        output[:, 5] = self._surround_gain / _SQRT_TWO * self._decorrelators[3].process(ambience[:, 1])
        with np.errstate(over="ignore", invalid="ignore"):
            result = output.astype(np.float32)
        if not np.isfinite(result).all():
            raise ValueError("mix exceeds the finite float32 audio range")
        return result

    def flush(self) -> np.ndarray:
        """Drain exactly the reported common latency and close this track."""
        if self._closed:
            return np.empty((0, 8), dtype=np.float32)
        self._closed = True
        if not self._frames:
            return np.empty((0, 8), dtype=np.float32)
        assert self._stem_names is not None
        silence = np.zeros((self.latency_samples, 2), dtype=np.float64)
        return self._mix({name: silence for name in self._stem_names}, silence)
