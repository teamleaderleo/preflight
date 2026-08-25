import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("save_state_guard.py")
spec = importlib.util.spec_from_file_location("save_state_guard", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class SaveStateGuardTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.saves = Path(self.temporary.name) / "saves"
        self.selected = self.write("save_Disposable", "campaign.xml", "before")
        self.other = self.write("save_Main", "campaign.xml", "keep")
        self.write("common", "shaderlib_cache_hash.data", "mutable global state")

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, directory, name, content):
        path = self.saves / directory
        path.mkdir(parents=True, exist_ok=True)
        (path / name).write_text(content, encoding="utf-8")
        return path

    def test_snapshot_hashes_campaigns_and_excludes_global_state(self):
        result = module.snapshot(self.saves, self.selected.name)
        self.assertEqual({"save_Disposable", "save_Main"}, set(result["campaignSaves"]))
        self.assertNotIn("common", result["campaignSaves"])
        self.assertEqual("save_Disposable", result["selectedSave"])

    def test_compare_accepts_only_the_selected_save_changing(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        result = module.compare(before, self.saves)
        self.assertTrue(result["accepted"])
        self.assertTrue(result["selectedSaveChanged"])
        self.assertTrue(result["otherCampaignSavesUnchanged"])
        self.assertEqual(["save_Disposable"], result["changedCampaignSaves"])

    def test_compare_rejects_a_sibling_save_change(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        (self.other / "campaign.xml").write_text("unexpected", encoding="utf-8")
        result = module.compare(before, self.saves)
        self.assertFalse(result["accepted"])
        self.assertEqual(["save_Main"], result["unexpectedChangedCampaignSaves"])

    def test_compare_rejects_a_run_that_did_not_save(self):
        before = self.snapshot_file()
        result = module.compare(before, self.saves)
        self.assertFalse(result["accepted"])
        self.assertFalse(result["selectedSaveChanged"])
        self.assertIn("a save write was not observed", result["reasons"][0])

    def test_compare_rejects_selected_save_removal(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").unlink()
        self.selected.rmdir()
        result = module.compare(before, self.saves)
        self.assertFalse(result["accepted"])
        self.assertFalse(result["selectedSavePresent"])
        self.assertEqual(["save_Disposable"], result["changedCampaignSaves"])
        self.assertIn("was removed", result["reasons"][0])

    def test_compare_rejects_a_new_sibling_campaign(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        self.write("save_New", "campaign.xml", "new")
        result = module.compare(before, self.saves)
        self.assertFalse(result["accepted"])
        self.assertEqual(["save_New"], result["unexpectedChangedCampaignSaves"])

    def test_snapshot_rejects_save_symlinks(self):
        linked = self.saves / "save_Linked"
        try:
            linked.symlink_to(self.selected, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"symbolic links unavailable: {error}")
        with self.assertRaises(module.GuardError):
            module.snapshot(self.saves, self.selected.name)

    def test_snapshot_rejects_a_symlink_inside_a_save(self):
        linked = self.selected / "borrowed.xml"
        try:
            linked.symlink_to(self.other / "campaign.xml")
        except OSError as error:
            self.skipTest(f"symbolic links unavailable: {error}")
        with self.assertRaises(module.GuardError):
            module.snapshot(self.saves, self.selected.name)

    def test_snapshot_rejects_a_hard_link_that_is_not_an_independent_copy(self):
        linked = self.selected / "borrowed.xml"
        try:
            os.link(self.other / "campaign.xml", linked)
        except OSError as error:
            self.skipTest(f"hard links unavailable: {error}")
        with self.assertRaises(module.GuardError):
            module.snapshot(self.saves, self.selected.name)

    def test_selected_save_must_be_one_starsector_directory_name(self):
        for value in ("", ".", "..", "save_Disposable/campaign.xml", "Disposable"):
            with self.subTest(value=value), self.assertRaises(module.GuardError):
                module.snapshot(self.saves, value)

    def snapshot_file(self):
        path = Path(self.temporary.name) / "before.json"
        path.write_text(json.dumps(module.snapshot(self.saves, self.selected.name)), encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
