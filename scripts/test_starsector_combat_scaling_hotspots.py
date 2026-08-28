import json
import pathlib
import tempfile
import unittest

import starsector_combat_scaling_hotspots as module


class CombatScalingHotspotsTest(unittest.TestCase):
    def test_ranks_exact_method_whose_sample_share_rises_with_predictor(self):
        observations = []
        for index, (predictor, bad_share) in enumerate(((10, 0.10), (20, 0.20), (30, 0.45), (40, 0.80))):
            observations.append(module.RunObservation(
                run_id=f"r{index}",
                cell_id=f"m{predictor}",
                predictor=float(predictor),
                advance_micros=500.0 + predictor * predictor,
                combat_samples=100,
                inclusive_shares={
                    "com.fs.starfarer.combat.Bad.scan": bad_share,
                    "com.fs.starfarer.combat.Stable.advance": 0.50,
                },
                leaf_shares={"com.fs.starfarer.combat.Bad.scan": bad_share / 2.0},
            ))

        ranked = module.associations(observations)

        self.assertEqual("com.fs.starfarer.combat.Bad.scan", ranked[0].method)
        self.assertGreater(ranked[0].correlation, 0.9)
        self.assertGreater(ranked[0].high_minus_low_points, 60.0)
        self.assertEqual(4, ranked[0].runs_with_samples)

    def test_load_workload_uses_report_level_predictor_and_sampled_advance(self):
        payload = {
            "runId": "r1",
            "cellId": "symmetric-1040",
            "battleDp": 1040.0,
            "samples": [
                {"advanceMicros": 1200.0},
                {"advanceMicros": 1800.0},
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "workload.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            run_id, cell_id, predictor, advance = module.load_workload(path, "battleDp")

        self.assertEqual("r1", run_id)
        self.assertEqual("symmetric-1040", cell_id)
        self.assertEqual(1040.0, predictor)
        self.assertEqual(1500.0, advance)

    def test_requires_four_runs_and_predictor_variation(self):
        observation = module.RunObservation(
            "r1", "c1", 10.0, 1000.0, 10,
            {"com.fs.starfarer.combat.Bad.scan": 0.1},
            {"com.fs.starfarer.combat.Bad.scan": 0.1},
        )
        with self.assertRaisesRegex(ValueError, "at least four"):
            module.associations([observation, observation, observation])
        with self.assertRaisesRegex(ValueError, "does not vary"):
            module.associations([observation, observation, observation, observation])


if __name__ == "__main__":
    unittest.main()
