import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("capture_loaded_save_identity.py")
SPEC = importlib.util.spec_from_file_location("capture_loaded_save_identity", SCRIPT)
identity = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = identity
SPEC.loader.exec_module(identity)


class LoadedSaveIdentityTest(unittest.TestCase):
    def make_game(self, root: Path, selected: str = "save_Test") -> Path:
        game = root / "Starsector"
        (game / "logs").mkdir(parents=True)
        save = game / "saves" / selected
        save.mkdir(parents=True)
        (save / "campaign.xml").write_text("campaign", encoding="utf-8")
        (save / "descriptor.xml").write_text("descriptor", encoding="utf-8")
        return game

    def test_captures_latest_loaded_save_and_exact_tree(self):
        with tempfile.TemporaryDirectory() as temporary:
            game = self.make_game(Path(temporary))
            other = game / "saves" / "save_Old"
            other.mkdir()
            (other / "campaign.xml").write_text("old", encoding="utf-8")
            log = game / "logs" / "starsector.log"
            log.write_text(
                "Reading save data from [../../../saves/save_Old/descriptor.xml]\n"
                "Loading ../../../saves/save_Test...\n",
                encoding="utf-8",
            )
            result = identity.capture(game, log)
        self.assertEqual("save_Test", result["selectedSave"])
        self.assertEqual(2, result["tree"]["files"])
        self.assertEqual(
            {"campaign.xml", "descriptor.xml"}, set(result["tree"]["entries"])
        )

    def test_before_comparison_detects_unchanged_and_changed_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            game = self.make_game(root)
            log = game / "logs" / "starsector.log"
            log.write_text(
                "Reading save data from [../../../saves/save_Test/descriptor.xml]\n",
                encoding="utf-8",
            )
            before = identity.capture(game, log)
            before_path = root / "before.json"
            before_path.write_text(json.dumps(before), encoding="utf-8")
            unchanged = identity.capture(game, log, before_path)
            (game / "saves" / "save_Test" / "campaign.xml").write_text(
                "changed", encoding="utf-8"
            )
            changed = identity.capture(game, log, before_path)
        self.assertTrue(unchanged["comparison"]["contentUnchanged"])
        self.assertFalse(changed["comparison"]["contentUnchanged"])

    def test_rejects_save_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            game = self.make_game(root)
            log = game / "logs" / "starsector.log"
            log.write_text(
                "Loading ../../../saves/save_Test...\n", encoding="utf-8"
            )
            (game / "saves" / "save_Test" / "linked").symlink_to(
                game / "saves" / "save_Test" / "campaign.xml"
            )
            with self.assertRaises(identity.IdentityError):
                identity.capture(game, log)

    def test_requires_a_loaded_save_marker(self):
        with tempfile.TemporaryDirectory() as temporary:
            game = self.make_game(Path(temporary))
            log = game / "logs" / "starsector.log"
            log.write_text("main menu only\n", encoding="utf-8")
            with self.assertRaises(identity.IdentityError):
                identity.capture(game, log)


if __name__ == "__main__":
    unittest.main()
