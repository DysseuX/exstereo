# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Inference-only adapter for the pinned ITSADIVE pretrained models.

No Lightning, torchvision, image decoder, training or GPU is needed. The
published image encoder is reused verbatim; the two dense networks reproduce
their evaluation-time linear/activation stages.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np
from PIL import Image
import torch
from torch import nn
from torch.nn import functional as F


UPSTREAM = Path(__file__).resolve().parents[2] / "third_party/itsadive"


class _EarlyStopping:
    """Inert placeholder for a training callback stored as a dictionary key."""


class _ModelCheckpoint:
    """Inert placeholder: no old training framework is imported or executed."""


def verify_assets(root: Path = UPSTREAM) -> dict:
    info = json.loads((root / "models.json").read_text(encoding="utf-8"))
    for item in info["files"]:
        path = root / item["path"]
        with path.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").hexdigest()
        if path.stat().st_size != item["size"] or checksum != item["sha256"]:
            raise ValueError(f"Повреждён файл модели: {path.name}")
    return info


def _checkpoint(path: Path) -> dict:
    # These two globals only mark callback dictionary keys in the pinned old
    # checkpoints. Allow inert local replacements, never weights_only=False.
    allow = [(_EarlyStopping, "pytorch_lightning.callbacks.early_stopping.EarlyStopping"),
             (_ModelCheckpoint, "pytorch_lightning.callbacks.model_checkpoint.ModelCheckpoint")]
    with torch.serialization.safe_globals(allow):
        return torch.load(path, map_location="cpu", weights_only=True)


class _Dense(nn.Module):
    def __init__(self, state: dict, prefixes: list[str], activation: str):
        super().__init__()
        self.layers = nn.ModuleList()
        self.activation = activation
        for prefix in prefixes:
            weight, bias = state[prefix + ".weight"], state[prefix + ".bias"]
            layer = nn.Linear(weight.shape[1], weight.shape[0])
            layer.load_state_dict({"weight": weight, "bias": bias}, strict=True)
            self.layers.append(layer)

    def forward(self, x):
        for index, layer in enumerate(self.layers):
            x = layer(x)
            if index < len(self.layers) - 1:
                x = F.leaky_relu(x, 0.01) if self.activation == "leaky" else F.relu(x)
        return x


class Predictor:
    def __init__(self, root: Path = UPSTREAM):
        torch.set_num_threads(1)
        self.provenance = verify_assets(root)
        spec = importlib.util.spec_from_file_location("_itsadive_encoder", root / "python/models/vae_incept.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cp = _checkpoint(root / "python/checkpoints/vae_incept_edges.ckpt")
        cfg = cp["hyper_parameters"]["cfg"]
        self.encoder = module.Encoder(cfg["use_inception"], cfg["repeat_per_block"])
        state = cp["state_dict"]
        prefix = "vae.encoder."
        self.encoder.load_state_dict({k[len(prefix):]: v for k, v in state.items() if k.startswith(prefix)}, strict=True)
        self.mu = nn.Linear(256, cfg["latent_size"])
        self.mu.load_state_dict({key: state["vae.fc_mu." + key] for key in ("weight", "bias")}, strict=True)
        del cp, state
        cp = _checkpoint(root / "python/checkpoints/dnn_edge_fullgrid.ckpt")
        cfg = cp["hyper_parameters"]["cfg"]
        if cfg["c"] != ["el", "az"]:
            raise ValueError("Неизвестный порядок координат DNN")
        self.latent = _Dense(cp["state_dict"], [f"dnn.seq.block_{i}.lin" for i in range(len(cfg["hidden_layers"])+1)], "leaky")
        del cp
        cp = _checkpoint(root / "python/checkpoints/cvae_dense_fullgrid.ckpt")
        cfg = cp["hyper_parameters"]["cfg"]
        if cfg["labels"] != ["el", "az"] or cp["hyper_parameters"]["nfft"] != 512:
            raise ValueError("Неизвестный формат CVAE")
        self.decoder = _Dense(cp["state_dict"], [f"cvae.dec.MLP.L{i}" for i in range(len(cfg["decoder_layer_sizes"])+1)], "relu")
        for net in (self.encoder, self.mu, self.latent, self.decoder):
            net.eval()
            net.requires_grad_(False)

    def predict(self, contour: Image.Image, positions, *, right: bool = False) -> np.ndarray:
        if contour.size != (256, 256):
            raise ValueError("Модель принимает контуры 256×256")
        positions = np.asarray(positions, dtype=np.float32)
        if positions.ndim != 2 or positions.shape[1] != 3 or not np.isfinite(positions).all():
            raise ValueError("Нужны конечные SOFA координаты [M,3]")
        if len(positions) == 0 or np.any(np.abs(positions[:, 1]) > 90):
            raise ValueError("Неверное число или высота направлений")
        azimuth = -positions[:, 0] if right else positions[:, 0]
        # The full-grid model learned complete HRTFs, not isolated pinna
        # filters. The paper/upstream structural stage deliberately uses only
        # the median front/back responses as approximate PRTFs. Querying all
        # azimuths here would add learned head shadow on top of VIKING's head.
        # Right-ear mirroring is an adaptation; the photo orientation remains
        # visible and adjustable in the preview, never inferred from EXIF.
        wrapped = azimuth % 360
        azimuth = np.where((wrapped > 90) & (wrapped < 270), -180, 0).astype(np.float32)
        conditions = torch.from_numpy(np.stack([positions[:, 1], azimuth], axis=1))
        photo = torch.from_numpy(np.array(contour.convert("L"), dtype=np.float32) / 255)[None, None]
        output = []
        with torch.inference_mode():
            embedding = self.mu(self.encoder(photo))
            for batch in conditions.split(64):
                latent = self.latent(torch.cat([embedding.expand(len(batch), -1), batch], dim=-1))
                output.append(self.decoder(torch.cat([latent, batch], dim=-1)).numpy())
        result = np.concatenate(output)
        if result.shape != (len(positions), 257) or not np.isfinite(result).all():
            raise ValueError("Модель вернула некорректную PRTF")
        return result
