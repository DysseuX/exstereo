# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Scientific invariants of the hybrid renderer, independent of ML weights."""
import tempfile
import unittest
from pathlib import Path

import numpy as np

from spatial_hrtf.hybrid import analytical_itd, combine_prtf, write_sofa


class HybridTests(unittest.TestCase):
    def setUp(self):
        self.positions = np.array([[0, 0, 1.47], [90, 0, 1.47], [270, 0, 1.47], [0, 90, 1.47]])
        self.base = np.zeros((4, 2, 128))
        self.base[:, :, 0] = 1
        self.prtf = np.zeros((4, 257))

    def test_arrival_direction_and_no_wraparound(self):
        result = combine_prtf(self.prtf, self.prtf, self.positions, self.base, 48000, 18, 20)
        arrivals = np.argmax(np.abs(result), axis=-1)
        actual = arrivals[:, 1] - arrivals[:, 0]
        expected = analytical_itd(self.positions, 18, 20) * 48000
        np.testing.assert_allclose(actual, expected, atol=1)
        self.assertGreater(actual[1], 0)
        self.assertLess(actual[2], 0)
        np.testing.assert_allclose(result[:, :, -64:], 0, atol=1e-10)
        self.assertTrue(np.isfinite(result).all())

    def test_one_gain_preserves_ear_and_direction_levels(self):
        base = self.base[:1].copy()
        base[:, 0] *= 0.5
        position = self.positions[:1]
        left = np.zeros((1, 257))
        right = np.full((1, 257), 6.020599913279624)
        result = combine_prtf(left, right, position, base, 48000, 18, 20)
        np.testing.assert_allclose(result[0, 1], result[0, 0] * 4, atol=1e-10)
        self.assertAlmostEqual(float(np.max(np.abs(result))), 0.95)

    def test_reject_nonfinite_prediction_and_invalid_head(self):
        bad = self.prtf.copy()
        bad[0, 0] = np.nan
        with self.assertRaises(ValueError):
            combine_prtf(bad, self.prtf, self.positions, self.base, 48000, 18, 20)
        with self.assertRaises(ValueError):
            analytical_itd(self.positions, 0, 20)

    def test_sofa_layout_and_zero_external_delay(self):
        from netCDF4 import Dataset

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "personal.sofa"
            write_sofa(path, self.base, self.positions, 48000, 18)
            with Dataset(path) as dataset:
                self.assertEqual(dataset.SOFAConventions, "SimpleFreeFieldHRIR")
                np.testing.assert_array_equal(dataset["Data.IR"][:], self.base)
                np.testing.assert_array_equal(dataset["SourcePosition"][:], self.positions)
                np.testing.assert_array_equal(dataset["Data.Delay"][:], np.zeros((1, 2)))
                self.assertEqual(dataset["ReceiverPosition"].shape, (2, 3, 1))
            with self.assertRaises(FileExistsError):
                write_sofa(path, self.base, self.positions, 48000, 18)


if __name__ == "__main__":
    unittest.main()
