# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Calibrated ear photographs → inspectable contour images, entirely offline."""
from __future__ import annotations

from pathlib import Path
import math

import numpy as np
from PIL import Image, ImageOps
from scipy import ndimage


def open_photo(path: str | Path) -> Image.Image:
    with Image.open(path) as source:
        if source.width * source.height > 64_000_000:
            raise ValueError("Уменьшите фото до 64 мегапикселей или менее.")
        return ImageOps.exif_transpose(source).convert("RGB")


def _point(value, image: Image.Image) -> tuple[float, float]:
    point = np.asarray(value, dtype=float)
    if point.shape != (2,) or not np.isfinite(point).all():
        raise ValueError("Координаты точки должны быть двумя конечными числами.")
    x, y = map(float, point)
    if not (0 <= x < image.width and 0 <= y < image.height):
        raise ValueError("Отмеченная точка находится вне фотографии.")
    return x, y


def calibrated_crop(image: Image.Image, canal, reference, reference_mm: float,
                    mirror: bool = False) -> Image.Image:
    """9.5 cm square centred on the canal; coordinates after EXIF rotation."""
    cx, cy = _point(canal, image)
    if len(reference) != 2:
        raise ValueError("Нужны две точки опорного отрезка.")
    first, second = (_point(point, image) for point in reference)
    length = math.dist(first, second)
    mm = float(reference_mm)
    if not math.isfinite(mm) or not 1 <= mm <= 200 or length < 5:
        raise ValueError("Укажите отрезок 1–200 мм длиной хотя бы 5 пикселей.")
    side = 95 * length / mm
    if side < 128 or side > 4 * max(image.size):
        raise ValueError("Некорректный масштаб или слишком маленькое ухо на фото.")
    if not isinstance(mirror, bool):
        raise ValueError("mirror должен быть true или false.")
    # EXTENT avoids allocating a potentially huge intermediate crop.
    crop = image.transform((512, 512), Image.Transform.EXTENT,
                           (cx-side/2, cy-side/2, cx+side/2, cy+side/2),
                           resample=Image.Resampling.BICUBIC, fillcolor=(0, 0, 0))
    return ImageOps.mirror(crop) if mirror else crop


def extract_contours(crop: Image.Image, threshold: float = 0.15) -> Image.Image:
    """Small Canny-style detector: smoothing, suppression and hysteresis.

    It does not identify ear anatomy. Hair, jewellery, rulers and background
    edges can be false positives; the user must inspect the preview.
    """
    threshold = float(threshold)
    if not math.isfinite(threshold) or not 0.02 <= threshold <= 0.9:
        raise ValueError("Порог контуров должен быть от 0.02 до 0.9.")
    gray = np.asarray(ImageOps.grayscale(crop.resize((512, 512))), dtype=np.float64) / 255
    gray = ndimage.gaussian_filter(gray, 1.2)
    gx = ndimage.sobel(gray, axis=1)
    gy = ndimage.sobel(gray, axis=0)
    magnitude = np.hypot(gx, gy)
    directions = (np.rint(np.arctan2(gy, gx) / (np.pi/4)).astype(int) % 4)
    thin = np.zeros_like(magnitude)
    for orientation, (dy, dx) in enumerate(((0, 1), (1, 1), (1, 0), (1, -1))):
        before = np.roll(magnitude, (dy, dx), axis=(0, 1))
        after = np.roll(magnitude, (-dy, -dx), axis=(0, 1))
        keep = (directions == orientation) & (magnitude >= before) & (magnitude >= after)
        thin[keep] = magnitude[keep]
    thin[:3] = thin[-3:] = 0
    thin[:, :3] = thin[:, -3:] = 0
    scale = float(np.percentile(magnitude, 99.5))
    if scale < 1e-5:
        raise ValueError("На фото не обнаружены контуры: проверьте масштаб и снимок.")
    strong = thin >= max(scale * threshold, 1e-5)
    weak = thin >= max(scale * threshold * 0.4, 1e-6)
    edges = ndimage.binary_propagation(strong, structure=np.ones((3, 3)), mask=weak)
    if edges.sum() < 30:
        raise ValueError("Слишком мало контуров. Уменьшите порог или выберите другое фото.")
    return Image.fromarray(np.uint8(edges) * 255).resize((256, 256), Image.Resampling.LANCZOS)


def prepare_photo(path, canal, reference, reference_mm: float,
                  mirror: bool = False, edge_threshold: float = 0.15) -> Image.Image:
    photo = open_photo(path)
    crop = calibrated_crop(photo, canal, reference, reference_mm, mirror)
    return extract_contours(crop, edge_threshold)


def load_contours(path: str | Path) -> Image.Image:
    """Manual alternative: already calibrated, oriented white-on-black PNG."""
    with Image.open(path) as source:
        if source.size != (256, 256):
            raise ValueError("Готовые контуры должны иметь размер 256×256.")
        result = source.convert("L")
    pixels = np.asarray(result)
    density = float(np.mean(pixels > 80))
    if not 0.001 < density < 0.5:
        raise ValueError("Нужны белые контуры на чёрном фоне, а не обычное фото.")
    return result
