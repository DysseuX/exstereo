#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Export the actual VIKING reference and an audited, patchable SOFA template.

Run only in the dedicated Android-HRTF build venv, via scripts/run-limited.sh.
No Python/SciPy/HDF5 runtime is included in the Android application.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import struct
import sys

import h5py
import numpy as np
from netCDF4 import Dataset

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "kubuntu-hrtf/src"))
from spatial_hrtf.hybrid import load_reference, combine_prtf
from spatial_hrtf.photos import calibrated_crop, extract_contours


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def dataset_region(dataset, shape):
    assert dataset.shape == shape, (dataset.name, dataset.shape)
    assert dataset.dtype == np.dtype("<f8") and dataset.dtype.byteorder in ("<", "=")
    plist = dataset.id.get_create_plist()
    assert plist.get_layout() == h5py.h5d.CONTIGUOUS
    assert plist.get_nfilters() == 0 and plist.get_external_count() == 0
    assert not dataset.is_virtual and dataset.chunks is None and dataset.compression is None
    assert dataset.dtype.metadata is None and not dataset.dtype.hasobject
    offset = dataset.id.get_offset()
    assert offset is not None and offset >= 8
    assert dataset.id.get_storage_size() == math.prod(shape) * 8
    return int(offset), int(dataset.id.get_storage_size())


def write_template(path, positions, rate, samples):
    """Create final attributes once: avoid HDF5 deleted/reallocated attribute messages.

    Reopening a netCDF file and lengthening string attributes left stale/duplicate
    attribute messages visible to libmysofa 1.3.x, which then missed DataType.
    h5py/netCDF4 still read those files, so their successful reopen is insufficient.
    """
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
    attrs = {
        "Conventions": "SOFA", "Version": "1.0", "SOFAConventions": "SimpleFreeFieldHRIR",
        "SOFAConventionsVersion": "1.0", "APIName": "Android HRTF offline", "APIVersion": "0.1",
        "ApplicationName": "Ear HRTF", "ApplicationVersion": "0.1", "DataType": "FIR",
        "RoomType": "free field", "Title": "Experimental photo-derived ITSADIVE hybrid HRTF",
        "DatabaseName": "ITSADIVE-derived prototype", "ListenerShortName": "personal-preview",
        "AuthorContact": "", "Organization": "", "DateCreated": now, "DateModified": now,
        "License": "VIKING reference: Creative Commons Attribution 4.0 International; see bundled notices.",
        "History": "Photo-derived PRTF + VIKING magnitude; minimum-phase FIR; analytical head-size ITD",
        "Comment": "Experimental estimate, not an acoustic measurement. ITD is embedded in Data.IR; Data.Delay is zero. ReceiverPosition encodes head width. Dates refer to the template export. Head depth influences analytical ITD only.",
        "Origin": "https://github.com/miccio-dk/itsadive_hybrid_structural_model",
        "References": "Spagnol, Miccini and Unnthorsson (2020), VIKING HRTF, https://doi.org/10.5281/zenodo.4160401; Miccini and Spagnol (2021), ITSADIVE hybrid structural model",
    }
    with Dataset(path, "w", format="NETCDF4") as dataset:
        for name, count in {"M": len(positions), "R": 2, "N": samples, "C": 3, "I": 1, "E": 1}.items():
            dataset.createDimension(name, count)
        dataset.setncatts(attrs)

        def variable(name, dimensions, values, **attributes):
            item = dataset.createVariable(name, "f8", dimensions, fill_value=False, contiguous=True, endian="little")
            item[:] = values
            if attributes:
                item.setncatts(attributes)

        variable("Data.IR", ("M", "R", "N"), np.zeros((len(positions), 2, samples)))
        variable("Data.SamplingRate", ("I",), [rate], Units="hertz")
        variable("Data.Delay", ("I", "R"), [[0.0, 0.0]])
        variable("SourcePosition", ("M", "C"), positions, Type="spherical", Units="degree, degree, metre")
        variable("ListenerPosition", ("I", "C"), [[0, 0, 0]], Type="cartesian", Units="metre")
        variable("ListenerView", ("I", "C"), [[1, 0, 0]], Type="cartesian", Units="metre")
        variable("ListenerUp", ("I", "C"), [[0, 0, 1]], Type="cartesian", Units="metre")
        variable("ReceiverPosition", ("R", "C", "I"), np.array([[0, .09, 0], [0, -.09, 0]])[:, :, None],
                 Type="cartesian", Units="metre")
        variable("EmitterPosition", ("E", "C", "I"), np.zeros((1, 3, 1)), Type="cartesian", Units="metre")


