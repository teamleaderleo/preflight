import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("summarize_nex_market_list_cohort.py")
SPEC = importlib.util.spec_from_file_location("summarize_nex_market_list_cohort", SCRIPT)
cohort = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = cohort
SPEC.loader.exec_module(cohort)


class NexMarketListCohortSummaryTest(unittest.TestCase):
    def write_run(
        self,
        root: Path,
        name: str,
        candidate: bool,
        fps: float,
        tree: str = "save-tree",
        fatal: bool = False,
    ) -> Path:
        path = root / name
        path.mkdir()
        window = {
            "frames": 1400,
            "totalActiveNanos": 45_000_000_000,
            "averageFps": fps,
            "p50Micros": 22_000,
            "p95Micros": 30_000,
            "p99Micros": 40_000,
            "maximumMicros": 90_000,
            "onePercentLowFps": 25.0,
            "over50Millis": 4,
            "over100Millis": 0,
            "stutterProfile": {"stutterBurdenMillisPerSecond": 5.0},
        }
        paused = dict(window)
        paused.update({
            "frames": 2600,
            "p99Micros": 33_000,
            "onePercentLowFps": 30.3,
        })
        probe = {
            "planId": cohort.PLAN_ID,
            "requested": candidate,
            "shadowRequested": False,
            "enabled": candidate,
            "shadowEnabled": False,
            "healthy": True,
            "nexInstalled": candidate,
            "coreInstalled": candidate,
            "scopesBegun": 4 if candidate else 0,
            "scopesEnded": 4 if candidate else 0,
            "nestedScopes": 0,
            "outsideScopeDeclines": 0,
            "misses": 156 if candidate else 0,
            "stores": 156 if candidate else 0,
            "hits": 5460 if candidate else 0,
            "shadowMatches": 0,
            "shadowMismatches": 0,
            "failures": 0,
            "maximumEntries": 39 if candidate else 0,
        }
        report = {
            "nexMarketListScope": probe,
            "frameTimes": {
                "measurementWindow": window,
                "campaignPausedAfter30SecondsActive": paused,
                "measurementOverhead": {"averageMicros": 2.5},
                "presentationPolicy": {
                    "lastSwapInterval": 1,
                    "forceVsyncOff": False,
                    "frameRateCap": "unchanged",
                },
                "openGlContext": {
                    "vendor": "Apple",
                    "renderer": "Apple M5",
                    "version": "2.1 Metal",
                },
                "displayPhases": {
                    "campaignAfter30SecondsActive": {
                        "preSwap": {"p99Micros": 39_000},
                        "nativeSwap": {"p99Micros": 500},
                    }
                },
            },
        }
        (path / "runtime-frame-report.json").write_text(json.dumps(report), encoding="utf-8")
        (path / "run.json").write_text(json.dumps({
            "preflightJarSha256": "jar",
            "wrapperRuntime": {"javaVersion": "21", "osArch": "aarch64"},
            "platform": "MAC",
            "installRoot": "/game",
            "textureProfileFingerprint": "texture",
            "textureManifestSha256": "manifest",
            "textureIndexSha256": "index",
            "directLaunchSettings": {"resolution": "1440x932"},
            "optimizationPreset": "recommended",
            "disabledOptimizationDomains": [],
            "recordingMode": "OFF",
            "campaignTimes": False,
            "smoothFramePacing": False,
            "adapterMode": "ENABLED",
            "adapterPlanScope": "full",
            "combatJvmSafeguard": {"active": True},
            "macRosettaGcPolicy": {"active": True},
            "outcome": "FATAL_LOG_EVIDENCE" if fatal else "COMPLETED",
            "exitCode": 6 if fatal else 0,
            "lifecycleEvidence": {"fatalDetected": fatal},
        }), encoding="utf-8")
        (path / "profile.json").write_text(json.dumps({
            "profileFingerprint": "profile", "resolvedModCount": 83,
        }), encoding="utf-8")
        (path / "smoke-evidence.json").write_text(json.dumps({
            "scenario": "campaign-nex-economy-info-paused-unpaused-thin",
            "status": "passed",
        }), encoding="utf-8")
        disabled = ["probe-a", "probe-b"] + ([] if candidate else [cohort.PLAN_ID])
        (path / "adapter.json").write_text(json.dumps({
            "planControl": {"scope": "full", "disabledPlans": disabled},
        }), encoding="utf-8")
        (path / "adapter-health.json").write_text(json.dumps({
            "status": "ACTIVE",
            "mode": "ENABLED",
            "transformerInstalled": True,
            "killSwitchActive": False,
            "sourceBindingRejected": 0,
            "unavailablePlans": 0,
            "transformationsDeclined": 0,
            "containedFailures": 0,
            "cacheRejectionSignals": 0,
            "wrapperFailureSignals": 0,
            "runtimeIntegrityFailures": 0,
        }), encoding="utf-8")
        (path / "save-identity.json").write_text(json.dumps({
            "format": "starsector-preflight-loaded-save-identity-v1",
            "installRoot": "/game",
            "selectedSave": "save_Test",
            "tree": {"treeSha256": tree},
            "comparison": {
                "beforeAvailable": True,
                "sameSelectedSave": True,
                "contentUnchanged": True,
            },
        }), encoding="utf-8")
        return path

    def complete_runs(self, root: Path) -> list[cohort.Run]:
        paths = [
            self.write_run(root, "b1", True, 44.0),
            self.write_run(root, "a1", False, 42.0),
            self.write_run(root, "a2", False, 41.0),
            self.write_run(root, "b2", True, 45.0),
        ]
        return [cohort.load_run(path) for path in paths]

    def test_complete_interleaved_cohort_passes_and_stays_compact(self):
        with tempfile.TemporaryDirectory() as temporary:
            runs = self.complete_runs(Path(temporary))
            rendered = cohort.render(runs)
        self.assertEqual((True, True, True, True), cohort.gates(runs))
        self.assertIn(
            "gates: identity=PASS  adapter=PASS  workload=PASS  cohort=PASS", rendered
        )
        self.assertIn("B vs A (arm medians):", rendered)
        self.assertIn("97.22% repeated list builds avoided", rendered)
        self.assertIn("presentation context p99", rendered)

    def test_fatal_lifecycle_rejects_adapter_gate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = [
                self.write_run(root, "b1", True, 44.0, fatal=True),
                self.write_run(root, "a1", False, 42.0),
            ]
            runs = [cohort.load_run(path) for path in paths]
            rendered = cohort.render(runs)
        self.assertFalse(cohort.gates(runs)[1])
        self.assertIn("process lifecycle was not a clean exit", rendered)

    def test_candidate_without_served_cache_rejects_adapter_gate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = self.write_run(root, "b1", True, 44.0)
            report_path = path / "runtime-frame-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["nexMarketListScope"]["hits"] = 0
            report_path.write_text(json.dumps(report), encoding="utf-8")
            run = cohort.load_run(path)
        self.assertFalse(run.adapter_ok)

    def test_save_identity_divergence_rejects_identity_gate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = [
                self.write_run(root, "a1", False, 42.0),
                self.write_run(root, "b1", True, 44.0, tree="different"),
            ]
            runs = [cohort.load_run(path) for path in paths]
        self.assertFalse(cohort.gates(runs)[0])

    def test_shadow_mode_is_not_misclassified_as_a_baseline(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = self.write_run(root, "shadow", False, 42.0)
            report_path = path / "runtime-frame-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["nexMarketListScope"]["shadowRequested"] = True
            report_path.write_text(json.dumps(report), encoding="utf-8")
            run = cohort.load_run(path)
        self.assertFalse(run.adapter_ok)

    def test_intrusive_probe_is_rejected_with_a_reason(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = self.write_run(root, "b1", True, 44.0)
            report_path = path / "runtime-frame-report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["frameTimes"]["gpuFrameTime"] = {"requested": True}
            report_path.write_text(json.dumps(report), encoding="utf-8")
            run = cohort.load_run(path)
        self.assertFalse(run.adapter_ok)
        self.assertIn("intrusive discovery instrumentation was active", run.adapter_problems)


if __name__ == "__main__":
    unittest.main()
