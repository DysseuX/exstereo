# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-2.0-or-later

import errno
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from spatial_upmixer.pipeline import CancelledError, _publish


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / "encoded.opus"
        self.output = Path(self.temp.name) / "destination.opus"
        self.source.write_bytes(b"opus payload" * 100)

    def no_links(self):
        return patch("spatial_upmixer.pipeline.os.link",
                     side_effect=OSError(errno.EOPNOTSUPP, "No hard links"))

    def test_filesystem_without_hard_links(self):
        expected = self.source.read_bytes()
        with self.no_links():
            _publish(self.source, self.output, False, threading.Event())
        self.assertEqual(self.output.read_bytes(), expected)
        self.assertFalse(self.source.exists())

    def test_fallback_does_not_overwrite_existing_destination(self):
        self.output.write_bytes(b"keep this")
        with self.no_links(), self.assertRaises(FileExistsError):
            _publish(self.source, self.output, False, threading.Event())
        self.assertEqual(self.output.read_bytes(), b"keep this")
        self.assertTrue(self.source.exists())

    def test_cancel_during_fallback_removes_partial_copy(self):
        cancel = threading.Event()

        def cancel_before_copy(*args):
            cancel.set()
            raise OSError(errno.EOPNOTSUPP, "No hard links")

        with patch("spatial_upmixer.pipeline.os.link", side_effect=cancel_before_copy):
            with self.assertRaises(CancelledError):
                _publish(self.source, self.output, False, cancel)
        self.assertFalse(self.output.exists())
        self.assertTrue(self.source.exists())


if __name__ == "__main__":
    unittest.main()
