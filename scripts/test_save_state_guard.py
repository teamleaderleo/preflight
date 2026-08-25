import contextlib
import hashlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("save_state_guard.py")
PILOT_PATH = Path(__file__).with_name("run-gameplay-pilot.sh")
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

    def test_gameplay_pilot_binds_cleanup_to_the_exact_process_lifetime(self):
        source = PILOT_PATH.read_text(encoding="utf-8")

        self.assertIn("python3 pgrep lsof ps awk", source)
        self.assertIn(
            '[[ -n "$cwd" && ( "$cwd" == "$resolved" || "$cwd" == "$resolved/"* ) ]]',
            source,
        )
        self.assertGreaterEqual(source.count("ps -o lstart="), 2)
        self.assertIn("printf '%s\\t%s\\n' \"$pid\" \"$started\"", source)
        self.assertIn('"$current_start" == "$recorded_start"', source)

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
        for value in (
                "", ".", "..", "save_Disposable/campaign.xml", "Disposable", "save_" + "x" * 251
        ):
            with self.subTest(value=value), self.assertRaises(module.GuardError):
                module.snapshot(self.saves, value)

    def test_pilot_attestation_binds_the_exact_save_boundary_engine_source_and_configuration(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"exact tested engine")
        pilot_evidence = self.pilot_evidence(engine)

        result = module.pilot_attestation(
            before_path=before,
            after_path=after,
            engine_path=engine,
            **pilot_evidence,
            selected_save=self.selected.name,
            source_revision="ab" * 20,
            source_dirty=False,
            process_exit_status=0,
            route_attested=True,
            recorded_at="2026-08-26T04:30:00Z",
            configuration=self.configuration(),
        )

        self.assertTrue(result["complete"])
        self.assertTrue(result["attested"])
        self.assertEqual("ab" * 20, result["source"]["revision"])
        self.assertEqual(hashlib.sha256(engine.read_bytes()).hexdigest(), result["engineJar"]["sha256"])
        self.assertEqual(hashlib.sha256(after.read_bytes()).hexdigest(), result["evidence"]["saveStateAfter"]["sha256"])
        self.assertTrue(result["evidence"]["saveBoundaryAccepted"])
        self.assertEqual("COMPLETED", result["evidence"]["run"]["outcome"])
        self.assertEqual("aa" * 32, result["evidence"]["profile"]["profileFingerprint"])
        self.assertEqual("ACTIVE", result["evidence"]["adapterHealth"]["status"])
        combat_coverage = result["evidence"]["routeCoverage"]["combatAfterCampaign"]
        self.assertEqual(2400, combat_coverage["frames"])
        self.assertEqual(240 * 1_000_000_000, combat_coverage["activeNanos"])
        self.assertTrue(combat_coverage["accepted"])
        self.assertEqual(self.configuration(), result["configuration"])
        self.assertEqual([], result["reasons"])

    def test_pilot_attestation_cannot_claim_reload_over_a_failed_run_or_boundary(self):
        before = self.snapshot_file()
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine, process_exit_status=1)

        with self.assertRaisesRegex(module.GuardError, "cannot be attested"):
            module.pilot_attestation(
                before_path=before,
                after_path=after,
                engine_path=engine,
                **pilot_evidence,
                selected_save=self.selected.name,
                source_revision="cd" * 20,
                source_dirty=True,
                process_exit_status=1,
                route_attested=True,
                recorded_at="2026-08-26T04:31:00Z",
                configuration=self.configuration(),
            )

    def test_pilot_attestation_rejects_a_comparison_from_another_before_snapshot(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        comparison = module.compare(before, self.saves)
        comparison["before"] = {}
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(comparison), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)

        with self.assertRaisesRegex(module.GuardError, "does not derive"):
            module.pilot_attestation(
                before_path=before,
                after_path=after,
                engine_path=engine,
                **pilot_evidence,
                selected_save=self.selected.name,
                source_revision="34" * 20,
                source_dirty=False,
                process_exit_status=0,
                route_attested=True,
                recorded_at="2026-08-26T04:31:30Z",
                configuration=self.configuration(),
            )

    def test_pilot_attestation_rejects_an_impossible_timestamp(self):
        before = self.snapshot_file()
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)

        with self.assertRaisesRegex(module.GuardError, "UTC second timestamp"):
            module.pilot_attestation(
                before_path=before,
                after_path=Path(self.temporary.name) / "missing.json",
                engine_path=engine,
                **pilot_evidence,
                selected_save=self.selected.name,
                source_revision="78" * 20,
                source_dirty=False,
                process_exit_status=0,
                route_attested=False,
                recorded_at="2026-99-99T04:35:00Z",
                configuration=self.configuration(),
            )

    def test_incomplete_pilot_attestation_records_a_missing_comparison(self):
        before = self.snapshot_file()
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)

        result = module.pilot_attestation(
            before_path=before,
            after_path=Path(self.temporary.name) / "missing.json",
            engine_path=engine,
            **pilot_evidence,
            selected_save=self.selected.name,
            source_revision="ef" * 20,
            source_dirty=False,
            process_exit_status=0,
            route_attested=False,
            recorded_at="2026-08-26T04:32:00Z",
            configuration=self.configuration(),
        )

        self.assertFalse(result["complete"])
        self.assertIsNone(result["evidence"]["saveStateAfter"])
        self.assertIn("the save-state comparison was not produced", result["reasons"])

    def test_pilot_attestation_refuses_linked_or_replaceable_evidence(self):
        before = self.snapshot_file()
        linked = Path(self.temporary.name) / "linked-before.json"
        try:
            os.link(before, linked)
        except OSError as error:
            self.skipTest(f"hard links unavailable: {error}")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)

        with self.assertRaisesRegex(module.GuardError, "hard-linked"):
            module.pilot_attestation(
                before_path=linked,
                after_path=Path(self.temporary.name) / "missing.json",
                engine_path=engine,
                **pilot_evidence,
                selected_save=self.selected.name,
                source_revision="12" * 20,
                source_dirty=False,
                process_exit_status=0,
                route_attested=False,
                recorded_at="2026-08-26T04:33:00Z",
                configuration=self.configuration(),
            )

    def test_operator_attestation_is_create_once(self):
        output = Path(self.temporary.name) / "operator-attestation.json"
        module._write_json_once(output, {"complete": False})

        with self.assertRaises(FileExistsError):
            module._write_json_once(output, {"complete": True})
        self.assertEqual({"complete": False}, json.loads(output.read_text(encoding="utf-8")))

    def test_bounded_evidence_digest_refuses_growth_past_its_ceiling(self):
        evidence = Path(self.temporary.name) / "evidence.bin"
        evidence.write_bytes(b"four")

        with self.assertRaisesRegex(module.GuardError, "exceeds 3 bytes"):
            module._stable_file_digest(
                evidence, maximum_bytes=3, label="test evidence"
            )

    def test_complete_pilot_requires_post_campaign_combat_coverage(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine, combat_frames=0)

        result = module.pilot_attestation(
            before_path=before,
            after_path=after,
            engine_path=engine,
            **pilot_evidence,
            selected_save=self.selected.name,
            source_revision="90" * 20,
            source_dirty=False,
            process_exit_status=0,
            route_attested=True,
            recorded_at="2026-08-26T04:33:30Z",
            configuration=self.configuration(),
        )

        self.assertTrue(result["attested"])
        self.assertFalse(result["complete"])
        self.assertIn("combatAfterCampaign", result["reasons"][0])

    def test_complete_pilot_requires_minimum_active_route_duration(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(
            engine,
            campaign_after_active_nanos=module.MIN_SETTLED_CAMPAIGN_ACTIVE_NANOS - 1,
        )

        result = module.pilot_attestation(
            before_path=before,
            after_path=after,
            engine_path=engine,
            **pilot_evidence,
            selected_save=self.selected.name,
            source_revision="98" * 20,
            source_dirty=False,
            process_exit_status=0,
            route_attested=True,
            recorded_at="2026-08-26T04:33:35Z",
            configuration=self.configuration(),
        )

        self.assertFalse(result["complete"])
        coverage = result["evidence"]["routeCoverage"]["settledCampaign"]
        self.assertGreaterEqual(coverage["frames"], coverage["minimumFrames"])
        self.assertLess(coverage["activeNanos"], coverage["minimumActiveNanos"])
        self.assertIn("settledCampaign", result["reasons"][0])

    def test_complete_pilot_requires_the_actual_adapter_evidence(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)
        pilot_evidence["adapter_path"].unlink()

        result = module.pilot_attestation(
            before_path=before,
            after_path=after,
            engine_path=engine,
            **pilot_evidence,
            selected_save=self.selected.name,
            source_revision="91" * 20,
            source_dirty=False,
            process_exit_status=0,
            route_attested=True,
            recorded_at="2026-08-26T04:33:40Z",
            configuration=self.configuration(),
        )

        self.assertFalse(result["complete"])
        self.assertIn("the pilot adapter report was not produced", result["reasons"])

    def test_complete_pilot_requires_the_enabled_adapter_to_apply(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine, transformations_applied=0)

        result = module.pilot_attestation(
            before_path=before,
            after_path=after,
            engine_path=engine,
            **pilot_evidence,
            selected_save=self.selected.name,
            source_revision="93" * 20,
            source_dirty=False,
            process_exit_status=0,
            route_attested=True,
            recorded_at="2026-08-26T04:33:45Z",
            configuration=self.configuration(),
        )

        self.assertFalse(result["complete"])
        self.assertIn("did not apply the reviewed runtime stack", result["reasons"][0])

    def test_complete_pilot_requires_a_clean_source_state(self):
        result = self.complete_pilot_result(source_dirty=True)

        self.assertFalse(result["complete"])
        self.assertIn("the pilot source state had uncommitted changes", result["reasons"])

    def test_complete_pilot_requires_a_completed_run_report(self):
        result = self.complete_pilot_result(run_outcome="FAILED")

        self.assertFalse(result["complete"])
        self.assertIn("the pilot run report did not record a completed launch", result["reasons"])

    def test_complete_pilot_rejects_contained_adapter_failures(self):
        result = self.complete_pilot_result(contained_failures=1)

        self.assertFalse(result["complete"])
        self.assertIn("the enabled adapter reported contained runtime failures", result["reasons"])

    def test_pilot_rejects_a_run_report_for_another_engine(self):
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)
        run = json.loads(pilot_evidence["run_path"].read_text(encoding="utf-8"))
        run["preflightJarSha256"] = "00" * 32
        pilot_evidence["run_path"].write_text(json.dumps(run), encoding="utf-8")

        with self.assertRaisesRegex(module.GuardError, "different engine JAR"):
            module.pilot_attestation(
                before_path=self.snapshot_file(),
                after_path=Path(self.temporary.name) / "missing.json",
                engine_path=engine,
                **pilot_evidence,
                selected_save=self.selected.name,
                source_revision="92" * 20,
                source_dirty=False,
                process_exit_status=0,
                route_attested=False,
                recorded_at="2026-08-26T04:33:50Z",
                configuration=self.configuration(),
            )

    def test_pilot_rejects_a_run_report_for_another_profile(self):
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)
        run = json.loads(pilot_evidence["run_path"].read_text(encoding="utf-8"))
        run["profile"] = str(Path(self.temporary.name) / "other-profile.json")
        pilot_evidence["run_path"].write_text(json.dumps(run), encoding="utf-8")

        with self.assertRaisesRegex(module.GuardError, "different profile report"):
            module.pilot_attestation(
                before_path=self.snapshot_file(),
                after_path=Path(self.temporary.name) / "missing.json",
                engine_path=engine,
                **pilot_evidence,
                selected_save=self.selected.name,
                source_revision="96" * 20,
                source_dirty=False,
                process_exit_status=0,
                route_attested=False,
                recorded_at="2026-08-26T04:33:52Z",
                configuration=self.configuration(),
            )

    def test_no_adapter_diagnostic_can_still_bind_a_complete_save_lifecycle(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        run_path = Path(self.temporary.name) / "run.json"
        profile_path = Path(self.temporary.name) / "profile.json"
        profile_path.write_text(json.dumps({
            "profileFingerprint": "aa" * 32,
            "installRoot": "/Applications/Starsector.app",
        }), encoding="utf-8")
        run_path.write_text(json.dumps({
            "outcome": "COMPLETED",
            "exitCode": 0,
            "adapterMode": "OFF",
            "preflightJarSha256": hashlib.sha256(engine.read_bytes()).hexdigest(),
            "profile": str(profile_path.absolute()),
            "installRoot": "/Applications/Starsector.app",
        }), encoding="utf-8")

        result = module.pilot_attestation(
            before_path=before,
            after_path=after,
            engine_path=engine,
            run_path=run_path,
            profile_path=profile_path,
            adapter_path=Path(self.temporary.name) / "missing-adapter.json",
            adapter_health_path=Path(self.temporary.name) / "missing-health.json",
            selected_save=self.selected.name,
            source_revision="94" * 20,
            source_dirty=False,
            process_exit_status=0,
            route_attested=True,
            recorded_at="2026-08-26T04:33:55Z",
            configuration=self.configuration(
                startupCaches=False,
                gameplayCaches=False,
                audioRepair=False,
                profile=False,
                adapter=False,
            ),
        )

        self.assertTrue(result["complete"])
        self.assertIsNone(result["evidence"]["adapter"])
        self.assertIsNone(result["evidence"]["routeCoverage"])

    def test_attest_command_writes_one_complete_bound_receipt(self):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)
        output = Path(self.temporary.name) / "operator-attestation.json"
        arguments = [
            "attest",
            "--before", str(before),
            "--after", str(after),
            "--engine", str(engine),
            "--run", str(pilot_evidence["run_path"]),
            "--profile-report", str(pilot_evidence["profile_path"]),
            "--adapter-report", str(pilot_evidence["adapter_path"]),
            "--adapter-health", str(pilot_evidence["adapter_health_path"]),
            "--selected", self.selected.name,
            "--source-revision", "56" * 20,
            "--source-dirty", "false",
            "--process-exit-status", "0",
            "--route-attested", "true",
            "--recorded-at", "2026-08-26T04:34:00Z",
            "--startup-caches", "true",
            "--gameplay-caches", "true",
            "--safer-jvm", "false",
            "--audio-repair", "true",
            "--profile", "true",
            "--adapter", "true",
            "--disabled-plans", "",
            "--output", str(output),
        ]

        self.assertEqual(0, module.main(arguments))
        self.assertTrue(json.loads(output.read_text(encoding="utf-8"))["complete"])
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, module.main(arguments))

    def test_attest_command_writes_an_incomplete_receipt_and_returns_one(self):
        before = self.snapshot_file()
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine)
        output = Path(self.temporary.name) / "incomplete-attestation.json"
        arguments = [
            "attest",
            "--before", str(before),
            "--after", str(Path(self.temporary.name) / "missing-after.json"),
            "--engine", str(engine),
            "--run", str(pilot_evidence["run_path"]),
            "--profile-report", str(pilot_evidence["profile_path"]),
            "--adapter-report", str(pilot_evidence["adapter_path"]),
            "--adapter-health", str(pilot_evidence["adapter_health_path"]),
            "--selected", self.selected.name,
            "--source-revision", "95" * 20,
            "--source-dirty", "false",
            "--process-exit-status", "0",
            "--route-attested", "false",
            "--recorded-at", "2026-08-26T04:34:10Z",
            "--startup-caches", "true",
            "--gameplay-caches", "true",
            "--safer-jvm", "false",
            "--audio-repair", "true",
            "--profile", "true",
            "--adapter", "true",
            "--output", str(output),
        ]

        self.assertEqual(1, module.main(arguments))
        self.assertFalse(json.loads(output.read_text(encoding="utf-8"))["complete"])

    def configuration(self, **overrides):
        result = {
            "startupCaches": True,
            "gameplayCaches": True,
            "saferJvm": False,
            "audioRepair": True,
            "profile": True,
            "adapter": True,
            "disabledPlans": "",
        }
        result.update(overrides)
        return result

    def complete_pilot_result(self, *, source_dirty=False, **evidence_overrides):
        before = self.snapshot_file()
        (self.selected / "campaign.xml").write_text("after", encoding="utf-8")
        after = Path(self.temporary.name) / "after.json"
        after.write_text(json.dumps(module.compare(before, self.saves)), encoding="utf-8")
        engine = Path(self.temporary.name) / "preflight.jar"
        engine.write_bytes(b"engine")
        pilot_evidence = self.pilot_evidence(engine, **evidence_overrides)
        return module.pilot_attestation(
            before_path=before,
            after_path=after,
            engine_path=engine,
            **pilot_evidence,
            selected_save=self.selected.name,
            source_revision="97" * 20,
            source_dirty=source_dirty,
            process_exit_status=0,
            route_attested=True,
            recorded_at="2026-08-26T04:33:47Z",
            configuration=self.configuration(),
        )

    def pilot_evidence(
            self,
            engine,
            *,
            process_exit_status=0,
            campaign_first_frames=1800,
            campaign_after_frames=3600,
            combat_frames=2400,
            campaign_first_active_nanos=25 * 1_000_000_000,
            campaign_after_active_nanos=60 * 1_000_000_000,
            combat_active_nanos=240 * 1_000_000_000,
            transformations_applied=12,
            contained_failures=0,
            run_outcome=None,
    ):
        root = Path(self.temporary.name)
        run_path = root / "run.json"
        profile_path = root / "profile.json"
        adapter_path = root / "adapter.json"
        adapter_health_path = root / "adapter-health.json"
        adapter = {
            "mode": "ENABLED",
            "transformerInstalled": True,
            "killSwitchActive": False,
            "transformationsApplied": transformations_applied,
            "containedFailures": contained_failures,
            "frameTimes": {
                "campaignFirst30SecondsActive": {
                    "frames": campaign_first_frames,
                    "totalActiveNanos": campaign_first_active_nanos,
                },
                "campaignAfter30SecondsActive": {
                    "frames": campaign_after_frames,
                    "totalActiveNanos": campaign_after_active_nanos,
                },
                "combatActive": {
                    "frames": combat_frames + 300,
                    "totalActiveNanos": combat_active_nanos + 5 * 1_000_000_000,
                },
                "combatAfterCampaignActive": {
                    "frames": combat_frames,
                    "totalActiveNanos": combat_active_nanos,
                },
            },
        }
        health = {
            "format": module.ADAPTER_HEALTH_FORMAT,
            "status": "ACTIVE",
            "mode": "ENABLED",
            "transformerInstalled": True,
            "killSwitchActive": False,
            "transformationsApplied": transformations_applied,
            "containedFailures": contained_failures,
        }
        run = {
            "outcome": run_outcome or (
                "COMPLETED" if process_exit_status == 0 else "FAILED"
            ),
            "exitCode": process_exit_status,
            "adapterMode": "ENABLED",
            "preflightJarSha256": hashlib.sha256(engine.read_bytes()).hexdigest(),
            "profile": str(profile_path.absolute()),
            "installRoot": "/Applications/Starsector.app",
            "adapterReport": str(adapter_path.absolute()),
            "adapterHealthReport": str(adapter_health_path.absolute()),
        }
        adapter_path.write_text(json.dumps(adapter), encoding="utf-8")
        adapter_health_path.write_text(json.dumps(health), encoding="utf-8")
        profile_path.write_text(json.dumps({
            "profileFingerprint": "aa" * 32,
            "installRoot": "/Applications/Starsector.app",
        }), encoding="utf-8")
        run_path.write_text(json.dumps(run), encoding="utf-8")
        return {
            "run_path": run_path,
            "profile_path": profile_path,
            "adapter_path": adapter_path,
            "adapter_health_path": adapter_health_path,
        }

    def snapshot_file(self):
        path = Path(self.temporary.name) / "before.json"
        path.write_text(json.dumps(module.snapshot(self.saves, self.selected.name)), encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
