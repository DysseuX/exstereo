# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

from spatial_upmixer.cli import main
from spatial_upmixer.gui import job_config_from_form
from spatial_upmixer.pipeline import JobConfig
from spatial_upmixer.stem_stereo import (
    STEM_NAMES, StemStereoControl, StemStereoMixer, StemStereoSettings, parse_stem_controls,
)


class StemStereoTests(unittest.TestCase):
    def stems(self, frames):
        return {name: np.zeros((frames, 2), dtype=np.float32) for name in STEM_NAMES}

    def settings(self, **overrides):
        controls = StemStereoSettings.neutral().resolved()
        controls.update(overrides)
        return StemStereoSettings(tuple(controls.items()))

    def test_neutral_preserves_original_with_nonzero_demucs_reconstruction_error(self):
        rng = np.random.default_rng(17)
        stems = {name: rng.normal(0, .03, (17019, 2)).astype(np.float32) for name in STEM_NAMES}
        # Original deliberately differs from the sum of the separated stems.
        original = rng.normal(0, .2, (17019, 2)).astype(np.float32)
        mixer = StemStereoMixer(StemStereoSettings.neutral())
        np.testing.assert_array_equal(mixer.process(stems, original), original)
        self.assertEqual(mixer.latency_samples, 0)
        self.assertEqual(mixer.flush().shape, (0, 2))
        self.assertEqual(mixer.flush().shape, (0, 2))
        with self.assertRaises(ValueError):
            mixer.process(stems, original)

    def test_width_changes_only_selected_side_and_retains_residual(self):
        stems = self.stems(3)
        stems["other"][:] = [[.3, .1], [.1, -.1], [-.4, .2]]
        residual = np.array([[.07, -.03], [-.01, .11], [.02, .01]], dtype=np.float32)
        source = stems["other"] + residual
        output = StemStereoMixer(self.settings(other=StemStereoControl(width=2))).process(stems, source)
        expected = np.array([[.4, 0], [.2, -.2], [-.7, .5]]) + residual
        np.testing.assert_allclose(output, expected, atol=6e-8)
        # M/S width has exactly unchanged mono sum, including the residual.
        np.testing.assert_allclose(output.sum(axis=1), source.sum(axis=1), atol=6e-8)

    def test_centered_preset_collapses_vocals_and_bass_but_not_remaining_stereo(self):
        stems = self.stems(1)
        stems["vocals"][0] = [.2, 0]
        stems["bass"][0] = [0, .1]
        stems["drums"][0] = [.02, .07]
        output = StemStereoMixer().process(stems, sum(stems.values()))
        np.testing.assert_allclose(output[0], [.17, .22], atol=2e-8)

    def test_pan_moves_whole_stem_to_either_side_and_gain_is_independent(self):
        stems = self.stems(3)
        stems["vocals"][:] = [[.2, .2], [.1, 0], [0, -.3]]
        source = stems["vocals"].copy()
        for pan, index in ((-1, 0), (1, 1)):
            with self.subTest(pan=pan):
                output = StemStereoMixer(self.settings(vocals=StemStereoControl(pan=pan))).process(stems, source)
                np.testing.assert_allclose(output[:, index], [.4 / np.sqrt(2), .1 / np.sqrt(2), -.3 / np.sqrt(2)], atol=2e-8)
                np.testing.assert_array_equal(output[:, 1 - index], 0)
                self.assertAlmostEqual(float(np.sum(output[0] ** 2)), float(np.sum(source[0] ** 2)), places=7)
        output = StemStereoMixer(self.settings(vocals=StemStereoControl(gain_db=-6))).process(stems, source)
        np.testing.assert_allclose(output, source * 10 ** (-6 / 20), atol=2e-8)

    def test_arbitrary_chunks_and_empty_input_match_one_block_without_mutation(self):
        rng = np.random.default_rng(71)
        stems = {name: rng.normal(0, .02, (8197, 2)).astype(np.float32) for name in STEM_NAMES}
        original = sum(stems.values()) + .001
        snapshot = original.copy()
        expected = StemStereoMixer().process(stems, original)
        mixer = StemStereoMixer()
        self.assertEqual(mixer.process(self.stems(0), np.empty((0, 2))).shape, (0, 2))
        sizes = [1, 3, 701, 819, 4, 47, 6233, 389]
        out, position = [], 0
        for size in sizes:
            out.append(mixer.process({name: value[position:position + size] for name, value in stems.items()},
                                     original[position:position + size]))
            position += size
        self.assertEqual(position, len(original))
        np.testing.assert_array_equal(np.concatenate(out), expected)
        np.testing.assert_array_equal(original, snapshot)

    def test_validation_finite_shape_stem_names_and_float32_overflow(self):
        for kwargs in ({"width": -1}, {"width": 2.1}, {"pan": float("nan")},
                       {"gain_db": float("inf")}, {"pan": 1.1}, {"width": True}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                StemStereoControl(**kwargs)
        for controls in ((("unknown", StemStereoControl()),),
                         (("other", StemStereoControl()), ("other", StemStereoControl()))):
            with self.assertRaises(ValueError):
                StemStereoSettings(controls)
        stems = self.stems(4)
        for source in (np.ones((4, 1)), np.full((4, 2), np.nan), np.ones((4, 2), complex)):
            with self.assertRaises(ValueError):
                StemStereoMixer().process(stems, source)
        with self.assertRaises(ValueError):
            StemStereoMixer().process({"vocals": stems["vocals"]}, np.zeros((4, 2)))
        with self.assertRaises(ValueError):
            StemStereoMixer().process(stems, np.full((4, 2), np.finfo(np.float64).max / 10))
        mixer = StemStereoMixer()
        mixer.process(stems, np.zeros((4, 2)))
        with self.assertRaises(ValueError):
            mixer.process({name: value for name, value in stems.items() if name != "guitar"}, np.zeros((4, 2)))

    def test_default_bitrates_and_cli_controls_reach_pipeline(self):
        source, output = Path("input.flac"), Path("output.opus")
        self.assertEqual(JobConfig(source, output).effective_bitrate_kbps, 512)
        self.assertEqual(JobConfig(source, output, output_mode="stereo").effective_bitrate_kbps, 256)
        self.assertEqual(JobConfig(source, output, output_mode="stereo", bitrate_kbps=192).effective_bitrate_kbps, 192)
        with patch("spatial_upmixer.cli.run_job", return_value=output) as run, redirect_stdout(io.StringIO()):
            self.assertEqual(main([str(source), str(output), "--output-mode", "stereo", "--stereo-neutral",
                                   "--stereo-stem", "other=1.5,-0.25,-2"]), 0)
        config = run.call_args.args[0]
        self.assertEqual(config.output_mode, "stereo")
        self.assertEqual(config.stereo_settings.resolved()["vocals"], StemStereoControl())
        self.assertEqual(config.stereo_settings.resolved()["other"], StemStereoControl(1.5, -.25, -2))
        with patch("spatial_upmixer.cli.run_job") as run, redirect_stderr(io.StringIO()):
            self.assertEqual(main([str(source), str(output), "--stereo-neutral"]), 1)
            run.assert_not_called()
        for specs in (["other=nan,0,0"], ["other=1,0,0", "other=2,0,0"], ["other=1,0"], ["foo=1,0,0"]):
            with self.assertRaises(ValueError):
                parse_stem_controls(specs)

    def test_gui_form_maps_percentages_and_rejects_invalid_controls(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "input.flac"
            source.write_bytes(b"placeholder")  # Form validation does not decode audio.
            form = {"input_path": source, "output_path": Path(temp) / "result.opus",
                    "output_mode": "Стерео по стемам", "other_width": "145", "other_pan": "-12,5", "other_gain": "-2,5"}
            config = job_config_from_form(form)
            self.assertEqual(config.output_mode, "stereo")
            self.assertEqual(config.effective_bitrate_kbps, 256)
            self.assertEqual(config.stereo_settings.resolved()["other"], StemStereoControl(1.45, -.125, -2.5))
            with self.assertRaises(ValueError):
                job_config_from_form({**form, "bass_pan": "nan"})


if __name__ == "__main__":
    unittest.main()
