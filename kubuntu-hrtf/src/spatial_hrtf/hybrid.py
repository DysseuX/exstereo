# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Portable, experimental ITSADIVE-derived PRTF → SOFA processing.

This is deliberately an adaptation of the upstream MATLAB model. It uses the
VIKING pinna-less head/torso responses, a causal minimum-phase reconstruction
instead of the upstream order-60 ``yulewalk`` IIR, and an analytical head-size
ITD instead of selecting a measured HUTUBS subject. No acoustic measurement of
the listener, MATLAB installation, or HUTUBS database is implied.

As in the authors' model, median-plane HRTF predictions are used as a proxy
for PRTFs. The network was trained on complete HRTFs; its output is not a
measured or explicitly separated pinna-only response. Applying predictions
from arbitrary azimuths here would duplicate unreliable head-shadow cues.
"""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import math
import os
import tempfile
import warnings

import numpy as np
from scipy.fft import irfft, rfft


def _as_array(value, name: str, ndim: int) -> np.ndarray:
    result = np.asarray(value, dtype=np.float64)
    if result.ndim != ndim or result.size == 0:
        raise ValueError(f"{name}: expected a non-empty {ndim}-dimensional array")
    if not np.isfinite(result).all():
        raise ValueError(f"{name}: NaN or infinity")
    return result


def _validate_positions(positions) -> np.ndarray:
    positions = _as_array(positions, "positions", 2)
    if positions.shape[1] != 3:
        raise ValueError("positions must be [azimuth, elevation, distance]")
    if (np.abs(positions[:, 1]) > 90.000001).any():
        raise ValueError("elevation must be between -90 and 90 degrees")
    if (positions[:, 2] <= 0).any():
        raise ValueError("source distance must be positive")
    return positions


def _validate_head(head_width_cm: float, head_depth_cm: float) -> tuple[float, float]:
    width, depth = float(head_width_cm), float(head_depth_cm)
    if not (math.isfinite(width) and math.isfinite(depth)):
        raise ValueError("head dimensions must be finite")
    if not (8 <= width <= 30 and 8 <= depth <= 35):
        raise ValueError("head dimensions outside supported centimetre ranges")
    return width, depth


def _validate_rate(sample_rate: float) -> int:
    value = float(sample_rate)
    if not math.isfinite(value) or not 8000 <= value <= 96000 or not value.is_integer():
        raise ValueError("sample rate must be an integer between 8000 and 96000 Hz")
    return int(value)


def load_reference(reference_dir: str | Path) -> tuple[np.ndarray, np.ndarray, int]:
    """Read the actual upstream ``subj_Z.sofa``; return SOFA positions, IRs, Hz.

    ``reference_dir`` may be the ITSADIVE root, its ``matlab/data`` directory,
    or the SOFA file itself. The old supplementary ``pinnaless_VIKING.mat`` is
    intentionally not a fallback: it contains NaNs in many directions.
    """
    from netCDF4 import Dataset

    root = Path(reference_dir)
    candidates = [root] if root.is_file() else [
        root / "matlab" / "data" / "subj_Z.sofa", root / "subj_Z.sofa"
    ]
    path = next((p for p in candidates if p.is_file()), None)
    if path is None:
        raise FileNotFoundError(f"ITSADIVE reference subj_Z.sofa missing in {root}")
    with Dataset(path, "r") as dataset:
        dataset.set_auto_mask(False)
        positions = np.asarray(dataset.variables["SourcePosition"][:], dtype=np.float64)
        ir = np.asarray(dataset.variables["Data.IR"][:], dtype=np.float64)
        rate = _validate_rate(np.asarray(dataset.variables["Data.SamplingRate"][:]).flat[0])
        pos_type = getattr(dataset.variables["SourcePosition"], "Type", "spherical")
        if pos_type.lower() != "spherical":
            raise ValueError("reference SourcePosition must be spherical")
    positions = _validate_positions(positions)
    if ir.ndim != 3 or ir.shape[:2] != (len(positions), 2):
        raise ValueError("reference Data.IR must have dimensions [M,2,N]")
    # Use the same elevation resolution as the upstream structural model.
    grid = np.isclose(positions[:, 1] / 10, np.round(positions[:, 1] / 10))
    valid = np.isfinite(ir).all(axis=(1, 2)) & (np.nanmax(np.abs(ir), axis=(1, 2)) > 0)
    rejected = int(np.count_nonzero(grid & ~valid))
    if rejected:
        warnings.warn(f"Omitted {rejected} non-finite/silent VIKING directions", RuntimeWarning)
    selected = grid & valid
    if not selected.any():
        raise ValueError("reference has no valid directions on the 10-degree elevation grid")
    return positions[selected].copy(), ir[selected].copy(), rate


def analytical_itd(positions, head_width_cm: float, head_depth_cm: float) -> np.ndarray:
    """Approximate right-minus-left arrival time in seconds (SOFA +90° = left).

    A Woodworth spherical-head formula is used with an explicitly approximate
    equivalent radius sqrt(width*depth)/2. Elevation reduces the lateral angle.
    This captures head size, but does not calculate an exact ellipsoidal head or
    model the listener's measured frequency-dependent ITD.
    """
    positions = _validate_positions(positions)
    width, depth = _validate_head(head_width_cm, head_depth_cm)
    radius = math.sqrt(width * depth) / 200.0
    azimuth = np.deg2rad(positions[:, 0])
    elevation = np.deg2rad(positions[:, 1])
    lateral_angle = np.arcsin(np.clip(np.sin(azimuth) * np.cos(elevation), -1, 1))
    return radius / 343.0 * (lateral_angle + np.sin(lateral_angle))


def _minimum_phase(magnitude: np.ndarray, nfft: int) -> np.ndarray:
    """Causal real-cepstrum reconstruction, with the supplied magnitude intact."""
    cepstrum = irfft(np.log(np.maximum(magnitude, 1e-10)), n=nfft)
    causal = np.zeros(nfft)
    causal[0] = cepstrum[0]
    causal[1:nfft // 2] = 2 * cepstrum[1:nfft // 2]
    causal[nfft // 2] = cepstrum[nfft // 2]
    return irfft(np.exp(rfft(causal)), n=nfft)


def _fractional_delay(ir: np.ndarray, delay: float, output_size: int) -> np.ndarray:
    """Linear (not circular) delay; all channels acquire 15 common samples."""
    integer = int(math.floor(delay))
    fraction = delay - integer
    kernel_grid = np.arange(31, dtype=np.float64) - 15 - fraction
    kernel = np.sinc(kernel_grid) * np.kaiser(31, 8.0)
    kernel /= kernel.sum()
    shifted = np.convolve(ir, kernel)
    result = np.zeros(output_size, dtype=np.float64)
    result[integer:integer + shifted.size] = shifted
    return result


def combine_prtf(left_db, right_db, positions, base_ir, sample_rate: int,
                 head_width_cm: float, head_depth_cm: float) -> np.ndarray:
    """Combine two [M,F] pinna-proxy dB predictions with VIKING [M,2,N].

    Prediction row i should use the median-plane projection of positions[i]
    (SOFA degrees/metres), as in upstream; this function does no spatial
    interpolation. These predicted HRTFs are used as PRTFs by assumption,
    without explicit division by a measured head/torso transfer function.
    Frequency bins cover DC..Nyquist, as in upstream's normalised-frequency
    MATLAB stage.
    Right-ear image/direction mirroring belongs to the model caller. Output is
    a finite [M,2,Nout] array with ITD embedded in the impulses. Both ears and all
    directions share one normalisation gain, retaining their relative levels.
    """
    positions = _validate_positions(positions)
    base_ir = _as_array(base_ir, "base_ir", 3)
    left_db = _as_array(left_db, "left_db", 2)
    right_db = _as_array(right_db, "right_db", 2)
    sample_rate = _validate_rate(sample_rate)
    _validate_head(head_width_cm, head_depth_cm)
    if base_ir.shape[:2] != (len(positions), 2) or base_ir.shape[2] < 2:
        raise ValueError("base_ir must have dimensions [len(positions),2,N>=2]")
    if left_db.shape != right_db.shape or left_db.shape[0] != len(positions) or left_db.shape[1] < 2:
        raise ValueError("left/right PRTFs must have matching [len(positions),F>=2] shapes")
    if np.any(np.max(np.abs(base_ir), axis=-1) == 0):
        raise ValueError("reference contains a silent ear response")
    if np.any(left_db < -80) or np.any(right_db < -80) or np.any(left_db > 30) or np.any(right_db > 30):
        warnings.warn("PRTF prediction limited to -80..+30 dB", RuntimeWarning)
    prtf = np.stack([np.clip(left_db, -80, 30), np.clip(right_db, -80, 30)], axis=1)
    nfft = 1 << max(11, (2 * (base_ir.shape[-1] + 2 * (left_db.shape[1] - 1)) - 1).bit_length())
    itd_samples = analytical_itd(positions, head_width_cm, head_depth_cm) * sample_rate
    # Add delay to the farther ear; a common latency is introduced by the FIR.
    delays = np.stack([np.maximum(-itd_samples, 0), np.maximum(itd_samples, 0)], axis=1)
    output_size = nfft + 30 + int(math.ceil(float(delays.max())))
    result = np.empty((len(positions), 2, output_size), dtype=np.float64)
    source_bins = np.linspace(0, 1, left_db.shape[1])
    output_bins = np.linspace(0, 1, nfft // 2 + 1)
    for direction in range(len(positions)):
        for ear in range(2):
            baseline = np.abs(rfft(base_ir[direction, ear], n=nfft))
            pinna_db = np.interp(output_bins, source_bins, prtf[direction, ear])
            magnitude = baseline * np.power(10.0, pinna_db / 20.0)
            impulse = _minimum_phase(magnitude, nfft)
            result[direction, ear] = _fractional_delay(impulse, float(delays[direction, ear]), output_size)
    if not np.isfinite(result).all():
        raise ValueError("non-finite result from hybrid HRTF synthesis")
    peak = float(np.max(np.abs(result)))
    if peak <= 1e-12:
        raise ValueError("hybrid synthesis produced silence")
    result *= 0.95 / peak
    return result


def write_sofa(path: str | Path, ir, positions, sample_rate: int, head_width_cm: float,
               *, title: str = "Spatial HRTF personal prototype", metadata: dict | None = None,
               overwrite: bool = False) -> Path:
    """Atomically write standard SimpleFreeFieldHRIR with zero Data.Delay."""
    from netCDF4 import Dataset

    ir = _as_array(ir, "ir", 3)
    positions = _validate_positions(positions)
    rate = _validate_rate(sample_rate)
    width, _ = _validate_head(head_width_cm, 20.0)
    if ir.shape[:2] != (len(positions), 2) or ir.shape[-1] < 2:
        raise ValueError("ir must have dimensions [len(positions),2,N>=2]")
    output = Path(path)
    if output.exists() and not overwrite:
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
    os.close(fd)
    try:
        with Dataset(temporary, "w", format="NETCDF4") as dataset:
            for name, size in {"M": len(positions), "R": 2, "N": ir.shape[-1], "C": 3, "I": 1, "E": 1}.items():
                dataset.createDimension(name, size)
            now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
            attrs = {
                "Conventions": "SOFA", "Version": "1.0", "SOFAConventions": "SimpleFreeFieldHRIR",
                "SOFAConventionsVersion": "1.0", "APIName": "Spatial HRTF Python", "APIVersion": "0.1",
                "ApplicationName": "Spatial HRTF", "ApplicationVersion": "0.1", "DataType": "FIR",
                "RoomType": "free field", "Title": str(title), "DatabaseName": "ITSADIVE-derived prototype",
                "ListenerShortName": "personal-preview", "AuthorContact": "", "Organization": "",
                "DateCreated": now, "DateModified": now,
                "License": "Derived from VIKING / ITSADIVE data; see bundled third-party notices",
                "History": "Photo-derived PRTF + VIKING magnitude; minimum-phase FIR; analytical head-size ITD",
                "Comment": "Experimental estimate, not an acoustic measurement. ITD is inside Data.IR; Data.Delay is zero.",
                "Origin": "https://github.com/miccio-dk/itsadive_hybrid_structural_model",
                "References": "Miccini and Spagnol (2021), A hybrid approach to structural modeling of individualized HRTFs",
            }
            if metadata:
                for key, value in metadata.items():
                    # Additional fields cannot change format or authorship claims.
                    attrs["SpatialHRTF_" + str(key)] = str(value)
            dataset.setncatts(attrs)

            def variable(name, dimensions, data, **attributes):
                var = dataset.createVariable(name, "f8", dimensions, fill_value=False)
                var[:] = data
                if attributes:
                    var.setncatts(attributes)
                return var

            variable("Data.IR", ("M", "R", "N"), ir)
            variable("Data.SamplingRate", ("I",), [rate], Units="hertz")
            variable("Data.Delay", ("I", "R"), [[0.0, 0.0]])
            variable("SourcePosition", ("M", "C"), positions, Type="spherical", Units="degree, degree, metre")
            variable("ListenerPosition", ("I", "C"), [[0, 0, 0]], Type="cartesian", Units="metre")
            variable("ListenerView", ("I", "C"), [[1, 0, 0]], Type="cartesian", Units="metre")
            variable("ListenerUp", ("I", "C"), [[0, 0, 1]], Type="cartesian", Units="metre")
            receivers = np.array([[0, width / 200, 0], [0, -width / 200, 0]])[:, :, None]
            variable("ReceiverPosition", ("R", "C", "I"), receivers, Type="cartesian", Units="metre")
            variable("EmitterPosition", ("E", "C", "I"), np.zeros((1, 3, 1)), Type="cartesian", Units="metre")
        if output.exists() and not overwrite:
            raise FileExistsError(output)
        os.replace(temporary, output)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return output
