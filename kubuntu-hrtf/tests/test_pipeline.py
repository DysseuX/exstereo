# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import json
from pathlib import Path
import tempfile
import unittest

from spatial_hrtf.pipeline import build_profile


class PublishingTests(unittest.TestCase):
    def manifest(self, folder):
        path = folder / "manifest.json"
        path.write_text(json.dumps({
            "left": {"contours": "missing-left.png"},
            "right": {"contours": "missing-right.png"},
            "head_width_cm": 16, "head_depth_cm": 20,
        }))
        return path

    def test_existing_profile_and_report_remain_together(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            output = folder / "profile"
            output.mkdir()
            (output / "personal.sofa").write_bytes(b"existing-profile")
            (output / "report.json").write_bytes(b"existing-report")
            with self.assertRaises(FileExistsError):
                build_profile(self.manifest(folder), output)
            self.assertEqual((output / "personal.sofa").read_bytes(), b"existing-profile")
            self.assertEqual((output / "report.json").read_bytes(), b"existing-report")
            self.assertEqual(len(list(output.iterdir())), 2)

    def test_failed_preparation_cleans_temporary_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            output = folder / "profile"
            with self.assertRaises(FileNotFoundError):
                build_profile(self.manifest(folder), output, log=lambda *a, **k: None)
            self.assertEqual(list(output.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
