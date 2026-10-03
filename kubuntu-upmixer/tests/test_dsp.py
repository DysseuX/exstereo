# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Signal-level contract tests, requiring only NumPy, SciPy and unittest."""

import unittest

import numpy as np

from spatial_upmixer.dsp import CHANNEL_ORDER, MixSettings, SurroundMixer


def empty_stems(frames):
    return {name: np.zeros((frames, 2), dtype=np.float32) for name in ("bass", "drums", "vocals", "other")}


def render(stems, original, chunk_sizes=None):
    mixer = SurroundMixer()
    parts = []
    start = 0
    for size in chunk_sizes or [len(original)]:
        end = min(start + size, len(original))
        parts.append(mixer.process({name: values[start:end] for name, values in stems.items()}, original[start:end]))
        start = end
    if start < len(original):
        parts.append(mixer.process({name: values[start:] for name, values in stems.items()}, original[start:]))
    tail = mixer.flush()
    if len(original):
        assert len(tail) == mixer.latency_samples
    parts.append(tail)
    output = np.concatenate(parts, axis=0)
    return output[mixer.latency_samples : mixer.latency_samples + len(original)]


class DspTests(unittest.TestCase):
    def test_centered_vocal_moves_to_center_without_losing_front_reconstruction(self):
        frames = 48_000
        tone = 0.2 * np.sin(2 * np.pi * 440 * np.arange(frames) / 48_000)
        stems = empty_stems(frames)
        stems["vocals"][:] = tone[:, None]
        output = render(stems, stems["vocals"], [8192] * 6)
        steady = output[12_000:-2048]
        self.assertGreater(np.sqrt(np.mean(steady[:, 2] ** 2)), 0.19)
        self.assertLess(np.sqrt(np.mean(steady[:, :2] ** 2)), 0.002)
        np.testing.assert_allclose(output[:, 0] + output[:, 2] / np.sqrt(2), tone, atol=4e-7)
        np.testing.assert_allclose(output[:, 1] + output[:, 2] / np.sqrt(2), tone, atol=4e-7)

    def test_hard_panned_vocal_stays_out_of_center(self):
        frames = 10_001
        stems = empty_stems(frames)
        stems["vocals"][:, 0] = 0.25 * np.sin(2 * np.pi * 550 * np.arange(frames) / 48_000)
        output = render(stems, stems["vocals"], [1, 17, 256, 8192])
        np.testing.assert_array_equal(output[:, 2], 0)
        np.testing.assert_array_equal(output[:, 1], 0)
        np.testing.assert_allclose(output[:, 0], stems["vocals"][:, 0], atol=1e-8)

    def test_opposite_polarity_vocal_is_not_a_center_source(self):
        stems = empty_stems(6000)
        tone = 0.1 * np.sin(2 * np.pi * 330 * np.arange(6000) / 48_000)
        stems["vocals"][:, 0] = tone
        stems["vocals"][:, 1] = -tone
        output = render(stems, stems["vocals"])
        np.testing.assert_array_equal(output[:, 2], 0)

    def test_lfe_rejects_high_frequencies_and_main_bass_is_preserved(self):
        def measure(frequency):
            frames = 24_000
            stems = empty_stems(frames)
            tone = 0.2 * np.sin(2 * np.pi * frequency * np.arange(frames) / 48_000)
            stems["bass"][:] = tone[:, None]
            output = render(stems, stems["bass"])
            np.testing.assert_allclose(output[:, :2], stems["bass"], atol=1e-8)
            np.testing.assert_array_equal(output[:, 2], 0)
            return np.sqrt(np.mean(output[12_000:, 3] ** 2))

        low = measure(60)
        high = measure(4000)
        self.assertGreater(low, 0.04)
        self.assertLess(high, low / 1000)

    def test_arbitrary_chunk_boundaries_match_whole_buffer_and_keep_layout(self):
        rng = np.random.default_rng(713)
        stems = {
            name: rng.normal(0, 0.02, (20_017, 2)).astype(np.float32)
            for name in ("bass", "drums", "vocals", "other", "guitar", "piano")
        }
        original = sum(stems.values())
        whole = render(stems, original)
        split = render(stems, original, [1, 3, 127, 4096, 1, 8192, 15, 1, 256])
        self.assertEqual(whole.shape, (20_017, 8))
        self.assertEqual(whole.dtype, np.float32)
        self.assertEqual(CHANNEL_ORDER, ("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR"))
        np.testing.assert_allclose(split, whole, atol=2e-7, rtol=2e-6)
        np.testing.assert_allclose(whole[:, 0] + whole[:, 2] / np.sqrt(2), original[:, 0], atol=1e-7)
        np.testing.assert_allclose(whole[:, 1] + whole[:, 2] / np.sqrt(2), original[:, 1], atol=1e-7)
        for back, side in ((4, 6), (5, 7)):
            self.assertGreater(np.std(whole[4000:, side]), 0.001)
            self.assertGreater(np.std(whole[4000:, back] - whole[4000:, side]), 0.001)

    def test_process_shape_and_flush_for_short_tracks(self):
        for frames in (1, 17, 255, 256, 257, 1023):
            with self.subTest(frames=frames):
                stems = empty_stems(frames)
                stems["bass"][:, 0] = 0.1
                output = render(stems, stems["bass"], [1, 3])
                self.assertEqual(output.shape, (frames, 8))
                np.testing.assert_allclose(output[:, 0], 0.1, atol=1e-8)

    def test_settings_and_input_validation(self):
        for kwargs in (
            {"sample_rate": 0}, {"sample_rate": 48_000.0}, {"sample_rate": True},
            {"surround_gain_db": float("nan")}, {"lfe_gain_db": 50}, {"lfe_cutoff_hz": 0},
        ):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                MixSettings(**kwargs)
        mixer = SurroundMixer()
        stems = empty_stems(10)
        with self.assertRaises(ValueError):
            mixer.process({}, np.zeros((10, 2)))
        stems["bass"][0, 0] = np.nan
        with self.assertRaises(ValueError):
            mixer.process(stems, np.zeros((10, 2)))
        stems["bass"][0, 0] = 0
        with self.assertRaises(ValueError):
            mixer.process(stems, np.zeros((9, 2)))
        mixer.process(stems, np.zeros((10, 2)))
        with self.assertRaises(ValueError):
            mixer.process({**stems, "guitar": np.zeros((10, 2))}, np.zeros((10, 2)))
        self.assertEqual(mixer.flush().shape, (mixer.latency_samples, 8))
        self.assertEqual(mixer.flush().shape, (0, 8))
        with self.assertRaises(ValueError):
            mixer.process(stems, np.zeros((10, 2)))

    def test_no_input_modification_or_global_clipping(self):
        stems = empty_stems(4096)
        stems["bass"][:, 0] = 2.0
        before = stems["bass"].copy()
        output = render(stems, stems["bass"])
        np.testing.assert_array_equal(stems["bass"], before)
        self.assertEqual(float(output[:, 0].max()), 2.0)


if __name__ == "__main__":
    unittest.main()
