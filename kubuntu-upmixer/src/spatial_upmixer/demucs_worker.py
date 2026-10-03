# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Isolated Demucs process: live JSON progress and actual forward-device checks.

Only this child imports Torch; cancellation kills its process group. No CPU
fallback is performed for an explicitly requested CUDA device.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

PREFIX = "SPATIAL_PROGRESS "


def report(stage: str, message: str, **fields) -> None:
    print(PREFIX + json.dumps(dict(stage=stage, message=message, **fields),
                             ensure_ascii=False, allow_nan=False), flush=True)


def select_device(torch, requested: str):
    # Preserve the project's conservative Auto policy; GPU is an explicit choice.
    if requested not in ("auto", "cpu", "cuda"):
        raise ValueError("Неизвестное вычислительное устройство.")
    if requested != "cuda":
        return torch.device("cpu")
    if torch.version.cuda is None:
        raise RuntimeError("Выбрана CUDA, но этот PyTorch собран только для CPU. "
                           "Нужно окружение PyTorch с CUDA (SPATIAL_PYTHON). Перехода на CPU не было.")
    if not torch.cuda.is_available():
        raise RuntimeError("Выбрана CUDA, но PyTorch не видит доступную NVIDIA GPU. "
                           "Проверьте драйвер и CUDA_VISIBLE_DEVICES. Перехода на CPU не было.")
    return torch.device("cuda", torch.cuda.current_device())


def _tensors(value, torch):
    if torch.is_tensor(value):
        yield value
    elif isinstance(value, (tuple, list)):
        for item in value:
            yield from _tensors(item, torch)
    elif isinstance(value, dict):
        for item in value.values():
            yield from _tensors(item, torch)


def verify_forward(module, inputs, output, expected, torch) -> None:
    """Called by the root submodel hook, while weights are still on its device."""
    incoming, outgoing = list(_tensors(inputs, torch)), list(_tensors(output, torch))
    if not incoming or not outgoing:
        raise RuntimeError("Нельзя подтвердить устройство: нет входного/выходного тензора модели.")
    for value in (*incoming, *outgoing, *module.parameters(), *module.buffers()):
        if value.device != expected:
            raise RuntimeError(f"Устройство модели не совпало: ожидалось {expected}, "
                               f"получено {value.device}. Обработка остановлена без fallback.")
    if expected.type == "cuda":
        # A successful asynchronous launch is not yet proof of completed work.
        torch.cuda.synchronize(expected)


def segment_fraction(info: dict, samplerate: int, segment: float) -> float:
    models = max(1, int(info["models"]))
    index = int(info["model_idx_in_bag"])
    stride = max(1, int(.75 * int(samplerate * segment)))
    # Demucs' one random shift extends input by at most 0.5 s. Use that
    # upper bound: never announce completion before the actual function returns.
    length = max(1, int(info["audio_length"]) + int(.5 * samplerate))
    offset = max(0, int(info["segment_offset"]))
    completed = min(1., (offset + (stride if info["state"] == "end" else 0)) / length)
    return min(.999, max(0., (index + completed) / models))


def separate(model: str, requested: str, source: Path, destination: Path) -> None:
    report("separate_load", "Загрузка PyTorch и проверка вычислительного устройства…",
           demucs="Проверка устройства; вычисление ещё не подтверждено", important=True)
    import torch
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    device = select_device(torch, requested)
    name = torch.cuda.get_device_name(device) if device.type == "cuda" else "CPU"
    label = f"{device} · {name}" if device.type == "cuda" else "CPU"
    report("separate_load", f"Загрузка весов {model}; при необходимости — скачивание…",
           demucs=f"{label}: выбран, проход модели ещё не выполнен", important=True)
    from demucs.api import Separator, save_audio
    separator = Separator(model=model, device=str(device), shifts=1, overlap=.25,
                          split=True, jobs=0, progress=False)
    from demucs.apply import BagOfModels
    models = list(separator.model.models) if isinstance(separator.model, BagOfModels) else [separator.model]
    verified = [0] * len(models)
    latest = 0.

    def evidence() -> str:
        if not sum(verified):
            return f"{label}: ожидается первый проход модели"
        memory = ""
        if device.type == "cuda":
            memory = (f" · VRAM {torch.cuda.memory_allocated(device) / 2**20:.0f} МиБ"
                      f" (пик {torch.cuda.max_memory_allocated(device) / 2**20:.0f})")
        return f"{label}: вычисление подтверждено, фрагментов {sum(verified)}{memory}"

    def checked(index):
        def hook(module, inputs, output):
            verify_forward(module, inputs, output, device, torch)
            verified[index] += 1
            if verified[index] == 1:
                report("separate", f"Подтверждён проход модели {index + 1}/{len(models)} на {label}.",
                       stage_fraction=latest, device=label, demucs=evidence(), important=True)
        return hook

    def callback(info):
        nonlocal latest
        index = int(info["model_idx_in_bag"])
        latest = segment_fraction(info, separator.samplerate, float(models[index].segment))
        position = int(info["segment_offset"]) / separator.samplerate
        length = int(info["audio_length"]) / separator.samplerate
        action = "считает" if info["state"] == "start" else "завершил"
        report("separate", f"Demucs {model}: модель {index + 1}/{len(models)} {action} фрагмент "
               f"от {position:.1f} с / {length:.1f} с · {latest:.0%}",
               stage_fraction=latest, device=label, demucs=evidence())

    hooks = [part.register_forward_hook(checked(index)) for index, part in enumerate(models)]
    separator.update_parameter(callback=callback)
    try:
        report("separate_load", "Чтение звука и подготовка тензоров Demucs…", demucs=evidence())
        _, stems = separator.separate_audio_file(source)
    finally:
        for hook in hooks:
            hook.remove()
    if not all(verified):
        raise RuntimeError("Не все модели выполнили проверенный проход; результат не принимается.")
    report("separate", "Нейросетевое разделение завершено.", stage_fraction=1.,
           device=label, demucs=evidence(), important=True)
    destination.mkdir(parents=True, exist_ok=True)
    for index, (stem, audio) in enumerate(stems.items()):
        report("save_stems", f"Сохранение дорожки Demucs: {stem}…",
               stage_fraction=index / len(stems), device="CPU · запись WAV")
        save_audio(audio, str(destination / f"{stem}.wav"), samplerate=separator.samplerate,
                   clip="none", as_float=True)
    proof = dict(device=str(device), name=name, verified_forwards=verified,
                 torch=torch.__version__, cuda=torch.version.cuda)
    (destination / "execution.json").write_text(json.dumps(proof, ensure_ascii=False), encoding="utf-8")
    report("save_stems", "Дорожки Demucs сохранены.", stage_fraction=1., important=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), required=True)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        separate(args.model, args.device, args.input, args.output)
    except Exception as error:
        print(f"Demucs: {error}", file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
