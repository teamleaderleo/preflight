import contextlib
import io
import unittest

import starsector_gameplay_hotspots as module


def event(thread, *methods):
    frames = []
    for value in methods:
        owner, name = value.rsplit(".", 1)
        frames.append({"method": {"type": {"name": owner.replace(".", "/")}, "name": name}})
    return {"values": {"sampledThread": {"javaName": thread}, "stackTrace": {"frames": frames}}}


class GameplayHotspotTest(unittest.TestCase):
    def test_loop_root_classifies_state_instead_of_an_incidental_package(self):
        campaign = [
            "com.fs.starfarer.combat.Helper.lookup",
            "com.fs.starfarer.campaign.CampaignState.advance",
        ]
        self.assertEqual("campaign", module.gameplay_state(campaign))
        self.assertEqual("combat", module.gameplay_state([
            "example.Mod.advance",
            "com.fs.starfarer.combat.CombatEngine.advance",
        ]))

    def test_report_normalizes_slashes_and_excludes_non_main_threads(self):
        original_events = module.events
        original_thread = module.thread_of
        try:
            module.events = lambda *args, **kwargs: [
                event("main", "mod.Costly.work", "com.fs.starfarer.campaign.CampaignState.advance"),
                event("worker", "mod.Worker.work", "com.fs.starfarer.campaign.CampaignState.advance"),
                event("main", "mod.Combat.work", "com.fs.starfarer.combat.CombatEngine.advance"),
            ]
            module.thread_of = lambda value: value["values"]["sampledThread"]["javaName"]
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                module.report("fixture.jfr", top=5)
            rendered = output.getvalue()
            self.assertIn("campaign=1, combat=1, other=0", rendered)
            self.assertIn("mod.Costly.work", rendered)
            self.assertIn("mod.Combat.work", rendered)
            self.assertNotIn("mod.Worker.work", rendered)
        finally:
            module.events = original_events
            module.thread_of = original_thread


if __name__ == "__main__":
    unittest.main()
