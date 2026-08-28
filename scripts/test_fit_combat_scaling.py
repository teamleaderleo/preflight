import json
import pathlib
import tempfile
import unittest

import fit_combat_scaling as module


class CombatScalingFitTest(unittest.TestCase):
    def test_quadratic_ship_cost_wins_run_blocked_model_selection(self):
        rows = []
        for run in range(4):
            for ships in (8, 16, 32, 64):
                rows.append({
                    "runId": f"run-{run}",
                    "cellId": f"ships-{ships}",
                    "battleId": 1,
                    "ships": float(ships),
                    "advanceMicros": 500.0 + 0.75 * ships * ships + run * 3.0,
                })

        analysis = module.analyze(rows)

        self.assertEqual("repeatable", analysis.evidence)
        self.assertEqual("ships:quadratic", analysis.fits[0].model.name)
        self.assertTrue(analysis.confirmed_superlinear)
        self.assertIsNotNone(analysis.fits[0].cv_rmse)

    def test_threshold_density_cost_wins_when_a_knee_appears(self):
        rows = []
        for run in range(4):
            for density in (10, 20, 30, 40, 50, 60):
                rows.append({
                    "runId": f"run-{run}",
                    "cellId": f"density-{density}",
                    "battleId": 1,
                    "nearbyEntitiesMean": float(density),
                    "advanceMicros": (
                        800.0 + 2.0 * density
                        + 30.0 * max(0.0, density - 30.0)
                        + run * 2.0
                    ),
                })

        analysis = module.analyze(rows)

        self.assertEqual("nearbyEntitiesMean:threshold@30", analysis.fits[0].model.name)
        self.assertEqual("threshold", analysis.fits[0].model.kind)
        self.assertTrue(module.worsening_nonlinearity(analysis.fits[0]))
        self.assertTrue(analysis.confirmed_superlinear)

    def test_density_interaction_wins_when_ordnance_cost_depends_on_nearby_entities(self):
        rows = []
        cells = ((10, 2), (10, 9), (40, 2), (40, 9))
        for run in range(4):
            for missiles, density in cells:
                rows.append({
                    "runId": f"run-{run}",
                    "cellId": f"m{missiles}-d{density}",
                    "battleId": 1,
                    "missiles": float(missiles),
                    "nearbyEntitiesMean": float(density),
                    "advanceMicros": 700.0 + 2.5 * missiles * density + run * 2.0,
                })

        analysis = module.analyze(rows)

        self.assertEqual("missiles*nearbyEntitiesMean", analysis.fits[0].model.name)
        self.assertEqual("interaction", analysis.fits[0].model.kind)
        self.assertTrue(analysis.confirmed_superlinear)

    def test_bucketing_uses_medians_inside_run_cell_battle_time_windows(self):
        rows = [
            {"runId": "r1", "cellId": "c1", "battleId": 2,
             "combatElapsedSeconds": 1.0, "ships": 10, "advanceMicros": 100},
            {"runId": "r1", "cellId": "c1", "battleId": 2,
             "combatElapsedSeconds": 5.0, "ships": 12, "advanceMicros": 300},
            {"runId": "r1", "cellId": "c1", "battleId": 2,
             "combatElapsedSeconds": 12.0, "ships": 20, "advanceMicros": 900},
        ]

        bucketed = module.bucket_rows(rows, seconds=10.0)

        self.assertEqual(2, len(bucketed))
        self.assertEqual(11.0, bucketed[0]["ships"])
        self.assertEqual(200.0, bucketed[0]["advanceMicros"])
        self.assertEqual(2, bucketed[0]["samples"])

    def test_report_loads_runtime_json_and_renders_issue_ready_summary(self):
        payload = {
            "runId": "run-a",
            "cellId": "ordinary",
            "battleDp": 320.0,
            "samples": [
                {"battleId": 1, "combatElapsedSeconds": 1.0, "ships": 8,
                 "advanceMicros": 1200.0},
                {"battleId": 1, "combatElapsedSeconds": 12.0, "ships": 10,
                 "advanceMicros": 1400.0},
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "report.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = module.report([str(path)], bucket_seconds=10.0)
            rendered = module.render_markdown(result)

        self.assertEqual("exploratory", result["evidence"])
        self.assertIn("Combat scaling fit", rendered)
        self.assertIn("Discovery rule", rendered)
        self.assertIn("ships", rendered)
        self.assertEqual(320.0, result["ranges"]["battleDp"]["median"])


if __name__ == "__main__":
    unittest.main()
