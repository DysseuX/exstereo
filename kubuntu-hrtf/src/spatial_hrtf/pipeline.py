# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Build an auditable SOFA profile from a calibrated two-ear manifest."""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile

import numpy as np

from .hybrid import analytical_itd, combine_prtf, load_reference, write_sofa
from .photos import load_contours, prepare_photo


PRODUCTS = ("personal.sofa", "report.json", "left-contours.png", "right-contours.png")


def read_manifest(path: Path) -> dict:
    if path.stat().st_size > 100_000:
        raise ValueError("Слишком большой manifest.json")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("Manifest должен быть JSON-объектом.")
    for name in ("left", "right"):
        if not isinstance(manifest.get(name), dict):
            raise ValueError(f"Отсутствует описание уха: {name}")
    analytical_itd([[0, 0, 1]], manifest["head_width_cm"], manifest["head_depth_cm"])
    return manifest


def build_profile(manifest_path: str | Path, output: str | Path | None = None,
                  *, log=print) -> Path:
    manifest_path = Path(manifest_path).resolve()
    manifest = read_manifest(manifest_path)
    target = Path(output or manifest.get("output_directory") or manifest_path.parent / "profile").resolve()
    target.mkdir(parents=True, exist_ok=True)
    for name in PRODUCTS:
        if (target / name).exists():
            raise FileExistsError(f"Результат уже существует: {target / name}. Выберите другую папку.")

    # Keep partial/cancelled results separate from completed profiles.
    with tempfile.TemporaryDirectory(prefix=".building-", dir=target) as temporary:
        stage = Path(temporary)
        contours, sources = {}, {}
        for name in ("left", "right"):
            log(f"Подготовка контуров: {name}", flush=True)
            item = dict(manifest[name])
            manual = "contours" in item
            source = Path(item["contours"] if manual else item["path"])
            if not source.is_absolute():
                source = manifest_path.parent / source
            source = source.resolve()
            if manual:
                image = load_contours(source)
            else:
                image = prepare_photo(source, item["canal"], item["reference"], item["reference_mm"],
                                      item.get("mirror", name == "right"), item.get("edge_threshold", 0.15))
            image.save(stage / f"{name}-contours.png")
            contours[name] = image
            with source.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            density = float(np.mean(np.asarray(image) > 80))
            sources[name] = {"name": source.name, "sha256": digest, "manual_contours": manual,
                             "contour_density": density, "settings": item}
            if density > 0.25:
                log(f"Внимание: {name} — много контуров; проверьте волосы, фон и линейку.", flush=True)

        # Lazy imports let photo preparation and the UI work without loading
        # neural models into the GUI process.
        from .neural import Predictor, UPSTREAM

        log("Загрузка проверенных весов ITSADIVE (CPU)…", flush=True)
        model = Predictor()
        positions, baseline, rate = load_reference(UPSTREAM)
        log(f"Предсказание PRTF двух ушей: {len(positions)} направлений…", flush=True)
        left = model.predict(contours["left"], positions)
        right = model.predict(contours["right"], positions, right=True)
        log("Синтез HRIR, задержки между ушами, запись SOFA…", flush=True)
        impulses = combine_prtf(left, right, positions, baseline, rate,
                                manifest["head_width_cm"], manifest["head_depth_cm"])
        sofa = write_sofa(stage / "personal.sofa", impulses, positions, rate, manifest["head_width_cm"],
                          metadata={"UpstreamCommit": model.provenance["commit"],
                                    "HeadDepthCm": manifest["head_depth_cm"],
                                    "Method": "photo contours; pretrained ITSADIVE; VIKING; analytical ITD"})
        with sofa.open("rb") as stream:
            sofa_hash = hashlib.file_digest(stream, "sha256").hexdigest()
        report = {
            "schema_version": 1, "created_utc": datetime.now(timezone.utc).isoformat(),
            "method": "Experimental ITSADIVE-derived photo HRTF; not acoustically measured",
            "models": model.provenance, "inputs": sources,
            "head_width_cm": float(manifest["head_width_cm"]),
            "head_depth_cm": float(manifest["head_depth_cm"]),
            "output": {"file": "personal.sofa", "sha256": sofa_hash, "sample_rate": rate,
                       "directions": len(positions), "samples": impulses.shape[-1],
                       "elevation_range": [float(positions[:, 1].min()), float(positions[:, 1].max())],
                       "peak": float(np.max(np.abs(impulses))), "external_delay": 0},
            "prtf_db_range": {"left": [float(left.min()), float(left.max())],
                              "right": [float(right.min()), float(right.max())]},
            "adaptations": [
                "Automatic edge detection needs visual review; it is not anatomical segmentation.",
                "Two separately encoded ears; right-ear directions mirrored to a canonical-ear model.",
                "Full-grid checkpoint queried only on median front/back planes, as in upstream; VIKING supplies azimuth-dependent head shadow.",
                "Minimum-phase FIR replaces MATLAB yulewalk; normalised frequency axis follows MATLAB stage.",
                "Woodworth ITD with approximate sqrt(width*depth)/2 radius replaces HUTUBS subject selection.",
                "Generic VIKING head/torso magnitude remains; geometry below -40 degrees is not measured.",
                "One common peak normalisation preserves interaural/directional relative levels.",
                "Perceptual accuracy must be evaluated against a generic HRTF; it is not guaranteed by photo input.",
            ],
        }
        (stage / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # A directory lock also works on removable filesystems without hard
        # links. Publish SOFA last: its presence signals a completed result.
        lock = target / ".publish-lock"
        lock.mkdir()
        try:
            for name in PRODUCTS:
                if (target / name).exists():
                    raise FileExistsError(target / name)
            for name in ("left-contours.png", "right-contours.png", "report.json", "personal.sofa"):
                os.replace(stage / name, target / name)
        finally:
            lock.rmdir()
    log(f"Готово: {target / 'personal.sofa'}", flush=True)
    return target / "personal.sofa"
