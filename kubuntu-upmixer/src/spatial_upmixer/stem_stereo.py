# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Offline stereo remix from aligned Demucs stems, with reconstruction residual.

No learned width synthesis: this stage uses per-stem M/S and a stereo pan
matrix. The original-minus-stems residual remains at its original position.
Memory is proportional to one caller-supplied block, not the track duration.
"""

from __future__ import annotations

from dataclasses import dataclass
import math

import numpy as np

from .dsp import OPTIONAL_STEMS, REQUIRED_STEMS, SurroundMixer, _check_number


STEM_NAMES = ("vocals", "bass", "drums", "other", "guitar", "piano")
STEM_LABELS = {"vocals": "Вокал", "bass": "Бас", "drums": "Ударные",
               "other": "Остальное", "guitar": "Гитара*", "piano": "Пианино*"}


@dataclass(frozen=True)
class StemStereoControl:
    width: float = 1.0
    pan: float = 0.0
    gain_db: float = 0.0

    def __post_init__(self) -> None:
        _check_number("Ширина стема", self.width, 0.0, 2.0)
        _check_number("Панорама стема", self.pan, -1.0, 1.0)
        _check_number("Уровень стема, дБ", self.gain_db, -60.0, 6.0)


DEFAULT_CONTROLS = (
    ("vocals", StemStereoControl(width=0.0)),
    ("bass", StemStereoControl(width=0.0)),
    ("drums", StemStereoControl(width=1.0)),
    ("other", StemStereoControl(width=1.4)),
    ("guitar", StemStereoControl(width=1.25)),
    ("piano", StemStereoControl(width=1.2)),
)


@dataclass(frozen=True)
class StemStereoSettings:
    controls: tuple[tuple[str, StemStereoControl], ...] = DEFAULT_CONTROLS

    def __post_init__(self) -> None:
        names = []
        for name, control in self.controls:
            if name not in STEM_NAMES or name in names:
                raise ValueError("Неизвестный или повторный стереостем: " + str(name))
            if not isinstance(control, StemStereoControl):
                raise ValueError("Настройки стема должны содержать ширину, панораму и уровень.")
            names.append(name)

    @classmethod
    def neutral(cls) -> StemStereoSettings:
        """Exact input identity before linked export attenuation and Opus."""
        return cls(tuple((name, StemStereoControl()) for name in STEM_NAMES))

    def resolved(self) -> dict[str, StemStereoControl]:
        # Partial programmatic overrides use the documented centered preset.
        return {**dict(DEFAULT_CONTROLS), **dict(self.controls)}


def parse_stem_controls(specifications: list[str], *, neutral: bool = False) -> StemStereoSettings:
    """CLI controls use name=width,pan,gain_db; width and pan are ratios."""
    controls = (StemStereoSettings.neutral() if neutral else StemStereoSettings()).resolved()
    seen = set()
    for specification in specifications:
        try:
            name, text = specification.split("=", 1)
            name = name.strip()
            values = [float(value) for value in text.split(",")]
            if name not in STEM_NAMES or name in seen or len(values) != 3:
                raise ValueError()
            controls[name] = StemStereoControl(*values)
            seen.add(name)
        except (TypeError, ValueError) as error:
            raise ValueError("Стем: имя=ширина,панорама,дБ; например other=1.4,0,-1. "
                             "Ширина 0…2, панорама −1…1, уровень −60…+6; имена без повторов.") from error
    return StemStereoSettings(tuple(controls.items()))


class StemStereoMixer:
    """Apply only each stem's change; keep original reconstruction residual.

    Width: M=(L+R)/2, S=(L−R)/2, L'=M+wS, R'=M−wS.
    Pan moves the opposite channel into the selected side instead of dropping
    it. The linear crossfeed matrix is divided by sqrt(1+p²): mono retains its
    total power at center and either endpoint. It is not an HRTF renderer.
    """

    latency_samples = 0

    def __init__(self, settings: StemStereoSettings | None = None) -> None:
        self.settings = settings if settings is not None else StemStereoSettings()
        if not isinstance(self.settings, StemStereoSettings):
            raise TypeError("settings must be StemStereoSettings")
        self._controls = self.settings.resolved()
        self._names: frozenset[str] | None = None
        self._closed = False

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
        if self._names is not None and names != self._names:
            raise ValueError("stem names must remain the same throughout a track")
        source = SurroundMixer._stereo_array("original", original)
        arrays = {name: SurroundMixer._stereo_array(name, values, len(source))
                  for name, values in stems.items()}
        if len(source):
            self._names = names
        output = source.copy()
        for name in sorted(names):
            control = self._controls[name]
            if control == StemStereoControl():
                continue  # Exact identity, including original-minus-stems residual.
            values = arrays[name]
            mid = values.mean(axis=1)
            side = .5 * (values[:, 0] - values[:, 1]) * control.width
            left, right = mid + side, mid - side
            pan = control.pan
            scale = 10.0 ** (control.gain_db / 20.0) / math.sqrt(1.0 + pan * pan)
            if pan >= 0:
                changed = np.column_stack(((1 - pan) * left, right + pan * left))
            else:
                changed = np.column_stack((left - pan * right, (1 + pan) * right))
            output += changed * scale - values
        with np.errstate(over="ignore", invalid="ignore"):
            result = output.astype(np.float32)
        if not np.isfinite(result).all():
            raise ValueError("mix exceeds the finite float32 audio range")
        return result

    def flush(self) -> np.ndarray:
        self._closed = True
        return np.empty((0, 2), dtype=np.float32)
