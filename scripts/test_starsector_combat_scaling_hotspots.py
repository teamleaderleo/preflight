import datetime
import json
import pathlib
import tempfile
import unittest

import starsector_combat_scaling_hotspots as module


def execution_event(epoch_seconds, *methods):
    frames = []
    for value in methods:
        owner, name = value.rsplit(".", 1)
        frames.append({"method": {"type": {"name": owner.replace(".", "/")}, "name": name}})
    start = datetime.datetime.fromtimestamp(epoch_seconds, datetime.timezone.utc).isoformat()
    return {"values": {
        "startTime": start,
        "sampledThread": {"javaName": "main"},
        "stackTrace": {"frames": frames},
    }}


def calibration_event(epoch_seconds):
    start = datetime.datetime.fromtimestamp(epoch_seconds, datetime.timezone.utc).isoformat()
    return {"values": {"startTime": start}}


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

    def test_window_alignment_finds_late_battle_owner_inside_one_run(self):
        base = 1_704_067_200.0
        payload = {
            "runId": "r1",
            "cellId": "duration",
            "samples": [],
        }
        sampled = []
        for bucket, wrecks in enumerate((0, 2, 4, 6)):
            center = base + 5.0 + bucket * 10.0
            payload["samples"].append({
                "battleId": 1,
                "combatElapsedSeconds": bucket * 10.0 + 5.0,
                "epochMillis": center * 1000.0,
                "wrecks": wrecks,
                "advanceMicros": 1000.0 + wrecks * 100.0,
            })
            costly = bucket + 1
            for sample_index in range(4):
                method = "com.fs.starfarer.combat.WreckCleanup.scan" \
                    if sample_index < costly else "com.fs.starfarer.combat.Stable.advance"
                sampled.append(execution_event(
                    center - 1.5 + sample_index,
                    method,
                    "com.fs.starfarer.combat.CombatEngine.advance",
                ))
        calibration = [calibration_event(base + offset) for offset in (0.0, 1.0, 2.0)]

        def loader(path, names, depth=1, jfr=None):
            return sampled if names == ["jdk.ExecutionSample"] else calibration

        with tempfile.TemporaryDirectory() as directory:
            workload = pathlib.Path(directory) / "workload.json"
            workload.write_text(json.dumps(payload), encoding="utf-8")
            observations = module.observe_windows(
                workload,
                "fixture.jfr",
                "wrecks",
                bucket_seconds=10.0,
                minimum_combat_samples=3,
                event_loader=loader,
            )

        ranked = module.associations(observations, center_within_run=True)
        self.assertEqual(4, len(observations))
        self.assertEqual("com.fs.starfarer.combat.WreckCleanup.scan", ranked[0].method)
        self.assertGreater(ranked[0].correlation, 0.9)
        self.assertGreater(ranked[0].high_minus_low_points, 50.0)

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

    def test_requires_four_observations_and_predictor_variation(self):
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
