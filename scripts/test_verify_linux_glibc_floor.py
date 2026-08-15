#!/usr/bin/env python3

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_linux_glibc_floor as glibc


class VerifyLinuxGlibcFloorTest(unittest.TestCase):
    def test_extracts_and_orders_versioned_glibc_symbols(self):
        versions = glibc.versions_from_readelf(
            "Name: GLIBC_2.17  Flags: none\nName: GLIBC_2.35\nName: GLIBC_2.2.5\n"
        )
        self.assertEqual({(2, 17), (2, 35), (2, 2)}, versions)
        self.assertEqual("2.35", glibc.format_version(max(versions)))

    def test_accepts_files_at_or_below_reviewed_floor(self):
        report = glibc.assert_floor(
            {
                "usr/bin/preflight": {(2, 17), (2, 34)},
                "runtime/lib/server/libjvm.so": {(2, 2), (2, 35)},
            },
            (2, 35),
        )
        self.assertEqual(2, report["elfFilesChecked"])
        self.assertEqual("2.35", report["maximumAllowedGlibc"])
        self.assertEqual("2.35", report["maximumObservedGlibc"])

    def test_rejects_any_packaged_file_above_reviewed_floor(self):
        with self.assertRaisesRegex(glibc.CompatibilityError, "GLIBC newer than 2.35"):
            glibc.assert_floor(
                {
                    "usr/bin/preflight": {(2, 34)},
                    "usr/lib/libnew.so": {(2, 38)},
                },
                (2, 35),
            )

    def test_empty_symbol_set_reports_no_observed_requirement(self):
        report = glibc.assert_floor({"static-binary": set()}, (2, 35))
        self.assertIsNone(report["maximumObservedGlibc"])

    def test_rejects_malformed_floor(self):
        with self.assertRaisesRegex(glibc.CompatibilityError, "invalid GLIBC version"):
            glibc.version_tuple("glibc-2.35")


if __name__ == "__main__":
    unittest.main()
