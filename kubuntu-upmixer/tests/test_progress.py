# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

import hashlib
import json
import math
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from spatial_upmixer.demucs_worker import select_device, segment_fraction, verify_forward
from spatial_upmixer.pipeline import CancelledError, JobConfig, _run, _separate
from spatial_upmixer.progress import ProgressEvent, ProgressReporter, timing


class ProgressTests(unittest.TestCase):
    def test_eta_uses_stage_work_and_resets_between_stages(self):
        now = [100.]
        events = []
        report = ProgressReporter(events.append, lambda: now[0])
        report(ProgressEvent("mix", .4, "start", stage_fraction=0.))
        now[0] += 10
        report(ProgressEvent("mix", .41, "work", stage_fraction=.25))
        self.assertEqual(events[-1].eta_seconds, 30)
        self.assertEqual(events[-1].elapsed_seconds, 10)
        now[0] += 3
        report(ProgressEvent("encode", .82, "new", stage_fraction=0.))
        self.assertIsNone(events[-1].eta_seconds)
        self.assertEqual(events[-1].stage_elapsed_seconds, 0)
        self.assertEqual(events[-1].elapsed_seconds, 13)
        now[0] += 2
        report(ProgressEvent("encode", .82, "work", stage_fraction=.5))
        self.assertEqual(events[-1].eta_seconds, 2)

    def test_unknown_invalid_completed_and_stalled_eta_are_not_fabricated(self):
        event = ProgressEvent("load", None, "download", elapsed_seconds=7)
        self.assertIn("пока неизвестно", timing(event, 20))
        self.assertIn("00:27", timing(event, 20))
        self.assertIn("уточняется", timing(ProgressEvent("mix", None, "", stage_fraction=.5, eta_seconds=3), 4))
        self.assertIn("завершён", timing(ProgressEvent("mix", None, "", stage_fraction=1., eta_seconds=0)))
        events = []
        report = ProgressReporter(events.append)
        for bad in (math.nan, math.inf):
            report(ProgressEvent("mix", None, "", stage_fraction=bad))
            self.assertIsNone(events[-1].stage_fraction)
            self.assertIsNone(events[-1].eta_seconds)

    def test_device_evidence_persists_into_cpu_stage_but_not_another_job(self):
        events = []
        report = ProgressReporter(events.append)
        report(ProgressEvent("separate", None, "", device="cuda:0", demucs="CUDA подтверждено"))
        report(ProgressEvent("mix", None, "", device="CPU"))
        self.assertEqual(events[-1].demucs, "CUDA подтверждено")
        self.assertEqual(events[-1].device, "CPU")
        ProgressReporter(events.append)(ProgressEvent("probe", None, ""))
        self.assertNotIn("CUDA", events[-1].demucs)

    def test_subprocess_reports_before_exit_and_handles_partial_utf8_and_cr(self):
        with tempfile.TemporaryDirectory() as folder:
            marker = Path(folder) / "received"
            script = ("import os,sys,time,pathlib\n"
                      "data='этап'.encode()\nos.write(1,data[:1]);time.sleep(.02);os.write(1,data[1:]+b'\\r')\n"
                      "for i in range(300):\n"
                      " if pathlib.Path(sys.argv[1]).exists():\n  print('ack',flush=True);break\n"
                      " time.sleep(.01)\n"
                      "else: sys.exit(4)\n")
            lines = []
            def received(line):
                lines.append(line)
                if line == "этап":
                    marker.write_text("ok")
            _run([sys.executable, "-c", script, str(marker)], threading.Event(), on_line=received)
            self.assertEqual(lines, ["этап", "ack"])

    def test_live_subprocess_cancellation_stops_child(self):
        cancel = threading.Event()
        with self.assertRaises(CancelledError):
            _run([sys.executable, "-c", "import time;print('start',flush=True);time.sleep(30)"],
                 cancel, on_line=lambda _: cancel.set())

    def test_cache_explicitly_reports_no_current_cuda_execution(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source.flac"
            source.write_bytes(b"source")
            key = hashlib.sha256(b"htdemucs_ft|4.1.0|float32-no-rescale-v1source").hexdigest()
            cached = root / "cache/stems" / key
            cached.mkdir(parents=True)
            for name in ("bass", "drums", "vocals", "other"):
                (cached / f"{name}.wav").touch()
            (cached / "complete.json").write_text("{}")
            config = JobConfig(source, root / "out.opus", device="cuda", cache_dir=root / "cache")
            events = []
            with patch("spatial_upmixer.pipeline.importlib.util.find_spec", return_value=True), \
                 patch("spatial_upmixer.pipeline.importlib.metadata.version", return_value="4.1.0"), \
                 patch("spatial_upmixer.pipeline._run", side_effect=AssertionError("Must not run Demucs")):
                self.assertEqual(len(_separate(config, root, threading.Event(), events.append)), 4)
            self.assertIn("не запускался", events[-1].demucs)
            self.assertNotIn("подтверждено", events[-1].demucs)


class DeviceTests(unittest.TestCase):
    def torch(self, build="13.0", available=True):
        return SimpleNamespace(version=SimpleNamespace(cuda=build),
            cuda=SimpleNamespace(is_available=Mock(return_value=available), current_device=lambda: 0),
            device=lambda kind, index=None: kind if index is None else f"{kind}:{index}")

    def test_explicit_cuda_never_falls_back(self):
        for torch in (self.torch(build=None), self.torch(available=False)):
            with self.assertRaisesRegex(RuntimeError, "Перехода на CPU не было"):
                select_device(torch, "cuda")
        self.assertEqual(select_device(self.torch(), "cuda"), "cuda:0")

    def test_auto_stays_on_cpu_and_does_not_query_cuda(self):
        torch = self.torch()
        self.assertEqual(select_device(torch, "auto"), "cpu")
        self.assertEqual(select_device(torch, "cpu"), "cpu")
        torch.cuda.is_available.assert_not_called()

    def test_verifier_checks_input_output_weights_buffers_and_synchronizes(self):
        class Tensor:
            def __init__(self, device):
                self.device = device
        expected = SimpleNamespace(type="cuda", index=0)
        cpu = SimpleNamespace(type="cpu", index=None)
        torch = SimpleNamespace(is_tensor=lambda v: isinstance(v, Tensor),
                                cuda=SimpleNamespace(synchronize=Mock()))
        good = Tensor(expected)
        model = SimpleNamespace(parameters=lambda: iter([good]), buffers=lambda: iter([good]))
        verify_forward(model, (good,), {"out": good}, expected, torch)
        torch.cuda.synchronize.assert_called_once_with(expected)
        for which in ("input", "output", "weight", "buffer"):
            torch.cuda.synchronize.reset_mock()
            wrong = Tensor(cpu)
            altered = SimpleNamespace(parameters=lambda: iter([wrong if which == "weight" else good]),
                                      buffers=lambda: iter([wrong if which == "buffer" else good]))
            with self.assertRaisesRegex(RuntimeError, "не совпало"):
                verify_forward(altered, (wrong if which == "input" else good,),
                               wrong if which == "output" else good, expected, torch)
            torch.cuda.synchronize.assert_not_called()

    def test_segment_progress_counts_all_models_and_never_claims_early_completion(self):
        values = []
        for index in range(4):
            for offset in (0, 585, 1170, 1755):
                for state in ("start", "end"):
                    values.append(segment_fraction(dict(models=4, model_idx_in_bag=index,
                        audio_length=1900, segment_offset=offset, state=state), 100, 7.8))
        self.assertEqual(values[0], 0)
        self.assertTrue(all(a <= b for a, b in zip(values, values[1:])))
        self.assertGreater(values[-1], .99)
        self.assertLess(values[-1], 1)


if __name__ == "__main__":
    unittest.main()