def export(destination: Path, fixtures: Path):
    destination.mkdir(parents=True, exist_ok=True)
    fixtures.mkdir(parents=True, exist_ok=True)
    positions, reference, rate = load_reference(ROOT / "kubuntu-hrtf/third_party/itsadive")
    m, r, n = reference.shape
    assert m == 729 and r == 2 and rate == 48000, (reference.shape, rate)
    output_samples = 2200
    nfft = 1 << max(11, (2 * (n + 512) - 1).bit_length())
    max_itd = math.sqrt(30 * 35) / 200 / 343 * (math.pi / 2 + 1)
    assert nfft + 30 + math.ceil(max_itd * rate) <= output_samples
    reference_path = destination / "reference.bin"
    with reference_path.open("wb") as stream:
        stream.write(b"HRTFREF1")
        stream.write(struct.pack("<4i", m, 2, n, rate))
        stream.write(np.asarray(positions, dtype="<f8").tobytes())
        stream.write(np.asarray(reference, dtype="<f8").tobytes())
    template = destination / "personal_template.sofa"
    write_template(template, positions, rate, output_samples)
    with h5py.File(template, "r") as dataset:
        ir_offset, ir_bytes = dataset_region(dataset["Data.IR"], (m, 2, output_samples))
        receiver_offset, receiver_bytes = dataset_region(dataset["ReceiverPosition"], (2, 3, 1))
        # Every HDF5 object must be local and nonvirtual; patchable datasets have stricter checks above.
        def audit(_name, item):
            if isinstance(item, h5py.Dataset):
                assert not item.is_virtual and item.id.get_create_plist().get_external_count() == 0
        dataset.visititems(audit)
    assert ir_offset + ir_bytes <= template.stat().st_size
    assert receiver_offset + receiver_bytes <= template.stat().st_size
    assert ir_offset + ir_bytes <= receiver_offset or receiver_offset + receiver_bytes <= ir_offset
    manifest = dict(format_version=1, directions=m, reference_samples=n, sample_rate=rate,
                    output_samples=output_samples, dataset_dtype="<f8", dataset_layout="contiguous",
                    reference_sha256=sha(reference_path), template_sha256=sha(template),
                    template_size=template.stat().st_size, ir_offset=ir_offset, ir_byte_size=ir_bytes,
                    receiver_offset=receiver_offset, receiver_byte_size=receiver_bytes,
                    upstream_reference_sha256=sha(ROOT / "kubuntu-hrtf/third_party/itsadive/matlab/data/subj_Z.sofa"),
                    reference_license="CC BY 4.0", reference_doi="10.5281/zenodo.4160401")
    (destination / "reference_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    make_fixtures(fixtures, positions, reference, rate)
    print(json.dumps(manifest, indent=2))


def make_fixtures(destination, positions, reference, rate):
    """Deterministic SciPy/Pillow oracle for Kotlin math, rather than self-comparison."""
    from PIL import Image, ImageDraw
    indices = [0, 81, 200, 400]
    pos, base = positions[indices], reference[indices]
    frequencies = np.linspace(0, 1, 257)
    left = np.asarray([3 * np.sin(frequencies * (i + 1) * np.pi) for i in range(4)], dtype=np.float32)
    right = np.asarray([-2 * np.cos(frequencies * (i + 2) * np.pi) for i in range(4)], dtype=np.float32)
    result = combine_prtf(left, right, pos, base, rate, 18, 20)
    fixture = dict(positions=pos.tolist(), base=base.tolist(), sample_rate=rate,
                   left=left.tolist(), right=right.tolist(), head_width=18, head_depth=20,
                   expected=result.tolist())
    (destination / "acoustic_reference.json").write_text(json.dumps(fixture, separators=(",", ":")))
    image = Image.new("RGB", (600, 600), (120, 120, 120))
    draw = ImageDraw.Draw(image)
    draw.ellipse((150, 90, 450, 500), outline="white", width=12)
    draw.arc((225, 150, 405, 440), 40, 330, fill="black", width=12)
    image.save(destination / "photo_input.png")
    crop = calibrated_crop(image, (300, 300), ((40, 20), (240, 20)), 40)
    crop.save(destination / "photo_crop.png")
    extract_contours(crop).save(destination / "photo_contours.png")
    (destination / "photo_gray.bin").write_bytes(np.asarray(crop.convert("L"), dtype=np.uint8).tobytes())
    (destination / "photo_contours.bin").write_bytes(np.asarray(extract_contours(crop), dtype=np.uint8).tobytes())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", type=Path, default=ROOT / "android-hrtf/app/src/main/assets")
    parser.add_argument("--fixtures", type=Path, default=ROOT / "android-hrtf/app/src/test/resources/parity")
    args = parser.parse_args()
    export(args.assets, args.fixtures)
