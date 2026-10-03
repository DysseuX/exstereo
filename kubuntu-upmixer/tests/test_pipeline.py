# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest

import numpy as np
import soundfile as sf

from spatial_upmixer.dsp import SurroundMixer
from spatial_upmixer.pipeline import CancelledError, JobConfig, probe, run_job
from spatial_upmixer.stem_stereo import StemStereoControl, StemStereoSettings


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg required")
class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stems_dir = self.root / "stems with spaces"
        self.stems_dir.mkdir()
        self.frames = 48_013
        t = np.arange(self.frames) / 48_000
        tone = lambda f: 0.08 * np.sin(2 * np.pi * f * t)
        self.stems = {
            "bass": np.column_stack((tone(60), tone(60))),
            "vocals": np.column_stack((tone(500), tone(500))),
            "drums": np.column_stack((tone(1100), tone(1300))),
            "other": np.column_stack((tone(1700), tone(2200))),
        }
        self.original = sum(self.stems.values())
        self.source = self.root / "original song.flac"
        sf.write(self.source, self.original, 48_000, subtype="PCM_24")
        for name, values in self.stems.items():
            sf.write(self.stems_dir / f"{name}.wav", values, 48_000, subtype="FLOAT")

    def config(self, **kwargs):
        return JobConfig(**{**dict(input_path=self.source, output_path=self.root / "result.opus",
                                  stems_dir=self.stems_dir), **kwargs})

    def test_real_opus_export_preserves_layout_duration_and_channel_identity(self):
        events = []
        output = run_job(self.config(), events.append)
        stream = probe(output)["streams"][0]
        self.assertEqual((stream["codec_name"], stream["channels"], stream["channel_layout"]),
                         ("opus", 8, "7.1"))
        self.assertEqual(stream["sample_rate"], "48000")
        data = output.read_bytes()
        header = data.index(b"OpusHead")
        self.assertEqual(data[header + 9], 8)
        self.assertEqual(data[header + 18], 1)
        decoded = self.root / "decoded.wav"
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(output), "-c:a", "pcm_f32le", str(decoded)], check=True)
        actual, rate = sf.read(decoded, dtype="float32", always_2d=True)
        self.assertEqual(actual.shape, (self.frames, 8))
        self.assertEqual(rate, 48000)
        mixer = SurroundMixer()
        expected = np.concatenate([mixer.process(self.stems, self.original), mixer.flush()])
        expected = expected[mixer.latency_samples:mixer.latency_samples + self.frames]
        for channel in range(8):
            with self.subTest(channel=channel):
                a, b = actual[5000:-2000, channel], expected[5000:-2000, channel]
                self.assertGreater(float(np.corrcoef(a, b)[0, 1]), 0.95)
        self.assertTrue(np.isfinite(actual).all())
        self.assertLess(float(np.max(np.abs(actual))), 1)
        self.assertEqual(events[-1].fraction, 1)
        self.assertFalse(list(self.root.glob(".spatial-upmix-*")))

    def test_stem_stereo_real_opus_has_family_zero_exact_duration_and_independent_width(self):
        events = []
        # Source includes material absent from all Demucs stems. It must survive.
        t = np.arange(self.frames) / 48000
        residual = np.column_stack((.025 * np.sin(2 * np.pi * 2800 * t), .025 * np.sin(2 * np.pi * 3100 * t)))
        original = self.original + residual
        sf.write(self.source, original, 48000, subtype="PCM_24")
        controls = StemStereoSettings.neutral().resolved()
        controls["other"] = StemStereoControl(width=1.5)
        settings = StemStereoSettings(tuple(controls.items()))
        output = run_job(self.config(output_mode="stereo", stereo_settings=settings), events.append)
        stream = probe(output)["streams"][0]
        self.assertEqual((stream["codec_name"], stream["channels"], stream["channel_layout"], stream["sample_rate"]),
                         ("opus", 2, "stereo", "48000"))
        data = output.read_bytes()
        head = data.index(b"OpusHead")
        self.assertEqual((data[head + 9], data[head + 18]), (2, 0))
        tags = {key.lower(): value for key, value in stream["tags"].items()}
        self.assertEqual(tags["spatial_upmixer_algorithm"], "stem_stereo")
        self.assertEqual(tags["spatial_upmixer_layout"], "stereo")
        self.assertEqual(tags["spatial_upmixer_mapping_family"], "0")
        self.assertEqual(int(tags["spatial_upmixer_frames"]), self.frames)
        self.assertEqual(tags["spatial_upmixer_residual"], "original_minus_stems_preserved")
        self.assertEqual(json.loads(tags["spatial_upmixer_stems"])["other"]["width"], 1.5)
        decoded = self.root / "stereo-decoded.wav"
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(output), "-c:a", "pcm_f32le", str(decoded)], check=True)
        actual, rate = sf.read(decoded, dtype="float32", always_2d=True)
        self.assertEqual(actual.shape, (self.frames, 2))
        self.assertEqual(rate, 48000)
        # Independent signal oracle: width 1.5 adds ±0.25(L−R) of just `other`.
        expected = original.copy()
        side_change = .25 * (self.stems["other"][:, 0] - self.stems["other"][:, 1])
        expected[:, 0] += side_change
        expected[:, 1] -= side_change
        for channel in range(2):
            self.assertGreater(float(np.corrcoef(actual[3000:-3000, channel], expected[3000:-3000, channel])[0, 1]), .99)
        self.assertTrue(np.isfinite(actual).all())
        self.assertLess(float(np.max(np.abs(actual))), 1)
        self.assertTrue(any("256 кбит" in event.message for event in events))
        self.assertFalse(list(self.root.glob(".spatial-upmix-*")))

    def test_stereo_neutral_exports_mp3_opus_and_mono_sources_without_separation(self):
        for extension, codec in (("mp3", "libmp3lame"), ("opus", "libopus")):
            with self.subTest(extension=extension):
                source = self.root / f"stereo-input.{extension}"
                subprocess.run(["ffmpeg", "-v", "error", "-i", str(self.source), "-c:a", codec, str(source)], check=True)
                output = run_job(self.config(input_path=source, output_path=self.root / f"stereo-{extension}.opus",
                                             output_mode="stereo", stereo_settings=StemStereoSettings.neutral()))
                decoded = self.root / f"decoded-{extension}.wav"
                reference = self.root / f"reference-{extension}.wav"
                for incoming, destination in ((output, decoded), (source, reference)):
                    subprocess.run(["ffmpeg", "-v", "error", "-i", str(incoming), "-ar", "48000", "-ac", "2",
                                    "-c:a", "pcm_f32le", str(destination)], check=True)
                actual, _ = sf.read(decoded, always_2d=True)
                expected, _ = sf.read(reference, always_2d=True)
                self.assertEqual(actual.shape, expected.shape)
                self.assertGreater(float(np.corrcoef(actual[2000:-2000].ravel(), expected[2000:-2000].ravel())[0, 1]), .99)
        mono = self.root / "mono.flac"
        sf.write(mono, self.original.mean(axis=1), 48000, subtype="PCM_24")
        output = run_job(self.config(input_path=mono, output_path=self.root / "from-mono.opus",
                                     output_mode="stereo", stereo_settings=StemStereoSettings.neutral()))
        self.assertEqual(probe(output)["streams"][0]["channels"], 2)

    def test_stereo_linked_attenuation_retains_channel_balance_and_reports_gain(self):
        source = self.root / "loud.opus"
        # Use safe lossless source amplitude; boost all separated stems instead.
        sf.write(self.source, self.original, 48000, subtype="PCM_24")
        settings = StemStereoSettings(tuple((name, StemStereoControl(gain_db=6)) for name in self.stems))
        # Large aligned stems can exceed 0 dBFS despite residual preservation.
        for name, values in self.stems.items():
            sf.write(self.stems_dir / f"{name}.wav", values * 5, 48000, subtype="FLOAT")
        output = run_job(self.config(output_path=source, output_mode="stereo", stereo_settings=settings))
        tags = {key.lower(): value for key, value in probe(output)["streams"][0]["tags"].items()}
        self.assertLess(float(tags["spatial_upmixer_peak_gain"]), 1)
        decoded = self.root / "loud-decoded.wav"
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(output), "-c:a", "pcm_f32le", str(decoded)], check=True)
        actual, _ = sf.read(decoded, always_2d=True)
        expected = self.original * (1 + 5 * (10 ** (6 / 20) - 1)) * float(tags["spatial_upmixer_peak_gain"])
        self.assertLess(float(np.max(np.abs(actual))), 1)
        for channel in range(2):
            a, b = actual[3000:-3000, channel], expected[3000:-3000, channel]
            self.assertGreater(float(np.corrcoef(a, b)[0, 1]), .99)
            self.assertAlmostEqual(float(np.std(a) / np.std(b)), 1, delta=.04)

    def test_stereo_cancel_and_invalid_config_never_publish(self):
        cancel = threading.Event()
        def report(event):
            if event.stage == "mix":
                cancel.set()
        with self.assertRaises(CancelledError):
            run_job(self.config(output_mode="stereo"), report, cancel)
        self.assertFalse((self.root / "result.opus").exists())
        for kwargs in ({"output_mode": "bad"}, {"output_mode": "stereo", "bitrate_kbps": 128.5},
                       {"output_mode": "stereo", "stereo_settings": {}}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                run_job(self.config(**kwargs))

    def test_mp3_and_opus_sources(self):
        for extension, codec in (("mp3", "libmp3lame"), ("opus", "libopus")):
            with self.subTest(extension=extension):
                source = self.root / f"source.{extension}"
                subprocess.run(["ffmpeg", "-v", "error", "-i", str(self.source),
                                "-c:a", codec, str(source)], check=True)
                output = run_job(self.config(input_path=source, output_path=self.root / f"from-{extension}.opus"))
                self.assertEqual(probe(output)["streams"][0]["channels"], 8)

    def test_centercut_cascade_real_export_and_channel_order(self):
        from spatial_upmixer.dsp import MixSettings
        from spatial_upmixer.surroundupmix import CascadeSettings, SurroundUpmixMixer
        output = run_job(self.config(upmix="surroundupmix", cascade_preset="none", surround_gain_db=0))
        stream = probe(output)["streams"][0]
        self.assertEqual((stream["codec_name"], stream["channels"], stream["channel_layout"]), ("opus", 8, "7.1"))
        tags = {k.lower(): v for k, v in stream["tags"].items()}
        self.assertEqual(tags["spatial_upmixer_algorithm"], "surroundupmix")
        decoded = self.root / "cascade-decoded.wav"
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(output), "-c:a", "pcm_f32le", str(decoded)], check=True)
        actual, _ = sf.read(decoded, dtype="float32", always_2d=True)
        self.assertEqual(actual.shape, (self.frames, 8))
        mixer = SurroundUpmixMixer(MixSettings(surround_gain_db=0), CascadeSettings(preset="none"))
        expected = np.concatenate([mixer.process(self.stems, self.original), mixer.flush()])
        expected = expected[mixer.latency_samples:mixer.latency_samples + self.frames]
        for channel in range(8):
            with self.subTest(channel=channel):
                a, b = actual[10000:-5000, channel], expected[10000:-5000, channel]
                if np.std(b) > 1e-5:
                    self.assertGreater(float(np.corrcoef(a, b)[0, 1]), .94)
        self.assertLess(float(np.max(np.abs(actual))), 1)

    def test_cascade_music_analysis_and_cancel(self):
        cancel = threading.Event()
        def report(event):
            if event.stage == "analysis":
                cancel.set()
        with self.assertRaises(CancelledError):
            run_job(self.config(upmix="surroundupmix"), report, cancel)
        self.assertFalse((self.root / "result.opus").exists())
        output = run_job(self.config(upmix="surroundupmix"))
        self.assertEqual(probe(output)["streams"][0]["channels"], 8)

    def test_tagged_opus_preserves_track_metadata_in_surround_export(self):
        source = self.root / "tagged-source.opus"
        expected_tags = {"title": "Проверка пространственного звука", "artist": "Исполнитель",
                         "album": "Album with spaces", "track": "3/12"}
        args = ["ffmpeg", "-v", "error", "-i", str(self.source), "-c:a", "libopus"]
        for key, value in expected_tags.items():
            args += ["-metadata", f"{key}={value}"]
        subprocess.run(args + [str(source)], check=True)
        source_info = probe(source)
        self.assertIn("tags", source_info["streams"][0])
        output = run_job(self.config(input_path=source))
        result = probe(output)["streams"][0]
        self.assertEqual((result["channels"], result["channel_layout"]), (8, "7.1"))
        actual_tags = {key.lower(): value for key, value in result["tags"].items()}
        for key, value in expected_tags.items():
            with self.subTest(tag=key):
                self.assertEqual(actual_tags.get(key), value)
        self.assertEqual(actual_tags["spatial_upmixer_rendering"], "channel_mix_no_hrtf")

    def test_output_is_not_overwritten_and_input_is_not_modified(self):
        before = self.source.read_bytes()
        target = self.root / "result.opus"
        target.write_bytes(b"existing file")
        with self.assertRaises(FileExistsError):
            run_job(self.config())
        self.assertEqual(target.read_bytes(), b"existing file")
        self.assertEqual(self.source.read_bytes(), before)

    def test_cancelled_job_does_not_publish_output(self):
        cancel = threading.Event()

        def cancel_during_mix(event):
            if event.stage == "mix":
                cancel.set()

        with self.assertRaises(CancelledError):
            run_job(self.config(), cancel_during_mix, cancel)
        self.assertFalse((self.root / "result.opus").exists())
        self.assertFalse(list(self.root.glob(".spatial-upmix-*")))

    def test_mismatched_stems_fail_without_partial_destination(self):
        sf.write(self.stems_dir / "other.wav", self.stems["other"][:2000], 48000, subtype="FLOAT")
        with self.assertRaisesRegex(ValueError, "длительности"):
            run_job(self.config())
        self.assertFalse((self.root / "result.opus").exists())

    def test_missing_stem_is_reported(self):
        (self.stems_dir / "bass.wav").unlink()
        with self.assertRaisesRegex(ValueError, "bass"):
            run_job(self.config())

    def test_gain_nan_and_bad_destination_are_rejected(self):
        with self.assertRaises(ValueError):
            run_job(self.config(surround_gain_db=float("nan")))
        with self.assertRaises(ValueError):
            run_job(self.config(output_path=self.root / "wrong.mp3"))


if __name__ == "__main__":
    unittest.main()
