import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("summarize_combat_cohort.py")
SPEC = importlib.util.spec_from_file_location("summarize_combat_cohort", SCRIPT)
cohort = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = cohort
SPEC.loader.exec_module(cohort)


class CombatCohortSummaryTest(unittest.TestCase):
    def write_run(self, root: Path, name: str, candidate: bool, fps: float) -> Path:
        path = root / name
        path.mkdir()
        frame = {
            "measurementWindow": {
                "frames": 600, "totalActiveNanos": 30_000_000_000,
                "averageFps": fps, "p50Micros": 50_000, "p95Micros": 80_000,
                "p99Micros": 120_000, "maximumMicros": 180_000,
                "onePercentLowFps": 8.0, "over50Millis": 100,
                "over100Millis": 10,
                "stutterProfile": {"stutterBurdenMillisPerSecond": 300.0},
            },
            "combatWorkloadFingerprint": {
                "combatSecondsElapsed": 38.0,
                "begin": {"ships": 102, "projectiles": 3, "missiles": 3},
                "end": {"ships": 125, "projectiles": 200, "missiles": 100,
                        "combatOver": False},
            },
            "openGlTextureBindDedup": {
                "requested": candidate, "active": candidate, "runtimeDisabled": False,
                "problem": None, "unexpectedThreadCalls": 0,
                "suppressedCalls": 1_700_000 if candidate else 0,
                "suppressedPercent": 38.0 if candidate else None,
            },
            "presentationPolicy": {
                "lastSwapInterval": 1, "forceVsyncOff": False,
                "frameRateCap": "unchanged",
            },
        }
        (path / "runtime-frame-report.json").write_text(
            json.dumps({"frameTimes": frame}), encoding="utf-8")
        (path / "run.json").write_text(json.dumps({
            "preflightJarSha256": "jar", "javaVersion": "21", "installRoot": "/game",
            "textureProfileFingerprint": "texture", "textureManifestSha256": "manifest",
            "textureIndexSha256": "index", "directLaunchSettings": {"resolution": "x"},
            "optimizationPreset": "recommended", "disabledOptimizationDomains": [],
            "adapterMode": "ENABLED", "combatJvmSafeguard": {"active": True},
            "macRosettaGcPolicy": {"active": True}, "outcome": "COMPLETED",
            "lifecycleEvidence": {"fatalDetected": False},
        }), encoding="utf-8")
        (path / "profile.json").write_text(json.dumps({
            "profileFingerprint": "profile", "resolvedModCount": 83,
        }), encoding="utf-8")
        (path / "smoke-evidence.json").write_text(json.dumps({
            "scenario": "stress", "status": "passed",
            "steps": [{"id": "verify-zoom-out", "status": "passed", "detail": "exact"}],
        }), encoding="utf-8")
        return path

    def test_compact_report_includes_tail_deltas_and_gates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [
                cohort.load_run(self.write_run(root, "a1", False, 20.0)),
                cohort.load_run(self.write_run(root, "b1", True, 22.0)),
            ]
            rendered = cohort.render(runs)
        self.assertIn("gates: identity=PASS  workload=PASS  adapter=PASS", rendered)
        self.assertIn("B vs A (arm medians):", rendered)
        self.assertIn("1%low", rendered)
        self.assertIn("38.00% binds suppressed", rendered)


if __name__ == "__main__":
    unittest.main()
