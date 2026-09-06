import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from check_fast_rendering_port import verify_archive


class PortIdentityTest(unittest.TestCase):
    def test_identity_match_does_not_execute_or_extract(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fr-linux.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("starsector-fr.sh", "exit 99")
            expected = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "entries": {"starsector-fr.sh": hashlib.sha256(b"exit 99").hexdigest()}}
            self.assertEqual("matched", verify_archive(path, expected)["identity"])
            self.assertEqual([path], list(Path(directory).iterdir()))
            path.write_bytes(path.read_bytes() + b"changed")
            with self.assertRaisesRegex(ValueError, "release bytes changed"):
                verify_archive(path, expected)

    def test_outer_identity_is_not_enough_for_a_wrong_entry_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fr-macos.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("fr.jar", b"fixture")
            expected = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "entries": {"fr.jar": "0" * 64}}
            with self.assertRaisesRegex(ValueError, "entry changed"):
                verify_archive(path, expected)


if __name__ == "__main__":
    unittest.main()
