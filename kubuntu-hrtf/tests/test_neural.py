# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Real published-weight inference; run only inside run-limited.sh."""
import unittest

import numpy as np
from PIL import Image, ImageDraw

from spatial_hrtf.neural import Predictor


def synthetic_contour(alternative=False):
    image = Image.new("L", (256, 256))
    draw = ImageDraw.Draw(image)
    draw.ellipse((55, 30, 190, 230), outline=255, width=2)
    draw.arc((85, 65, 165, 200), 30, 300, fill=255, width=2)
    draw.ellipse((105, 110, 140, 150), outline=255, width=2)
    if alternative:
        draw.ellipse((35, 80, 120, 210), outline=255, width=3)
    return image


class NeuralTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = Predictor()

    def test_actual_weights_shape_repeatability_and_photo_sensitivity(self):
        positions = np.array([[0, 0, 1], [180, 0, 1], [0, 40, 1], [40, 0, 1], [320, 0, 1]])
        image = synthetic_contour()
        first = self.model.predict(image, positions)
        second = self.model.predict(image, positions)
        altered = self.model.predict(synthetic_contour(True), positions)
        self.assertEqual(first.shape, (5, 257))
        self.assertTrue(np.isfinite(first).all())
        np.testing.assert_array_equal(first, second)
        self.assertGreater(float(np.max(np.abs(first-altered))), 0.01)
        self.assertGreater(float(np.max(np.abs(first[0]-first[1]))), 0.01)
        self.assertGreater(float(np.max(np.abs(first[0]-first[2]))), 0.01)
        # Same front-plane pinna response, with head azimuth handled by VIKING.
        np.testing.assert_allclose(first[0], first[3], atol=1e-4)
        np.testing.assert_allclose(first[0], first[4], atol=1e-4)


if __name__ == "__main__":
    unittest.main()
