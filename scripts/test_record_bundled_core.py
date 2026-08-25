#!/usr/bin/env python3
"""Tests for the bundled core recorder. Run: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import pathlib
import tempfile
import unittest

from record_bundled_core import iso_date, upsert


class IsoDateTest(unittest.TestCase):
    def test_parses_the_servers_rfc_1123_date(self):
        self.assertEqual("2026-08-25", iso_date("Tue, 25 Aug 2026 13:02:02 GMT"))

    # Only the day is shown, so a late-evening GMT build must not be pulled back a day by a local
    # zone. Parsing keeps the server's own date rather than converting.
    def test_keeps_the_servers_day(self):
        self.assertEqual("2026-08-25", iso_date("Tue, 25 Aug 2026 23:59:59 GMT"))

    def test_a_missing_header_is_unknown_rather_than_fatal(self):
        self.assertEqual("?", iso_date(""))
        self.assertEqual("?", iso_date("   "))

    def test_a_malformed_header_is_unknown(self):
        self.assertEqual("?", iso_date("not a date"))


class UpsertTest(unittest.TestCase):
    def setUp(self):
        self.path = pathlib.Path(tempfile.mkdtemp()) / "bundled_cores.txt"

    def rows(self):
        return [
            line.split(None, 3)
            for line in self.path.read_text().splitlines()
            if line.strip() and not line.startswith("#")
        ]

    def test_records_a_core(self):
        upsert(self.path, "arm64-v8a", "snes9x_libretro", '"t1"', "2026-08-25")
        self.assertEqual([["arm64-v8a", "snes9x_libretro", '"t1"', "2026-08-25"]], self.rows())

    # The two ABIs are different binaries with different etags. Recording one must not touch the
    # other, or a 32-bit device would be told it holds the 64-bit build.
    def test_the_two_abis_are_separate_entries(self):
        upsert(self.path, "arm64-v8a", "snes9x_libretro", '"t64"', "2026-08-25")
        upsert(self.path, "armeabi-v7a", "snes9x_libretro", '"t32"', "2026-08-25")
        self.assertEqual(2, len(self.rows()))

    def test_re_recording_replaces_rather_than_appends(self):
        upsert(self.path, "arm64-v8a", "snes9x_libretro", '"old"', "2026-08-25")
        upsert(self.path, "arm64-v8a", "snes9x_libretro", '"new"', "2026-08-25")
        self.assertEqual([["arm64-v8a", "snes9x_libretro", '"new"', "2026-08-25"]], self.rows())

    # task cores skips a core already on disk, so its row must survive another core being fetched.
    def test_an_untouched_core_keeps_its_row(self):
        upsert(self.path, "arm64-v8a", "snes9x_libretro", '"s"', "2026-08-25")
        upsert(self.path, "arm64-v8a", "nestopia_libretro", '"n"', "2026-08-20")
        self.assertEqual(2, len(self.rows()))
        self.assertIn(["arm64-v8a", "snes9x_libretro", '"s"', "2026-08-25"], self.rows())

    def test_the_file_carries_a_header_explaining_itself(self):
        upsert(self.path, "arm64-v8a", "x_libretro", '"t"', "2026-08-25")
        self.assertTrue(self.path.read_text().startswith("#"))

    def test_rows_are_sorted_so_the_diff_is_readable(self):
        upsert(self.path, "arm64-v8a", "snes9x_libretro", '"s"', "2026-08-25")
        upsert(self.path, "arm64-v8a", "nestopia_libretro", '"n"', "2026-08-20")
        self.assertEqual(["nestopia_libretro", "snes9x_libretro"], [r[1] for r in self.rows()])


if __name__ == "__main__":
    unittest.main()
