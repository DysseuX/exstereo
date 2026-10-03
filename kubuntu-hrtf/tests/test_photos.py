# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from spatial_hrtf.photos import calibrated_crop, extract_contours, prepare_photo, load_contours, open_photo


class PhotoTests(unittest.TestCase):
    def test_physical_scale_and_canal_center(self):
        image = Image.new("RGB", (950, 950), "black")
        ImageDraw.Draw(image).rectangle((470, 470, 480, 480), fill="white")
        crop = calibrated_crop(image, (475, 475), ((100, 100), (400, 100)), 30)
        self.assertEqual(crop.size, (512, 512))
        self.assertGreater(np.asarray(crop)[256, 256, 0], 200)
        # A marker 47.5 mm away lies at the right crop edge.
        image.putpixel((949, 475), (255, 255, 255))
        mirrored = calibrated_crop(image, (475, 475), ((100, 100), (400, 100)), 30, True)
        original = calibrated_crop(image, (475, 475), ((100, 100), (400, 100)), 30)
        np.testing.assert_array_equal(np.asarray(mirrored), np.asarray(original)[:, ::-1])

    def test_invalid_calibration_and_blank_photo(self):
        image = Image.new("RGB", (512, 512))
        with self.assertRaises(ValueError):
            calibrated_crop(image, (256, 256), ((50, 50), (50, 50)), 30)
        with self.assertRaises(ValueError):
            calibrated_crop(image, (900, 20), ((50, 50), (250, 50)), 30)
        with self.assertRaises(ValueError):
            extract_contours(image)

    def test_contours_are_real_edges_and_manual_format_is_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            image = Image.new("RGB", (600, 600), (120, 120, 120))
            draw = ImageDraw.Draw(image)
            draw.ellipse((150, 90, 450, 500), outline="white", width=12)
            draw.arc((225, 150, 405, 440), 40, 330, fill="black", width=12)
            source = Path(tmp)/"photo.png"
            image.save(source)
            contours = prepare_photo(source, (300, 300), ((40, 20), (240, 20)), 40)
            values = np.asarray(contours)
            self.assertEqual(values.shape, (256, 256))
            self.assertGreater(np.mean(values > 80), 0.005)
            self.assertLess(np.mean(values > 80), 0.25)
            target = Path(tmp)/"contours.png"
            contours.save(target)
            np.testing.assert_array_equal(np.asarray(load_contours(target)), values)
            with self.assertRaises(ValueError):
                load_contours(source)

    def test_exif_rotation_precedes_coordinates(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)/"rotated.jpg"
            image = Image.new("RGB", (120, 200), "white")
            exif = Image.Exif()
            exif[274] = 6
            image.save(source, exif=exif)
            self.assertEqual(open_photo(source).size, (200, 120))


if __name__ == "__main__":
    unittest.main()
