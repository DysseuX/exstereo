# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Measured per-stage progress. Weighted overall fractions are not ETA inputs."""
from dataclasses import dataclass, replace
import math
import time
from typing import Callable


@dataclass(frozen=True)
class ProgressEvent:
    stage: str
    fraction: float | None
    message: str
    stage_fraction: float | None = None
    device: str = "CPU"
    demucs: str | None = None
    important: bool = False
    elapsed_seconds: float = 0.0
    stage_elapsed_seconds: float = 0.0
    eta_seconds: float | None = None


class ProgressReporter:
    def __init__(self, callback: Callable[[ProgressEvent], None], clock=time.monotonic):
        self.callback, self.clock = callback, clock
        self.started = self.stage_started = clock()
        self.stage = ""
        self.demucs = "Ещё не запускался"
        self.last_fraction = 0.0

    def __call__(self, event: ProgressEvent) -> None:
        now = self.clock()
        fraction = event.stage_fraction
        if fraction is not None:
            fraction = max(0., min(1., fraction)) if math.isfinite(fraction) else None
        if self.stage != event.stage or (fraction is not None and fraction < self.last_fraction):
            self.stage, self.stage_started, self.last_fraction = event.stage, now, 0.
        elapsed = max(0., now - self.stage_started)
        eta = None
        if fraction is not None:
            self.last_fraction = fraction
            if fraction >= 1:
                eta = 0.
            elif fraction > .001 and elapsed >= .5:
                eta = elapsed * (1 - fraction) / fraction
        if event.demucs is not None:
            self.demucs = event.demucs
        self.callback(replace(event, stage_fraction=fraction, demucs=self.demucs,
                              elapsed_seconds=max(0., now - self.started),
                              stage_elapsed_seconds=elapsed, eta_seconds=eta))


def duration(seconds: float) -> str:
    value = max(0, math.ceil(seconds))
    hours, rest = divmod(value, 3600)
    minutes, seconds = divmod(rest, 60)
    return f"{hours}:{minutes:02}:{seconds:02}" if hours else f"{minutes:02}:{seconds:02}"


def timing(event: ProgressEvent, since_event: float = 0.) -> str:
    elapsed = duration(event.elapsed_seconds + max(0., since_event))
    if event.eta_seconds is None:
        eta = "рассчитывается" if event.stage_fraction is not None else "пока неизвестно"
    elif event.stage_fraction == 1:
        eta = "завершён"
    elif since_event >= event.eta_seconds:
        eta = "уточняется"
    else:
        eta = "≈ " + duration(event.eta_seconds - max(0., since_event))
    return f"Прошло: {elapsed}  ·  До конца этапа: {eta}"
