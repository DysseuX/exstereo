# Modifications Copyright (c) 2026 DysseuX (DysseuX Group)

"""CenterCutCL 1.6.0 phase separator, expressed with NumPy's real FFT.

Algorithm: Avery Lee; dsp_centercut adaptation: J. D. Purcell/Moitah.
GPL-2.0-or-later; see third_party/centercut for the original source/notices.
The FFT size, half-sample windows, unclamped alpha and DC/Nyquist treatment
were also checked against the published CenterCutCL 1.6.0 managed assembly.
NumPy replaces Ooura's FFT, and the stream wrapper fixes short-file handling.
"""

from __future__ import annotations

import numpy as np

from .dsp import _SampleDelay


WINDOW_SIZE = 8192
HOP_SIZE = WINDOW_SIZE // 4


def raised_cosine(size: int = WINDOW_SIZE) -> np.ndarray:
    return 0.5 * (1.0 - np.cos(2.0 * np.pi * (np.arange(size) + 0.5) / size))


_WINDOW = raised_cosine()
_POST_WINDOW = _WINDOW**2 / 1.25
_WINDOW.flags.writeable = False
_POST_WINDOW.flags.writeable = False


def center_frame(stereo: np.ndarray) -> np.ndarray:
    """One original CenterCut analysis/synthesis window, before overlap-add."""
    spectrum = np.fft.rfft(stereo * _WINDOW[:, None], axis=0)
    summed = spectrum[:, 0] + spectrum[:, 1]
    difference = spectrum[:, 0] - spectrum[:, 1]
    power = np.abs(summed) ** 2
    alpha = np.zeros_like(power)
    usable = power > 1e-15
    alpha[usable] = 0.5 - 0.5 * np.sqrt(np.abs(difference[usable]) ** 2 / power[usable])
    center = summed * alpha
    # Ooura packs DC and Nyquist into indices 0 and 1; both are zeroed by CL.
    center[0] = center[-1] = 0.0
    # The product of pre-window and post-window is Hann^3. Four overlaps
    # sum to 4 * integral(Hann^3) = 5/4. NumPy irfft already includes 1/N.
    return np.fft.irfft(center, n=WINDOW_SIZE) * _POST_WINDOW


class CenterCut:
    """Streaming L/R -> residual L, residual R, center with fixed N latency.

Alpha deliberately is NOT a positive coherence mask: negative values for
some phase relationships are part of the published CenterCut algorithm.
"""

    latency_samples = WINDOW_SIZE

    def __init__(self) -> None:
        self._history = np.zeros((WINDOW_SIZE, 2), dtype=np.float64)
        self._pending = np.empty((0, 2), dtype=np.float64)
        self._overlap = np.zeros(WINDOW_SIZE, dtype=np.float64)
        self._ready = np.zeros(HOP_SIZE, dtype=np.float64)
        self._delay = _SampleDelay(WINDOW_SIZE, 2)

    def process(self, stereo: np.ndarray) -> np.ndarray:
        stereo = np.asarray(stereo, dtype=np.float64)
        if stereo.ndim != 2 or stereo.shape[1] != 2 or not np.isfinite(stereo).all():
            raise ValueError("CenterCut expects finite stereo PCM")
        pending = np.concatenate((self._pending, stereo))
        blocks = len(pending) // HOP_SIZE
        generated = np.empty(blocks * HOP_SIZE, dtype=np.float64)
        for index in range(blocks):
            offset = index * HOP_SIZE
            self._history[:-HOP_SIZE] = self._history[HOP_SIZE:]
            self._history[-HOP_SIZE:] = pending[offset:offset + HOP_SIZE]
            self._overlap += center_frame(self._history)
            generated[offset:offset + HOP_SIZE] = self._overlap[:HOP_SIZE]
            self._overlap[:-HOP_SIZE] = self._overlap[HOP_SIZE:]
            self._overlap[-HOP_SIZE:] = 0
        self._pending = pending[blocks * HOP_SIZE:].copy()
        ready = np.concatenate((self._ready, generated))
        center = ready[:len(stereo)]
        self._ready = ready[len(stereo):].copy()
        delayed = self._delay.process(stereo)
        return np.column_stack((delayed - center[:, None], center))
