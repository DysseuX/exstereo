# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

"""Signal and independent-oracle checks of the CenterCut/cascade port."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import numpy as np

from spatial_upmixer.centercut import CenterCut, WINDOW_SIZE, center_frame
from spatial_upmixer.dsp import MixSettings
from spatial_upmixer.surroundupmix import CascadeSettings, SurroundUpmixMixer, PRESETS


def separate(data, chunks):
    cc = CenterCut()
    pieces = []
    start = 0
    for n in chunks:
        pieces.append(cc.process(data[start:start+n]))
        start += n
    pieces.append(cc.process(data[start:]))
    pieces.append(cc.process(np.zeros((WINDOW_SIZE, 2))))
    return np.concatenate(pieces)[WINDOW_SIZE:WINDOW_SIZE+len(data)]


def render(stems, settings=None, chunks=()):
    mixer = SurroundUpmixMixer(cascade=settings or CascadeSettings(preset="none"))
    original = sum(stems.values())
    parts = []
    offset = 0
    for count in chunks:
        end = min(offset + count, len(original))
        parts.append(mixer.process({k: v[offset:end] for k, v in stems.items()}, original[offset:end]))
        offset = end
    parts.append(mixer.process({k: v[offset:] for k, v in stems.items()}, original[offset:]))
    parts.append(mixer.flush())
    return np.concatenate(parts)[mixer.latency_samples:mixer.latency_samples + len(original)]


class CenterCutTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("CENTER_CUT_REFERENCE"), "Set CENTER_CUT_REFERENCE to compiled original Hartley oracle")
    def test_numpy_matches_original_hartley_implementation(self):
        rng = np.random.default_rng(8241)
        data = rng.normal(0, .1, (WINDOW_SIZE, 2))
        data[:, 1] += .6 * data[:, 0]
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory)/"in.f64", Path(directory)/"out.f64"
            data.astype("<f8").tofile(source)
            subprocess.run([os.environ["CENTER_CUT_REFERENCE"], str(source), str(target)], check=True)
            expected = np.fromfile(target, dtype="<f8")
        np.testing.assert_allclose(center_frame(data), expected, atol=3e-13, rtol=2e-10)

    def test_common_signal_is_center_and_recombination_is_exact(self):
        tone = .2 * np.sin(2*np.pi*750*np.arange(30017)/48000)
        data = np.column_stack((tone, tone))
        result = separate(data, [1, 17, 2048, 8192])
        np.testing.assert_allclose(result[:, :2] + result[:, 2:3], data, atol=1e-16)
        np.testing.assert_allclose(result[8192:-8192, 2], tone[8192:-8192], atol=2e-7)

    def test_panned_and_antiphase_are_not_a_center_signal(self):
        tone = .1 * np.sin(2*np.pi*375*np.arange(17003)/48000)
        for data in (np.column_stack((tone, 0*tone)), np.column_stack((tone, -tone))):
            result = separate(data, [37, 2049])
            np.testing.assert_allclose(result[:, 2], 0, atol=1e-14)

    def test_arbitrary_chunks_match_and_phase_rule_is_not_coherence_mask(self):
        t = np.arange(27031)/48000
        data = .2*np.column_stack((np.sin(2*np.pi*900*t),np.sin(2*np.pi*900*t+2*np.pi/3)))
        expected = separate(data, [])
        np.testing.assert_allclose(separate(data, [1, 5, 2047, 13, 9000]), expected, atol=1e-14)
        # A 120-degree phase offset yields a negative center coefficient.
        self.assertLess(np.corrcoef(expected[8192:-8192, 2], data[8192:-8192].mean(axis=1))[0,1], -.99)


class CascadeTests(unittest.TestCase):
    def stems(self, count):
        return {name: np.zeros((count, 2), dtype=np.float32) for name in ("bass", "drums", "vocals", "other")}

    def test_short_tracks_and_end_of_stream_preserve_frame_count(self):
        for count in (1, 17, 2047, 2048, 2049):
            stems = self.stems(count)
            stems["vocals"][:] = .1
            out = render(stems, chunks=[1,3])
            self.assertEqual(out.shape, (count, 8))
            self.assertTrue(np.isfinite(out).all())
            self.assertGreater(float(np.max(np.abs(out))), 0)

    def test_all_presets_and_chunk_boundaries(self):
        rng = np.random.default_rng(430)
        stems = {name: rng.normal(0,.015,(32031,2)).astype(np.float32) for name in self.stems(1)}
        for preset in PRESETS:
            with self.subTest(preset=preset):
                settings = CascadeSettings(preset=preset)
                whole = render(stems, settings)
                chunked = render(stems, settings, [1,37,2048,9001,53,8192])
                np.testing.assert_allclose(whole, chunked, atol=1e-7, rtol=2e-6)
                self.assertTrue(np.isfinite(whole).all())
                self.assertGreater(float(np.std(whole[:, 4] - whole[:, 6])), .001)

    def test_mono_front_rear_routes_and_lfe_from_stem_sum(self):
        count = 15013
        stems = self.stems(count)
        stems["vocals"][:, 0] = .1*np.sin(2*np.pi*1500*np.arange(count)/48000)
        stems["vocals"][:, 1] = stems["vocals"][:, 0]
        for route, channels in (("mono", (2,)), ("front", (0,1)), ("rear", (4,5))):
            out = render(stems, CascadeSettings(preset="none", routes=(("vocals",route),)))
            for channel in range(8):
                if channel not in channels and channel != 3:
                    np.testing.assert_array_equal(out[:,channel], 0)
            self.assertGreater(float(np.std(out[:,channels[0]])), .025)
        def bass(frequency):
            stems = self.stems(24000)
            stems["bass"][:] = (.2*np.sin(2*np.pi*frequency*np.arange(24000)/48000))[:,None]
            return np.std(render(stems)[12000:, 3])
        self.assertGreater(bass(40), 1000*bass(4000))

    def test_center_gain_and_quad_fold_for_common_tone(self):
        stems = self.stems(48000)
        tone = .15*np.sin(2*np.pi*750*np.arange(48000)/48000)
        stems["vocals"][:] = tone[:, None]
        steady = slice(20000, -20000)
        center = render(stems, CascadeSettings(preset="none"))
        expected = tone[steady] * 10**((-6.02+2)/20)
        np.testing.assert_allclose(center[steady, 2], expected, atol=1e-6)
        quad = render(stems, CascadeSettings(preset="none", routes=(("vocals","4.0"),)))
        np.testing.assert_array_equal(quad[:, 2], 0)
        np.testing.assert_allclose(quad[steady, 0], expected/np.sqrt(2), atol=1e-6)
        np.testing.assert_allclose(quad[steady, 1], expected/np.sqrt(2), atol=1e-6)

    def test_stems_are_combined_before_nonlinear_center_analysis(self):
        rng = np.random.default_rng(1390)
        stems = self.stems(15031)
        stems["vocals"] = rng.normal(0,.02,(15031,2))
        stems["other"] = rng.normal(0,.02,(15031,2))
        combined = {name: values.copy() for name,values in stems.items()}
        combined["vocals"] += combined["other"]
        combined["other"].fill(0)
        np.testing.assert_allclose(render(stems), render(combined), atol=1e-7)

    def test_validates_routes_and_stream_lifecycle(self):
        for values in (("vocals","bad"),("unknown","front")):
            with self.assertRaises(ValueError): CascadeSettings(routes=(values,))
        stems = self.stems(2)
        mixer = SurroundUpmixMixer()
        mixer.process(stems, sum(stems.values()))
        self.assertEqual(mixer.flush().shape, (mixer.latency_samples, 8))
        self.assertEqual(mixer.flush().shape, (0,8))
        with self.assertRaises(ValueError): mixer.process(stems, sum(stems.values()))


if __name__ == "__main__":
    unittest.main()
