#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Export only the evaluated ITSADIVE inference graph and verify CPU parity.

Run through kubuntu-hrtf/launch.sh --tool, never in the Demucs environment.
No training entry point, optimizer, image decoder or stochastic latent sampling
is exported. Source checkpoints remain untouched in the desktop project.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import tempfile
import time


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "android-hrtf/app/src/main/assets/models"
DEFAULT_FIXTURES = ROOT / "android-hrtf/test-data/neural"
OPSET = 17
ENCODER_ATOL = 2e-4
DECODER_ATOL = 5e-4  # dB; deliberately far below a perceptually relevant change
RTOL = 2e-5


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def file_info(path: Path) -> dict:
    return {"filename": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)}


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def export_and_verify(output: Path, fixtures: Path) -> dict:
    # Deliberately lazy imports: --help does not load Torch or the checkpoints.
    import numpy as np
    import onnx
    import onnxruntime as ort
    from PIL import Image, ImageDraw
    import torch
    from torch import nn
    from spatial_hrtf.neural import Predictor

    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    torch.manual_seed(0)
    torch.use_deterministic_algorithms(True)
    started = time.monotonic()
    print("Loading pinned source weights on CPU…", flush=True)
    predictor = Predictor()
    embedding_size = predictor.mu.out_features

    class EarEncoder(nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = predictor.encoder
            self.mu = predictor.mu

        def forward(self, contour):
            return self.mu(self.encoder(contour))

    class PrtfDecoder(nn.Module):
        def __init__(self):
            super().__init__()
            self.latent = predictor.latent
            self.decoder = predictor.decoder

        def forward(self, features):
            conditions = features[:, -2:]
            return self.decoder(torch.cat((self.latent(features), conditions), dim=-1))

    encoder = EarEncoder().eval()
    decoder = PrtfDecoder().eval()
    for module in (encoder, decoder):
        module.requires_grad_(False)

    output.parent.mkdir(parents=True, exist_ok=True)
    fixtures.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".models-export-", dir=output.parent) as temporary, \
            tempfile.TemporaryDirectory(prefix=".neural-fixtures-", dir=fixtures.parent) as fixture_temporary:
        stage = Path(temporary)
        fixture_stage = Path(fixture_temporary)
        encoder_path = stage / "ear_encoder.onnx"
        decoder_path = stage / "prtf_decoder.onnx"

        print(f"Exporting FP32 graphs, embedding={embedding_size}, opset={OPSET}…", flush=True)
        with torch.inference_mode():
            torch.onnx.export(encoder, torch.zeros(1, 1, 256, 256), str(encoder_path),
                              input_names=["contour"], output_names=["embedding"],
                              opset_version=OPSET, dynamo=False, export_params=True,
                              do_constant_folding=True, external_data=False,
                              training=torch.onnx.TrainingMode.EVAL)
            torch.onnx.export(decoder, torch.zeros(3, embedding_size + 2), str(decoder_path),
                              input_names=["features"], output_names=["prtf_db"],
                              dynamic_axes={"features": {0: "batch"}, "prtf_db": {0: "batch"}},
                              opset_version=OPSET, dynamo=False, export_params=True,
                              do_constant_folding=True, external_data=False,
                              training=torch.onnx.TrainingMode.EVAL)

        # Fail before publishing if training/randomness or external weight paths
        # have accidentally leaked into either otherwise self-contained graph.
        operator_sets = {}
        for path in (encoder_path, decoder_path):
            graph = onnx.load(path, load_external_data=False)
            onnx.checker.check_model(graph, full_check=True)
            operators = sorted({node.op_type for node in graph.graph.node})
            forbidden = [name for name in operators if name.startswith("Random") or name in {"Dropout", "Adam", "Adagrad", "Momentum"}]
            if forbidden or graph.training_info:
                raise ValueError(f"Training/stochastic operators in {path.name}: {forbidden}")
            if any(tensor.data_location == onnx.TensorProto.EXTERNAL for tensor in graph.graph.initializer):
                raise ValueError(f"External initializer leaked into {path.name}")
            if any(tensor.data_type in (onnx.TensorProto.FLOAT16, onnx.TensorProto.DOUBLE)
                   for tensor in graph.graph.initializer):
                raise ValueError(f"Non-FP32 weight in {path.name}")
            operator_sets[path.name] = operators

        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.add_session_config_entry("session.intra_op.allow_spinning", "0")
        options.add_session_config_entry("session.inter_op.allow_spinning", "0")
        encoder_session = ort.InferenceSession(str(encoder_path), sess_options=options, providers=["CPUExecutionProvider"])
        decoder_session = ort.InferenceSession(str(decoder_path), sess_options=options, providers=["CPUExecutionProvider"])

        # Synthetic, non-personal contours cover both a sparse input and an
        # altered pinna-like shape. They are fixtures, never a personal HRTF.
        def synthetic_contour(alternative: bool):
            image = Image.new("L", (256, 256))
            draw = ImageDraw.Draw(image)
            draw.ellipse((55, 30, 190, 230), outline=255, width=2)
            draw.arc((85, 65, 165, 200), 30, 300, fill=255, width=2)
            draw.ellipse((105, 110, 140, 150), outline=255, width=2)
            if alternative:
                draw.ellipse((35, 80, 120, 210), outline=255, width=3)
            return image

        def binary(name, array):
            path = fixture_stage / (name + ".f32")
            values = np.asarray(array, dtype="<f4", order="C")
            values.tofile(path)
            return {**file_info(path), "shape": list(values.shape), "dtype": "float32", "byte_order": "little"}

        metrics = []
        fixture_cases = []
        positions = np.asarray([[0, -40, 1], [180, 90, 1], [90, 0, 1], [270, 10, 1],
                                [45, 30, 1], [135, -20, 1], [320, 40, 1]], dtype=np.float32)
        for case_index in range(2):
            image = synthetic_contour(bool(case_index))
            image.save(fixture_stage / f"contour_{case_index}.png")
            contour = np.asarray(image, dtype=np.float32)[None, None] / 255.0
            with torch.inference_mode():
                expected_embedding = encoder(torch.from_numpy(contour)).numpy()
            actual_embedding = encoder_session.run(["embedding"], {"contour": contour})[0]
            np.testing.assert_allclose(actual_embedding, expected_embedding, atol=ENCODER_ATOL, rtol=RTOL)
            encoder_error = float(np.max(np.abs(actual_embedding - expected_embedding)))
            fixture_case = {"name": f"synthetic_{case_index}", "contour_png": f"contour_{case_index}.png",
                            "encoder_input": binary(f"encoder_input_{case_index}", contour),
                            "encoder_expected": binary(f"encoder_expected_{case_index}", expected_embedding),
                            "decoder_cases": []}

            # Cover both ear orientations, front/back boundaries and the actual
            # CPU batch bound. Batch 1 catches accidental hardcoded export sizes.
            for right in (False, True):
                azimuth = -positions[:, 0] if right else positions[:, 0]
                wrapped = azimuth % 360
                median_azimuth = np.where((wrapped > 90) & (wrapped < 270), -180, 0).astype(np.float32)
                conditions = np.stack((positions[:, 1], median_azimuth), axis=1)
                for count in (1, 3, 64):
                    selected = conditions[np.arange(count) % len(conditions)]
                    source_features = np.concatenate((np.repeat(expected_embedding, count, axis=0), selected), axis=1)
                    actual_features = np.concatenate((np.repeat(actual_embedding, count, axis=0), selected), axis=1)
                    with torch.inference_mode():
                        expected_prtf = decoder(torch.from_numpy(source_features)).numpy()
                    actual_prtf = decoder_session.run(["prtf_db"], {"features": actual_features})[0]
                    np.testing.assert_allclose(actual_prtf, expected_prtf, atol=DECODER_ATOL, rtol=RTOL)
                    # Independently compare with the preexisting public adapter,
                    # including the right-ear/front/back coordinate convention.
                    reference_positions = positions[np.arange(count) % len(positions)]
                    original_prtf = predictor.predict(image, reference_positions, right=right)
                    np.testing.assert_allclose(actual_prtf, original_prtf, atol=DECODER_ATOL, rtol=RTOL)
                    error = float(np.max(np.abs(actual_prtf - original_prtf)))
                    metrics.append({"case": case_index, "right": right, "batch": count,
                                    "encoder_max_abs_error": encoder_error, "prtf_max_abs_error_db": error})
                    if count == 3:
                        label = f"decoder_{case_index}_{'right' if right else 'left'}"
                        fixture_case["decoder_cases"].append({"right": right,
                            "positions": reference_positions.tolist(),
                            "input": binary(label + "_features", source_features),
                            "expected": binary(label + "_expected", original_prtf)})
            fixture_cases.append(fixture_case)
            print(f"Synthetic contour {case_index}: encoder and decoder parity passed.", flush=True)

        # The altered input must affect the model, preventing a constant-output
        # export or an accidentally omitted encoder from passing shape checks.
        first = np.fromfile(fixture_stage / "encoder_expected_0.f32", dtype="<f4")
        second = np.fromfile(fixture_stage / "encoder_expected_1.f32", dtype="<f4")
        if float(np.max(np.abs(first - second))) < 1e-4:
            raise ValueError("The encoder lost sensitivity to contour changes")

        checkpoint_entries = [{"filename": Path(item["path"]).name, "bytes": item["size"], "sha256": item["sha256"]}
                              for item in predictor.provenance["files"] if item["path"].endswith(".ckpt")]
        manifest = {
            "schema_version": 1, "format": "onnx", "precision": "float32", "opset": OPSET,
            "embedding_size": embedding_size,
            "encoder": {**file_info(encoder_path), "input": "contour", "output": "embedding",
                        "input_shape": [1, 1, 256, 256], "output_shape": [1, embedding_size]},
            "decoder": {**file_info(decoder_path), "input": "features", "output": "prtf_db",
                        "input_shape": ["batch", embedding_size + 2], "output_shape": ["batch", 257]},
            "source_checkpoint_bytes": sum(item["bytes"] for item in checkpoint_entries),
            "exported_bytes": encoder_path.stat().st_size + decoder_path.stat().st_size,
            "source": {"repository": predictor.provenance["repository"], "commit": predictor.provenance["commit"],
                       "checkpoints": checkpoint_entries, "license": "MIT"},
            "input_convention": {"image": "calibrated 256x256 white-on-black contour; grayscale 0..1; NCHW",
                                 "features": "embedding then elevation_degrees then median_azimuth_degrees",
                                 "azimuth": "mirror for right ear, wrap to [0,360), then -180 if 90<azimuth<270 else 0",
                                 "output": "257 magnitude dB bins DC..Nyquist; pinna proxy, not final HRIR"},
            "excluded_from_apk": ["training checkpoints", "optimizers", "VAE image decoder", "CVAE encoder",
                                  "stochastic latent sampling", "PyTorch", "Python", "training frameworks"],
            "export_tools": {name: importlib.metadata.version(name) for name in ("torch", "onnx", "onnxruntime", "numpy", "Pillow")},
            "exporter_sha256": sha256(Path(__file__).resolve()), "operators": operator_sets,
            "parity": {"status": "passed", "provider": "CPUExecutionProvider", "threads": 1,
                       "encoder_atol": ENCODER_ATOL, "decoder_atol_db": DECODER_ATOL, "rtol": RTOL,
                       "cases": metrics, "source": "real pinned PyTorch weights and public Predictor.predict"},
        }
        write_json(stage / "manifest.json", manifest)
        write_json(fixture_stage / "manifest.json", {
            "schema_version": 1, "purpose": "non-personal synthetic inference parity fixtures",
            "encoder_sha256": manifest["encoder"]["sha256"], "decoder_sha256": manifest["decoder"]["sha256"],
            "embedding_size": embedding_size, "encoder_atol": ENCODER_ATOL, "decoder_atol_db": DECODER_ATOL,
            "rtol": RTOL, "cases": fixture_cases,
        })
        report = {"created_utc": datetime.now(timezone.utc).isoformat(), "elapsed_seconds": time.monotonic() - started,
                  "models": manifest, "fixtures": str(fixtures.relative_to(ROOT)) if fixtures.is_relative_to(ROOT) else str(fixtures)}
        write_json(fixture_stage / "export-report.json", report)
        output.mkdir(parents=True, exist_ok=True)
        fixtures.mkdir(parents=True, exist_ok=True)
        # Publish manifest last so it never points at partially copied models.
        for path in sorted(stage.iterdir(), key=lambda item: item.name == "manifest.json"):
            os.replace(path, output / path.name)
        for path in fixture_stage.iterdir():
            os.replace(path, fixtures / path.name)
        license_path = ROOT / "kubuntu-hrtf/third_party/itsadive/LICENSE"
        shutil.copyfile(license_path, output / "ITSADIVE-LICENSE.txt")
    print(json.dumps({"status": "passed", "embedding_size": embedding_size,
                      "source_checkpoint_bytes": manifest["source_checkpoint_bytes"],
                      "exported_bytes": manifest["exported_bytes"],
                      "max_prtf_error_db": max(item["prtf_max_abs_error_db"] for item in metrics),
                      "output": str(output)}, ensure_ascii=False), flush=True)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURES)
    args = parser.parse_args()
    export_and_verify(args.output.resolve(), args.fixtures.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
