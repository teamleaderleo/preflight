"""Tests for the repeated startup benchmark harness.

The 2026-07-23 pilots failed for three reasons this harness has to keep out: one sample
per mode, a blocked order that let drift line up with a condition, and a single bad half
destroying the whole session. These cover all three, plus the report's refusal to call an
underpowered result reportable.
"""

import contextlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("run-startup-benchmark.sh")
SCRIPT_TEXT = SCRIPT_PATH.read_text(encoding="utf-8")
PUBLIC_SCRIPT_PATH = Path(__file__).with_name("benchmark-startup.sh")
PUBLIC_SCRIPT_TEXT = PUBLIC_SCRIPT_PATH.read_text(encoding="utf-8")
PROBE_SCRIPT_PATH = Path(__file__).with_name("probe-launch.sh")
PROBE_SCRIPT_TEXT = PROBE_SCRIPT_PATH.read_text(encoding="utf-8")

MODULE_PATH = Path(__file__).with_name("starsector_benchmark_report.py")
spec = importlib.util.spec_from_file_location("starsector_benchmark_report", MODULE_PATH)
report = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = report
spec.loader.exec_module(report)


def runs(*specs) -> list[dict]:
    """(condition, iteration, seconds or None, status) tuples into run records."""
    records = []
    for condition, iteration, seconds, status in specs:
        records.append({
            "condition": condition,
            "iteration": iteration,
            "status": status,
            "reason": None if status == "accepted" else "profile-drift",
            "gameLogStartToMainMenuMs": None if seconds is None else seconds * 1000,
            "launcherReadyMs": 9000,
            "exitCode": 0,
        })
    return records


class PublicEntryPointTest(unittest.TestCase):
    def test_default_is_one_unattended_fast_launch(self):
        for argument in (
            "--unattended",
            "--conditions fast",
            "--rounds 1",
            "--skip-warmup",
            "--cooldown-seconds 0",
        ):
            self.assertIn(argument, PUBLIC_SCRIPT_TEXT)
        self.assertIn("PREFLIGHT_BENCHMARK_CONCISE=true", PUBLIC_SCRIPT_TEXT)

    def test_diagnostics_and_campaign_are_explicit_modes(self):
        self.assertIn("--details)", PUBLIC_SCRIPT_TEXT)
        self.assertIn("--campaign)", PUBLIC_SCRIPT_TEXT)
        self.assertIn("PREFLIGHT_BENCHMARK_CONCISE=false", PUBLIC_SCRIPT_TEXT)

    def test_legacy_one_shot_shape_is_concise_without_the_public_wrapper(self):
        self.assertIn('CONCISE="${PREFLIGHT_BENCHMARK_CONCISE:-auto}"', SCRIPT_TEXT)
        self.assertIn('if [[ "$CONCISE" == auto ]]', SCRIPT_TEXT)
        self.assertIn('"$UNATTENDED" == true && "$CONDITIONS" == fast && "$ROUNDS" == 1', SCRIPT_TEXT)

    def test_concise_mode_keeps_the_exact_main_menu_clock(self):
        self.assertIn("gameLogStartToGraphicsPreloadMs", SCRIPT_TEXT)
        self.assertIn('printf "Startup: %.2fs', SCRIPT_TEXT)

    def test_zero_accepted_launches_returns_failure(self):
        self.assertIn(
            "if ! jq -s -e 'any(.[]; .status == \"accepted\")' \"$RESULTS\" >/dev/null",
            SCRIPT_TEXT,
        )

    def test_concise_mode_avoids_the_expensive_convenience_run_scans(self):
        self.assertIn('doctor --game "$GAME" --no-scan', SCRIPT_TEXT)
        self.assertIn('if [[ "$CONCISE" != true || "$CONDITIONS" != fast ]]', SCRIPT_TEXT)
        self.assertIn('if [[ -n "$LAUNCHER" ]]; then\n                command+=(--launcher "$LAUNCHER")', SCRIPT_TEXT)
        self.assertIn('if [[ "$CONCISE" == true ]]; then\n        # A one-shot result', SCRIPT_TEXT)
        self.assertIn('fingerprint="$EXPECTED_FINGERPRINT"', SCRIPT_TEXT)

    def test_one_shot_identifies_the_enabled_profile_instead_of_trusting_a_stamp(self):
        baseline = re.search(
            r'BASELINE_PROFILE="\$ROOT/profile-baseline.json"(?P<body>.*?)\nnote "profile:',
            SCRIPT_TEXT,
            re.DOTALL,
        )
        self.assertIsNotNone(baseline, "baseline profile setup not found")
        body = baseline.group("body")
        self.assertIn('EXPECTED_FINGERPRINT="$(scan_fingerprint "$BASELINE_PROFILE")"', body)
        self.assertNotIn('EXPECTED_FINGERPRINT="${stamp_value%%:*}"', body)

    def test_default_storage_follows_the_exact_profiles_learned_access_order(self):
        self.assertIn('TEXTURE_STORAGE=""', SCRIPT_TEXT)
        selection = re.search(
            r'resolve_texture_storage\(\) \{(?P<body>.*?)\n\}',
            SCRIPT_TEXT,
            re.DOTALL,
        )
        self.assertIsNotNone(selection, "automatic storage selection not found")
        body = selection.group("body")
        self.assertIn('$cache/packs/$profile.spta', body)
        with tempfile.TemporaryDirectory() as name:
            cache = Path(name)
            profile = "a" * 64
            function = f'resolve_texture_storage() {{{body}\n}}\n'
            fresh = subprocess.run(
                ["bash", "-c", function + 'resolve_texture_storage "" "$1" "$2"', "-", str(cache), profile],
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual("balanced", fresh.stdout.strip())
            (cache / "packs").mkdir()
            (cache / "packs" / f"{profile}.spta").touch()
            learned = subprocess.run(
                ["bash", "-c", function + 'resolve_texture_storage "" "$1" "$2"', "-", str(cache), profile],
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual("compact", learned.stdout.strip())
            explicit = subprocess.run(
                ["bash", "-c", function + 'resolve_texture_storage fastest "$1" "$2"', "-", str(cache), profile],
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual("fastest", explicit.stdout.strip())
        self.assertLess(
            SCRIPT_TEXT.index('EXPECTED_FINGERPRINT="$(scan_fingerprint "$BASELINE_PROFILE")"'),
            SCRIPT_TEXT.index('TEXTURE_STORAGE="$(resolve_texture_storage'),
        )
        self.assertLess(
            SCRIPT_TEXT.index('TEXTURE_STORAGE="$(resolve_texture_storage'),
            SCRIPT_TEXT.index('--arg textureStorage "$TEXTURE_STORAGE"'),
        )

    def test_one_shot_direct_launch_does_not_start_a_settings_jvm_first(self):
        settings = re.search(
            r'LAUNCH_SETTINGS="\$ROOT/launch-settings.json"(?P<body>.*?)\n\nPROTOCOL=clicked',
            SCRIPT_TEXT,
            re.DOTALL,
        )
        self.assertIsNotNone(settings, "launch settings setup not found")
        self.assertIn('if [[ "$ONE_SHOT_DIRECT" == true ]]', settings.group("body"))
        self.assertIn('command+=(--direct)', SCRIPT_TEXT)

    def test_every_direct_protocol_preflight_launch_skips_the_launcher(self):
        launch = re.search(
            r'if \[\[ "\$condition" != vanilla \]\](?P<body>.*?)\n\s*banner "\$label"',
            SCRIPT_TEXT,
            re.DOTALL,
        )
        self.assertIsNotNone(launch, "benchmarked Preflight launch assembly not found")
        self.assertIn('if [[ "$PROTOCOL" == direct ]]', launch.group("body"))
        self.assertNotIn('if [[ "$ONE_SHOT_DIRECT" == true ]]', launch.group("body"))
        self.assertIn('command+=(--direct)', launch.group("body"))

    def test_benchmarked_preflight_launches_do_not_run_the_profile_census(self):
        self.assertIn('if [[ "$condition" != vanilla ]]', SCRIPT_TEXT)
        self.assertIn('command+=(--no-scan)', SCRIPT_TEXT)

    def test_concise_mode_reuses_a_current_checkout_jar(self):
        self.assertIn('checkout_is_current=false', SCRIPT_TEXT)
        self.assertIn('-newer "$CHECKOUT_JAR"', SCRIPT_TEXT)

    def test_concise_repreparation_hides_progress_but_preserves_failure_details(self):
        prepare = re.search(
            r"prepare_caches\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL,
        )
        self.assertIsNotNone(prepare, "prepare_caches function not found")
        body = prepare.group("body")
        self.assertIn('prepare_log="$ROOT/prepare-console.txt"', body)
        self.assertIn('>"$prepare_log" 2>&1', body)
        self.assertIn('cat "$prepare_log" >&2', body)
        self.assertIn('--report "$PREPARE_REPORT" >/dev/null', body)

    def test_stopped_watchdog_is_reaped_without_shell_noise(self):
        self.assertEqual(
            SCRIPT_TEXT.count('kill "$watchdog" >/dev/null 2>&1 || true'),
            SCRIPT_TEXT.count('wait "$watchdog" >/dev/null 2>&1 || true'),
        )

    def test_detailed_probe_reuses_the_same_cheap_engine_boundaries(self):
        self.assertIn('find pom.xml preflight-*/pom.xml preflight-*/src', PROBE_SCRIPT_TEXT)
        self.assertIn('doctor --game "$GAME" --no-scan', PROBE_SCRIPT_TEXT)
        self.assertIn('--engine) ENGINE="$2"', PROBE_SCRIPT_TEXT)

    def test_detailed_probe_stops_the_exact_published_runtime(self):
        stop = re.search(
            r"^stop_owned_game\(\) \{(?P<body>.*?)\n\}",
            PROBE_SCRIPT_TEXT,
            re.DOTALL | re.M,
        )
        self.assertIsNotNone(stop, "stop_owned_game not found")
        body = stop.group("body")
        self.assertIn('runtime-process.json', body)
        self.assertIn('grep -qx "$runtime_pid" <<< "$tree"', body)
        self.assertLess(body.index('kill "$runtime_pid"'), body.index('for target in $tree'))

    def test_detailed_probe_does_not_rescan_every_java_process_while_waiting(self):
        cleanup = re.search(
            r"^cleanup\(\) \{(?P<body>.*?)\n\}",
            PROBE_SCRIPT_TEXT,
            re.DOTALL | re.M,
        )
        self.assertIsNotNone(cleanup, "cleanup not found")
        self.assertNotIn('for _ in $(seq 1 15)', cleanup.group("body"))


class ConditionTest(unittest.TestCase):
    def test_vanilla_launches_without_the_agent_attached(self):
        # JAVA_TOOL_OPTIONS is how preflight injects the agent. If an inherited value
        # survives into the baseline, the baseline is not a baseline.
        block = re.search(r"vanilla\)\n(?P<body>.*?);;", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block, "vanilla condition not found")
        self.assertIn("env -u JAVA_TOOL_OPTIONS", block.group("body"))

    def test_vanilla_launches_from_the_game_directory(self):
        # Linux's stock starsector.sh uses ./jre_linux and relative classpath entries. Running
        # the baseline from the repository makes the launcher exit before Java starts.
        block = re.search(r"launch_once\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block, "launch_once not found")
        body = block.group("body")
        self.assertIn('launch_directory="$GAME"', body)
        self.assertIn('(cd "$launch_directory" && exec "${command[@]}")', body)

    def test_agent_condition_carries_the_recorder_but_no_adapter(self):
        block = re.search(r"agent\)\n(?P<body>.*?);;", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block, "agent condition not found")
        body = block.group("body")
        self.assertIn("--no-adapter", body)
        self.assertNotIn("--texture-auto", body)

    def test_enabled_condition_uses_the_collectable_texture_path(self):
        # BenchmarkCollectedRunComparison rejects an enabled record without textureAuto.
        block = re.search(r"enabled\)\n(?P<body>.*?);;", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block, "enabled condition not found")
        body = block.group("body")
        self.assertIn("--adapter", body)
        self.assertIn("--texture-auto", body)


class OrderTest(unittest.TestCase):
    def shuffle(self, seed: int, offset: int, items: list[str]) -> list[str]:
        source = re.search(
            r"shuffle_conditions\(\) \{.*?python3 -c '(?P<body>.*?)' \"\$SEED\"",
            SCRIPT_TEXT, re.DOTALL,
        )
        self.assertIsNotNone(source, "shuffle_conditions not found")
        result = subprocess.run(
            [sys.executable, "-c", source.group("body"), str(seed), str(offset)],
            input="\n".join(items), capture_output=True, text=True, check=True,
        )
        return result.stdout.split()

    def test_order_is_reproducible_from_the_recorded_seed(self):
        items = ["vanilla", "agent", "enabled"]
        self.assertEqual(self.shuffle(7, 1, items), self.shuffle(7, 1, items))
        self.assertCountEqual(self.shuffle(7, 1, items), items)

    def test_rounds_do_not_all_share_one_order(self):
        # A fixed order across rounds is a blocked design wearing a shuffle's clothes.
        items = ["vanilla", "agent", "enabled"]
        orders = {tuple(self.shuffle(11, offset, items)) for offset in range(1, 9)}
        self.assertGreater(len(orders), 1)

    def test_the_loop_shuffles_every_round_rather_than_once(self):
        self.assertIn('done < <(shuffle_conditions "$round")', SCRIPT_TEXT)


class ResilienceTest(unittest.TestCase):
    def test_path_aliases_share_one_measurement_identity(self):
        body = re.search(r"physical_path\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(body, "physical_path not found")
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            real = directory / "real"
            alias = directory / "alias"
            real.mkdir()
            alias.symlink_to(real, target_is_directory=True)
            script = (
                f'physical_path() {{{body.group("body")}\n}}\n'
                f'physical_path "{alias / "future-cache"}"\n'
            )
            result = subprocess.run(
                ["bash", "-c", script], capture_output=True, text=True, check=True
            )
            self.assertEqual(
                str(Path(real).resolve() / "future-cache"), result.stdout.strip()
            )

    def test_resume_normalizes_paths_before_comparing_them(self):
        self.assertIn('GAME="$(physical_path "$GAME")"', SCRIPT_TEXT)
        self.assertIn('CACHE="$(physical_path "$CACHE")"', SCRIPT_TEXT)
        self.assertIn(
            'RECORDED_GAME="$(physical_path "$(jq -er \'.game\' "$SESSION_CONFIG")")"',
            SCRIPT_TEXT,
        )
        self.assertIn(
            'RECORDED_CACHE="$(physical_path "$(jq -er \'.cache\' "$SESSION_CONFIG")")"',
            SCRIPT_TEXT,
        )

    def test_one_bad_launch_does_not_abort_the_campaign(self):
        loop = re.search(
            r"for round in \$\(seq 1 \"\$ROUNDS\"\); do(?P<body>.*?)\ndone", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(loop, "round loop not found")
        self.assertIn("|| true", loop.group("body"))

    def test_a_failed_launch_still_records_an_excluded_run(self):
        for reason in ("launcher-not-ready", "main-menu-not-detected"):
            self.assertIn(f'"excluded" "{reason}"', SCRIPT_TEXT)

    def test_completed_runs_are_skipped_on_resume(self):
        self.assertIn('if completed "$condition" "$round"; then', SCRIPT_TEXT)
        self.assertIn('.status == "accepted"', SCRIPT_TEXT)

    def test_resume_restores_the_measurement_contract(self):
        for field in (
            "RECORDED_CONDITIONS",
            "RECORDED_UNATTENDED",
            "RECORDED_COOLDOWN",
            "RECORDED_GAME",
            "RECORDED_CACHE",
            "RECORDED_TEXTURE_STORAGE",
            "RECORDED_SEED",
            "RECORDED_PROTOCOL",
        ):
            self.assertIn(field, SCRIPT_TEXT)
        self.assertIn('CONDITIONS="$RECORDED_CONDITIONS"', SCRIPT_TEXT)
        self.assertIn('UNATTENDED="$RECORDED_UNATTENDED"', SCRIPT_TEXT)
        self.assertIn('SEED="$RECORDED_SEED"', SCRIPT_TEXT)
        self.assertIn('TEXTURE_STORAGE="$RECORDED_TEXTURE_STORAGE"', SCRIPT_TEXT)

    def test_storage_policy_is_part_of_preparation_and_session_identity(self):
        self.assertIn('PREPARE_TEXTURE_STORAGE=balanced', SCRIPT_TEXT)
        self.assertIn('TEXTURE_SCOPE=learned', SCRIPT_TEXT)
        self.assertIn(
            '--texture-storage "$PREPARE_TEXTURE_STORAGE" --texture-scope "$TEXTURE_SCOPE"',
            SCRIPT_TEXT,
        )
        self.assertIn('PREPARE_STAMP_VALUE="$EXPECTED_FINGERPRINT:$TEXTURE_STORAGE"', SCRIPT_TEXT)
        self.assertIn('--arg textureStorage "$TEXTURE_STORAGE"', SCRIPT_TEXT)
        self.assertIn('"textureStorage": "$TEXTURE_STORAGE"', SCRIPT_TEXT)

    def test_legacy_preparation_stamp_is_balanced_only(self):
        stamp_guard = re.search(
            r'elif \[\[ -f "\$PREPARE_STAMP"(?P<body>.*?)\]\]; then',
            SCRIPT_TEXT,
            re.DOTALL,
        )
        self.assertIsNotNone(stamp_guard, "preparation stamp guard not found")
        self.assertIn('"$TEXTURE_STORAGE" == balanced', stamp_guard.group("body"))

    def test_resume_never_overwrites_immutable_launch_settings(self):
        settings = re.search(
            r'LAUNCH_SETTINGS="\$ROOT/launch-settings.json"(?P<body>.*?)\n\nPROTOCOL=clicked',
            SCRIPT_TEXT, re.DOTALL,
        )
        self.assertIsNotNone(settings, "launch-settings contract not found")
        body = settings.group("body")
        self.assertIn('if [[ -n "$SESSION" ]]', body)
        self.assertIn('[[ -f "$LAUNCH_SETTINGS" ]]', body)
        self.assertIn('else\n    java -jar "$JAR" launch-settings > "$LAUNCH_SETTINGS"', body)

    def test_legacy_resume_fails_instead_of_guessing(self):
        self.assertIn("This session predates the resumable session contract", SCRIPT_TEXT)
        self.assertIn("Refusing to guess its protocol, conditions, display settings", SCRIPT_TEXT)

    def test_resume_rejects_code_and_environment_drift(self):
        self.assertIn('.repositoryHead == $head and .preflightJarSha256 == $jar', SCRIPT_TEXT)
        self.assertIn('and .hardware == $hardware and .os == $os and .java == $java', SCRIPT_TEXT)
        self.assertIn("Refusing to combine unlike launches", SCRIPT_TEXT)


class CandidateEngineTest(unittest.TestCase):
    """A release-candidate campaign has to execute the candidate's own engine. The failure this
    guards against is silent: a run that fell back to preflight-cli/target/preflight.jar produces
    evidence identical in shape to one that did not, so the fallback cannot be allowed to exist."""

    def resolve(self, argument: str) -> tuple[int, str]:
        body = re.search(r"resolve_engine\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(body, "resolve_engine not found")
        script = (
            "set -uo pipefail\n"
            f'resolve_engine() {{{body.group("body")}\n}}\n'
            f'resolve_engine "{argument}"\n'
        )
        result = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
        return result.returncode, result.stdout.strip()

    def layouts(self, directory: Path) -> None:
        for relative in (
            # macOS bundle, and the same bundle seen from a mounted disk image.
            "app/Preflight.app/Contents/Resources/engine/preflight.jar",
            "dmg/Preflight.app/Contents/Resources/engine/preflight.jar",
            # Windows NSIS install root and a Linux package payload.
            "nsis/engine/preflight.jar",
            "deb/usr/lib/Preflight/engine/preflight.jar",
            "appimage/lib/preflight/engine/preflight.jar",
            "loose/preflight.jar",
        ):
            target = directory / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"engine")
        (directory / "empty").mkdir()
        (directory / "wrong-suffix").mkdir()
        (directory / "wrong-suffix" / "preflight.zip").write_bytes(b"engine")

    def test_every_reviewed_package_layout_resolves_to_its_engine(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            self.layouts(directory)
            for requested, expected in (
                ("app/Preflight.app", "app/Preflight.app/Contents/Resources/engine/preflight.jar"),
                ("dmg", "dmg/Preflight.app/Contents/Resources/engine/preflight.jar"),
                ("nsis", "nsis/engine/preflight.jar"),
                ("deb", "deb/usr/lib/Preflight/engine/preflight.jar"),
                ("appimage", "appimage/lib/preflight/engine/preflight.jar"),
                ("loose/preflight.jar", "loose/preflight.jar"),
            ):
                code, resolved = self.resolve(str(directory / requested))
                self.assertEqual(0, code, requested)
                self.assertEqual(str(directory / expected), resolved)

    def test_anything_it_cannot_identify_is_refused_rather_than_guessed(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            self.layouts(directory)
            for requested in ("empty", "wrong-suffix/preflight.zip", "absent"):
                code, resolved = self.resolve(str(directory / requested))
                self.assertEqual(1, code, requested)
                self.assertEqual("", resolved)

    def test_candidate_mode_builds_nothing_and_has_no_checkout_fallback(self):
        branch = re.search(
            r'if \[\[ "\$ENGINE_SOURCE" == candidate \]\]; then(?P<body>.*?)\nelse\n(?P<checkout>.*?)\nfi\n'
            r'JAR_SHA=',
            SCRIPT_TEXT, re.DOTALL,
        )
        self.assertIsNotNone(branch, "engine resolution branch not found")
        self.assertNotIn("mvn", branch.group("body"))
        self.assertNotIn("CHECKOUT_JAR", branch.group("body"))
        self.assertIn("Refusing to fall back to the checkout build", branch.group("body"))
        # The checkout branch is the only place the checkout JAR is ever selected.
        self.assertIn('"$MAVEN" -q -DskipTests package', branch.group("checkout"))
        self.assertIn('JAR="$CHECKOUT_JAR"', branch.group("checkout"))

    def test_a_named_digest_is_enforced_against_the_resolved_engine(self):
        self.assertIn("This engine is not the one --engine-sha256 named.", SCRIPT_TEXT)
        self.assertIn("--engine-sha256 describes a candidate engine, so it requires --engine.",
                      SCRIPT_TEXT)
        self.assertIn("--engine-sha256 must be a 64-character hex SHA-256.", SCRIPT_TEXT)

    def test_a_packaged_engine_is_checked_against_the_manifest_beside_it(self):
        self.assertIn("does not match the bundle manifest packaged beside it", SCRIPT_TEXT)
        self.assertIn('MANIFEST_JAR_BYTES="$(jq -er \'.jarBytes // empty\'', SCRIPT_TEXT)
        self.assertIn('MANIFEST_JAR_SHA="$(jq -er \'.jarSha256 // empty\'', SCRIPT_TEXT)
        self.assertIn("does not match the JAR digest in its bundle manifest", SCRIPT_TEXT)

    def test_the_session_records_and_rebinds_its_engine(self):
        self.assertIn('engineSource: $engineSource, engine: $engine', SCRIPT_TEXT)
        self.assertIn('"engineSource": "$ENGINE_SOURCE"', SCRIPT_TEXT)
        self.assertIn('and (.engineSource // "checkout") == $engineSource', SCRIPT_TEXT)
        self.assertIn("This session measured a release candidate engine, not the checkout.",
                      SCRIPT_TEXT)

    def test_sessions_written_before_engine_selection_still_resume(self):
        # Version 1 sessions could only ever have been checkout builds; the default is a fact
        # about the format, not a guess about the session.
        self.assertIn("jq -er '.engineSource // \"checkout\"'", SCRIPT_TEXT)
        self.assertIn("{version: 3,", SCRIPT_TEXT)

    def test_a_checkout_campaign_says_it_is_development_evidence(self):
        self.assertIn(
            'note "engine:          the checkout build -- development evidence, '
            'not a release candidate"',
            SCRIPT_TEXT,
        )


class ReportTest(unittest.TestCase):
    def test_medians_and_delta_are_measured_against_vanilla(self):
        summary = report.summarize(runs(
            ("vanilla", 1, 100.0, "accepted"),
            ("vanilla", 2, 102.0, "accepted"),
            ("vanilla", 3, 104.0, "accepted"),
            ("enabled", 1, 90.0, "accepted"),
            ("enabled", 2, 92.0, "accepted"),
            ("enabled", 3, 94.0, "accepted"),
        ))
        self.assertEqual(102.0, summary["conditions"]["vanilla"]["medianSeconds"])
        self.assertEqual(92.0, summary["conditions"]["enabled"]["medianSeconds"])
        comparison = summary["comparisons"]["enabled vs vanilla"]
        self.assertEqual(-10.0, comparison["deltaSeconds"])
        self.assertAlmostEqual(9.8, comparison["improvementPercent"], places=1)

    def test_excluded_runs_stay_out_of_the_statistics_but_are_reported(self):
        summary = report.summarize(runs(
            ("vanilla", 1, 100.0, "accepted"),
            ("vanilla", 2, None, "excluded"),
            ("vanilla", 3, 200.0, "accepted"),
        ))
        vanilla = summary["conditions"]["vanilla"]
        self.assertEqual(2, vanilla["successfulRuns"])
        self.assertEqual(1, vanilla["excludedRuns"])
        self.assertEqual(["profile-drift"], vanilla["exclusionReasons"])
        self.assertEqual(150.0, vanilla["medianSeconds"])

    def test_three_rounds_can_never_be_reportable(self):
        # Exactly the defect that made the July pair worthless, generalised: a sample this
        # small cannot support a claim no matter how large the measured gap.
        summary = report.summarize(runs(
            *[("vanilla", i, 100.0, "accepted") for i in range(1, 4)],
            *[("enabled", i, 10.0, "accepted") for i in range(1, 4)],
        ))
        self.assertFalse(summary["benchmarkAccepted"])
        self.assertGreaterEqual(summary["comparisons"]["enabled vs vanilla"]["permutationP"], 0.1)

    def test_five_clean_rounds_per_condition_are_reportable(self):
        summary = report.summarize(runs(
            *[("vanilla", i, 100.0 + i, "accepted") for i in range(1, 6)],
            *[("enabled", i, 80.0 + i, "accepted") for i in range(1, 6)],
        ))
        self.assertTrue(summary["benchmarkAccepted"])
        self.assertTrue(
            summary["comparisons"]["enabled vs vanilla"]["meetsCampaignMinimum"]
        )

    def test_a_condition_short_of_the_threshold_blocks_acceptance(self):
        summary = report.summarize(runs(
            *[("vanilla", i, 100.0, "accepted") for i in range(1, 6)],
            *[("enabled", i, 80.0, "accepted") for i in range(1, 6)],
            *[("agent", i, 99.0, "accepted") for i in range(1, 4)],
        ))
        self.assertFalse(summary["benchmarkAccepted"])

    def test_an_unknown_condition_is_reported_rather_than_silently_dropped(self):
        # This one really happened. A `profile` condition was added to the runner and not to
        # ORDER, so conditions_present filtered it out: three launches ran, recorded, and were
        # accepted, and the campaign then printed an empty table and "no pair of conditions",
        # which reads exactly like "your runs failed" when every run had succeeded. Losing a
        # condition has to be loud, because the operator has already spent the launches.
        summary = report.summarize(runs(
            *[("someday", i, 90.0 + i, "accepted") for i in range(1, 4)],
        ))
        self.assertIn("someday", summary["conditions"])
        self.assertEqual(3, summary["conditions"]["someday"]["successfulRuns"])
        self.assertIn("someday", report.render(summary, verbose=True))

    def test_a_diagnostic_condition_does_not_hold_back_a_finished_campaign(self):
        # The sampling profile is slower than an ordinary run by construction, so it is never
        # compared and never gates acceptance -- otherwise adding three diagnostic launches to
        # a complete campaign would retroactively make it unreportable.
        summary = report.summarize(runs(
            *[("vanilla", i, 100.0 + i, "accepted") for i in range(1, 6)],
            *[("fast", i, 80.0 + i, "accepted") for i in range(1, 6)],
            *[("profile", i, 120.0 + i, "accepted") for i in range(1, 4)],
        ))
        self.assertTrue(summary["benchmarkAccepted"])
        self.assertIn("profile", summary["conditions"])
        self.assertFalse(
            any("profile" in name for name in summary["comparisons"]),
            summary["comparisons"],
        )

    def test_two_launch_protocols_in_one_file_produce_no_comparison(self):
        # `direct` never builds a launcher; `clicked` does. The launcher's OpenGL context, font
        # loading and window creation are in one and not the other, so a median across them is
        # two quantities read as one. A caveated delta would still get quoted, so there is none.
        mixed = runs(
            *[("vanilla", i, 100.0, "accepted") for i in range(1, 6)],
            *[("fast", i, 80.0, "accepted") for i in range(1, 6)],
        )
        for index, run in enumerate(mixed):
            run["protocol"] = "direct" if index % 2 else "clicked"

        summary = report.summarize(mixed)
        self.assertTrue(summary["protocolsMixed"])
        self.assertEqual(["clicked", "direct"], summary["launchProtocols"])
        self.assertEqual({}, summary["comparisons"])
        self.assertFalse(summary["benchmarkAccepted"])
        rendered = report.render(summary, verbose=True)
        self.assertIn("NOT comparable", rendered)
        # The per-condition table still prints: the runs happened and the operator paid for
        # them, and knowing which protocol each fell under is how they get salvaged.
        self.assertIn("vanilla", rendered)

    def test_a_single_protocol_compares_normally(self):
        single = runs(
            *[("vanilla", i, 100.0, "accepted") for i in range(1, 6)],
            *[("fast", i, 80.0, "accepted") for i in range(1, 6)],
        )
        for run in single:
            run["protocol"] = "direct"

        summary = report.summarize(single)
        self.assertFalse(summary["protocolsMixed"])
        self.assertEqual(["direct"], summary["launchProtocols"])
        self.assertTrue(summary["benchmarkAccepted"])

    def test_sessions_recorded_before_protocols_existed_read_as_clicked(self):
        # Every run recorded before 2026-08-01 was a clicked one. Treating a missing field as
        # its own protocol would make every older session look internally inconsistent.
        summary = report.summarize(runs(
            *[("vanilla", i, 100.0, "accepted") for i in range(1, 6)],
            *[("fast", i, 80.0, "accepted") for i in range(1, 6)],
        ))
        self.assertEqual(["clicked"], summary["launchProtocols"])
        self.assertFalse(summary["protocolsMixed"])

    def test_a_campaign_that_drifted_more_than_it_measured_is_not_a_result(self):
        # The 2026-08-01 campaign, reproduced. Fifteen launches, every one accepted, medians
        # printed -- and launch order explained 88% of the variance because the machine warmed
        # up +19.6s across a run whose condition effects were about 2s. Nothing in the harness
        # was looking, so a worthless campaign looked like a clean one.
        drifting = []
        for index, (condition, iteration) in enumerate(
            [(c, i) for i in range(1, 6) for c in ("vanilla", "fast", "prepared")]
        ):
            drifting.append((condition, iteration, 94.0 + 1.4 * index, "accepted"))
        summary = report.summarize(runs(*drifting))

        self.assertTrue(summary["driftDominatesConditions"])
        trend = summary["launchOrderTrend"]
        # Slightly under the 1.4 built into the fixture, and correctly so: centring on condition
        # means absorbs a little of a trend this perfectly confounded with position. The
        # estimate is deliberately conservative -- it under-reports drift rather than inventing
        # it out of a difference between conditions.
        self.assertAlmostEqual(1.4, trend["secondsPerLaunch"], delta=0.1)
        self.assertAlmostEqual(19.6, trend["secondsAcrossCampaign"], delta=1.5)
        self.assertFalse(summary["benchmarkAccepted"],
                         "a campaign the machine dominated is not a reportable result")
        rendered = report.render(summary, verbose=True)
        self.assertIn("NOT comparable", rendered)
        self.assertIn("cooldown-seconds", rendered)

    def test_a_steady_campaign_still_reports(self):
        # The guard must not fire on ordinary scatter, or it blocks every campaign forever.
        steady = []
        wobble = [0.4, -0.3, 0.2, -0.4, 0.1]
        for iteration in range(1, 6):
            steady.append(("vanilla", iteration, 100.0 + wobble[iteration - 1], "accepted"))
            steady.append(("fast", iteration, 80.0 + wobble[iteration - 1], "accepted"))
        summary = report.summarize(runs(*steady))

        self.assertFalse(summary["driftDominatesConditions"])
        self.assertTrue(summary["benchmarkAccepted"])

    def test_the_trend_is_reported_even_when_it_does_not_block(self):
        # A drift smaller than the effect still belongs in the record: it is the difference
        # between a 20s win and a 20s win with 5s of machine in it.
        mild = []
        for index, (condition, iteration) in enumerate(
            [(c, i) for i in range(1, 6) for c in ("vanilla", "fast")]
        ):
            base = 100.0 if condition == "vanilla" else 70.0
            mild.append((condition, iteration, base + 0.5 * index, "accepted"))
        summary = report.summarize(runs(*mild))

        self.assertTrue(summary["launchOrderTrend"]["measurable"])
        self.assertGreater(summary["launchOrderTrend"]["secondsAcrossCampaign"], 0)
        self.assertFalse(summary["driftDominatesConditions"])

    def test_too_few_runs_to_see_a_trend_is_not_a_claim_that_there_is_none(self):
        summary = report.summarize(runs(("vanilla", 1, 100.0, "accepted")))
        self.assertFalse(summary["launchOrderTrend"]["measurable"])
        self.assertFalse(summary["driftDominatesConditions"])

    def test_no_baseline_means_no_comparison_rather_than_a_crash(self):
        summary = report.summarize(runs(("enabled", 1, 80.0, "accepted")))
        self.assertEqual({}, summary["comparisons"])
        self.assertFalse(summary["benchmarkAccepted"])
        self.assertIn("No comparison yet", report.render(summary, verbose=True))

    def test_summary_round_trips_through_the_written_file(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory) / "results.jsonl"
            results.write_text(
                "\n".join(
                    json.dumps(record)
                    for record in runs(
                        ("vanilla", 1, 100.0, "accepted"),
                        ("enabled", 1, 90.0, "accepted"),
                    )
                ) + "\n",
                encoding="utf-8",
            )
            output = Path(directory) / "summary.json"
            with contextlib.redirect_stdout(io.StringIO()):
                report.main(["summary", "--results", str(results), "--output", str(output)])
            written = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(2, len(written["runs"]))
            self.assertEqual(100.0, written["conditions"]["vanilla"]["medianSeconds"])


class DetectorContractTest(unittest.TestCase):
    """The script drives the detector across a process boundary, so a renamed or newly
    required flag would only show up at launch time."""

    def test_every_detector_invocation_parses(self):
        detector_path = Path(__file__).with_name("starsector_log_ready_detector.py")
        detector_spec = importlib.util.spec_from_file_location("detector_under_test", detector_path)
        detector = importlib.util.module_from_spec(detector_spec)
        assert detector_spec.loader is not None
        # dataclasses resolves annotations through sys.modules during class creation.
        sys.modules[detector_spec.name] = detector
        detector_spec.loader.exec_module(detector)

        invocations = []
        lines = SCRIPT_TEXT.splitlines()
        for index, line in enumerate(lines):
            if 'python3 "$DETECTOR" ' not in line:
                continue
            call = line.split('python3 "$DETECTOR" ', 1)[1]
            cursor = index
            while call.rstrip().endswith("\\"):  # shell line continuation
                cursor += 1
                call = call.rstrip().removesuffix("\\") + " " + lines[cursor]
            invocations.append(call)

        self.assertGreaterEqual(len(invocations), 3, "expected snapshot and both watches")
        seen = set()
        for call in invocations:
            tokens = call.split()
            subcommand, flags = tokens[0], [token for token in tokens if token.startswith("--")]
            seen.add(subcommand)
            argv = [subcommand]
            for flag in flags:
                argv += [flag, "1"]  # parses as a path, an int, and a float alike
            # Raises SystemExit on an unknown flag or a missing required one.
            detector.parse_args(argv)
        self.assertEqual({"snapshot", "watch-launcher", "watch-main-menu"}, seen)


class AdapterEvidenceTest(unittest.TestCase):
    """A fail-open adapter run looks exactly like a baseline run from the outside, so
    nothing but this check stops it from being counted as a prepared measurement."""

    def served(self, telemetry) -> bool:
        body = re.search(
            r"served_prepared_textures\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(body, "served_prepared_textures not found")
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            if telemetry is not None:
                (run_dir / "adapter.json").write_text(
                    json.dumps({"textureCompatibility": telemetry}), encoding="utf-8"
                )
            script = (
                "set -uo pipefail\n"
                "bad() { :; }\nnote() { :; }\n"
                f'served_prepared_textures() {{{body.group("body")}\n}}\n'
                f'served_prepared_textures "{run_dir}"\n'
            )
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
            return result.returncode == 0

    def healthy(self, **overrides) -> dict:
        telemetry = {
            "ready": True, "hits": 4956, "misses": 0, "fallbacks": 0,
            "internalErrors": 0, "bytesServed": 12345, "disableReasons": [],
        }
        telemetry.update(overrides)
        return telemetry

    def test_a_serving_run_is_accepted(self):
        self.assertTrue(self.served(self.healthy()))

    def test_a_run_that_served_nothing_is_rejected(self):
        self.assertFalse(self.served(self.healthy(hits=0)))

    def test_a_disabled_adapter_is_rejected(self):
        self.assertFalse(self.served(self.healthy(disableReasons=["circuit-breaker"])))

    def test_internal_errors_reject_the_run(self):
        self.assertFalse(self.served(self.healthy(internalErrors=1)))

    def test_an_unready_adapter_is_rejected(self):
        self.assertFalse(self.served(self.healthy(ready=False)))

    def test_a_missing_report_is_rejected_rather_than_assumed_healthy(self):
        self.assertFalse(self.served(None))

    def bypassed(self, telemetry) -> bool:
        body = re.search(
            r"bypassed_pixel_conversions\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(body, "bypassed_pixel_conversions not found")
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            if telemetry is not None:
                (run_dir / "adapter.json").write_text(
                    json.dumps({"textureCompatibility": {"preparedPixels": telemetry}}),
                    encoding="utf-8",
                )
            script = (
                "set -uo pipefail\n"
                "bad() { :; }\nnote() { :; }\n"
                f'bypassed_pixel_conversions() {{{body.group("body")}\n}}\n'
                f'bypassed_pixel_conversions "{run_dir}"\n'
            )
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
            return result.returncode == 0

    def test_a_prepared_run_that_bypassed_conversions_is_accepted(self):
        self.assertTrue(self.bypassed({
            "hits": 6813, "fallbacks": 12, "internalErrors": 0,
            "conversionCallsBypassed": 6813, "uploadBytesSupplied": 646243375,
        }))

    def test_a_prepared_run_that_bypassed_nothing_is_rejected(self):
        # The dangerous case: the cache served every texture, so served_prepared_textures
        # passes in full, but every one of them fell back to the ordinary BufferedImage
        # conversion. That is a compatibility run labelled prepared, and timing it would
        # report that prepared-pixels changes nothing.
        self.assertFalse(self.bypassed({
            "hits": 0, "fallbacks": 6813, "internalErrors": 0,
            "conversionCallsBypassed": 0, "uploadBytesSupplied": 0,
        }))
        # Mostly falling back is the same failure, just quieter.
        self.assertFalse(self.bypassed({
            "hits": 100, "fallbacks": 6713, "internalErrors": 0,
            "conversionCallsBypassed": 100, "uploadBytesSupplied": 1000,
        }))
        self.assertFalse(self.bypassed(None))

    def test_the_prepared_condition_must_prove_both_cache_and_bridge(self):
        # Serving from the cache and bypassing the conversion are separate claims, and the
        # prepared condition rests on both. Checking only the first is how a run that never
        # exercised the bridge would be counted as evidence that the bridge does nothing.
        guard = re.search(
            r'elif \[\[ (?P<test>[^\]]*prepared[^\]]*) \]\] \\?\s*\n\s*&& \{(?P<body>[^}]*)\}',
            SCRIPT_TEXT,
        )
        self.assertIsNotNone(guard, "prepared guard does not check the pixel bridge")
        self.assertIn("bypassed_pixel_conversions", guard.group("body"))
        self.assertIn("served_prepared_textures", guard.group("body"))
        # Every condition that claims the bridge has to prove it, not just the first one
        # added. A variant guarded only by the cache check would report the padded and
        # unpadded paths as identical when one of them never ran.
        conditions = re.findall(r'^\s{8}(prepared[a-z-]*)\)', SCRIPT_TEXT, re.MULTILINE)
        self.assertTrue(conditions, "no prepared conditions found in the launch dispatch")
        for condition in conditions:
            self.assertIn(condition, guard.group("test"), f"{condition} is not guarded")
        self.assertIn("fast", guard.group("test"), "the current --fast preset is not guarded")
        self.assertIn("full", guard.group("test"), "the historical full stack is not guarded")

    def test_compatibility_cache_serving_conditions_are_checked_and_no_others(self):
        # vanilla and agent legitimately have no adapter evidence. The compatibility-pixel
        # conditions prove cache service here; prepared-pixel conditions use the stricter
        # cache-plus-bridge guard above.
        guard = re.search(
            r'elif \[\[ (?P<test>[^\]]*) \]\] \\?\s*\n?\s*&& ! served_prepared_textures',
            SCRIPT_TEXT,
        )
        self.assertIsNotNone(guard, "adapter guard not found")
        condition = guard.group("test")
        self.assertIn("enabled", condition)
        self.assertIn("compatibility", condition)
        self.assertIn("profile", condition)
        self.assertNotIn("fast", condition)
        self.assertNotIn("vanilla", condition)
        self.assertNotIn("agent", condition)

    def test_fast_condition_invokes_the_cli_fast_preset(self):
        fast = re.search(
            r"^        fast\)(?P<body>.*?) ;;",
            SCRIPT_TEXT,
            re.DOTALL | re.MULTILINE,
        )
        self.assertIsNotNone(fast, "fast launch condition not found")
        self.assertRegex(fast.group("body"), r"(?:^|\s)--fast(?:\s|$)")


class UnattendedTest(unittest.TestCase):
    """Running without an operator removes the two things a human did. The ways it goes wrong
    quietly are measuring two protocols as one, and reading our own SIGTERM as a failed run."""

    def test_profile_condition_finishes_live_recording_before_shutdown(self):
        profile = re.search(
            r"^        profile\)(?P<body>.*?) ;;",
            SCRIPT_TEXT,
            re.DOTALL | re.MULTILINE,
        )
        self.assertIsNotNone(profile, "profile launch condition not found")
        self.assertIn("--profile", profile.group("body"))
        self.assertNotIn("--single-chunk-recording", profile.group("body"))
        self.assertIn("finish_live_recording", SCRIPT_TEXT)

    def test_every_condition_is_driven_including_vanilla(self):
        # The properties travel through the game's own EXTRAARGS hook rather than through the
        # agent, so vanilla is unattended too. That is the whole difference from the button
        # driver this replaced, which needed something inside the process and so could never
        # touch the baseline.
        block = re.search(r"local auto=false\n(?P<body>.*?)\n    fi", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block, "unattended gate not found")
        self.assertIn('"$PROTOCOL" == direct', block.group("body"))
        self.assertNotIn("vanilla", block.group("body"))

    def test_an_install_that_cannot_be_driven_is_refused_before_any_launch(self):
        # Forty minutes into a campaign is the wrong time to find out that nothing is going to
        # press anything. The check runs before the first launch and exits.
        gate = re.search(
            r"directLaunchAvailable.*?exit 2", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(gate, "direct-launch availability gate not found")
        self.assertIn("--unattended is not available", gate.group(0))
        self.assertLess(
            SCRIPT_TEXT.index("directLaunchAvailable"),
            SCRIPT_TEXT.index("launch_once() {"),
            "availability is decided before any launch can happen",
        )

    def test_the_settings_come_from_the_launcher_rather_than_from_the_script(self):
        # A hard-coded resolution would launch fine and measure a configuration nobody chose.
        self.assertIn("launch-settings", SCRIPT_TEXT)
        self.assertIn(".settings.javaOptions", SCRIPT_TEXT)
        self.assertNotRegex(SCRIPT_TEXT, r"startRes=\d+x\d+")

    def test_a_cooldown_precedes_the_launch_rather_than_following_it(self):
        # It has to be inside launch_once and before the snapshot, so every launch begins from
        # the same thermal state. After the launch it would cool the machine for whatever ran
        # next, which is the same bug with an extra step.
        block = re.search(r"launch_once\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(block)
        body = block.group("body")
        self.assertIn("COOLDOWN_SECONDS > 0", body)
        self.assertLess(
            body.index("COOLDOWN_SECONDS > 0"),
            body.index('"$DETECTOR" snapshot'),
            "the cooldown must finish before the run's log snapshot is taken",
        )

    def test_the_old_button_driver_is_refused_rather_than_aliased(self):
        # --auto-play measured a different interval. Quietly accepting the name would put two
        # protocols in one results file.
        self.assertIn("--auto-play|--auto-play-timeout-ms)", SCRIPT_TEXT)
        self.assertNotIn("command+=(--auto-play)", SCRIPT_TEXT)

    def test_the_protocol_is_recorded_with_every_run(self):
        # The results file is the only place the two can be told apart afterwards.
        body = re.search(r"record_run\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(body)
        self.assertIn("protocol: $protocol", body.group("body"))

    def test_the_direct_protocol_does_not_wait_for_a_launcher_that_never_appears(self):
        # There is no launcher under launchDirect, so its readiness marker is never logged.
        # Watching for it would spend the full launcher timeout before every single run.
        self.assertRegex(
            SCRIPT_TEXT,
            r'if \[\[ "\$auto" != true \]\]; then\n(?:.*?\n)*?\s*if ! python3 "\$DETECTOR" watch-launcher',
        )

    def test_a_deliberate_stop_is_not_counted_as_a_failed_run(self):
        # The harness signals the game once the menu marker lands, and `wait` then reports a
        # signalled exit. Without this the acceptance check excludes every automated run as
        # nonzero-exit, which looks like the whole campaign failed.
        self.assertRegex(
            SCRIPT_TEXT, r'if \[\[ "\$deliberate_stop" == true \]\]; then\n\s*exit_code=0'
        )
        # A crash must still be caught, so the fatal flag has to be tested before the exit code.
        fatal = SCRIPT_TEXT.index('if [[ -s "$fatal_flag" ]]; then\n        report_fatal_jvm_error')
        nonzero = SCRIPT_TEXT.index('reason="nonzero-exit-$exit_code"')
        self.assertLess(fatal, nonzero, "exit code is judged before the crash flag")

    def test_an_unattended_stop_leaves_the_wrapper_alive_to_finalize_evidence(self):
        direct_stop = re.search(
            r'if \[\[ "\$auto" == true \]\]; then(?P<body>.*?)\n    else',
            SCRIPT_TEXT,
            re.DOTALL,
        )
        self.assertIsNotNone(direct_stop, "unattended stop block not found")
        self.assertIn(
            'terminate_descendants "$pid" "$run_dir/runtime-process.json"',
            direct_stop.group("body"),
        )
        self.assertNotIn('terminate "$pid"', direct_stop.group("body"))


class SettlingLaunchTest(unittest.TestCase):
    """The settling launch is discarded, so every cost it carries is pure downside."""

    def test_the_settling_launch_does_not_attach_the_recorder(self):
        # It runs `fast`, not `enabled`. Its output is thrown away, so a recording buys
        # nothing -- and the recorder is the most likely contributor to the HotSpot
        # safepoint crash this configuration keeps hitting. Attaching the heaviest
        # profiler we own to the one launch nobody reads is all risk and no information.
        block = re.search(
            r"Discarded settling launch.*?touch \"\$ROOT/warmup-done\"",
            SCRIPT_TEXT, re.DOTALL,
        )
        self.assertIsNotNone(block, "settling launch block not found")
        body = block.group(0)
        self.assertIn("launch_once fast 0", body)
        self.assertNotIn("launch_once enabled 0", body)

    def test_a_failed_settling_launch_is_not_recorded_as_settled(self):
        # Marking it done regardless was the quiet failure: a settling launch that crashed
        # before the main menu may not have finished GraphicsLib's normal-map cache, and
        # the leftover write then lands on the first measured run -- exactly the
        # contamination the settling launch exists to prevent.
        block = re.search(
            r"Discarded settling launch.*?\nfi\n", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(block, "settling launch block not found")
        body = block.group(0)
        self.assertNotIn("launch_once fast 0 \"SETTLING LAUNCH  (discarded)\" || true", body)
        self.assertRegex(body, r'if \[\[ "\$settled" == true \]\]')
        self.assertIn("retry", body.lower())


class FatalJvmErrorTest(unittest.TestCase):
    """A fatal JVM error does not end the process: Starsector passes
    -XX:+ShowMessageBoxOnError, so HotSpot prints its report and waits on stdin forever.
    That is indistinguishable from a slow load by every signal the harness watches."""

    def patterns(self) -> str:
        match = re.search(r"FATAL_JVM_PATTERNS='(?P<value>[^']*)'", SCRIPT_TEXT)
        self.assertIsNotNone(match, "FATAL_JVM_PATTERNS not found")
        return match.group("value")

    def matches(self, text: str) -> bool:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "wrapper-output.txt"
            output.write_text(text, encoding="utf-8")
            result = subprocess.run(
                ["grep", "-qaE", self.patterns(), str(output)], capture_output=True
            )
            return result.returncode == 0

    def game_patterns(self) -> str:
        match = re.search(r"FATAL_GAME_PATTERN='(?P<value>[^']*)'", SCRIPT_TEXT)
        self.assertIsNotNone(match, "FATAL_GAME_PATTERN not found")
        return match.group("value")

    def game_matches(self, text: str) -> bool:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "wrapper-output.txt"
            output.write_text(text, encoding="utf-8")
            result = subprocess.run(
                ["grep", "-qaE", self.game_patterns(), str(output)], capture_output=True
            )
            return result.returncode == 0

    def test_the_modal_dialog_hang_is_detected(self):
        # The 2026-07-31 failure. StarfarerLauncher wraps both the launcher and the game start
        # in one try and answers a throwable with org.lwjgl.Sys.alert -- a modal native dialog
        # nothing dismisses. The process stays alive at idle, which every signal the harness
        # watches reads as a slow load, so it used to cost the full 600-second timeout.
        self.assertTrue(self.game_matches(
            "9421 [main] ERROR  com.fs.starfarer.StarfarerLauncher  - "
            "Number of remaining buffer elements is 668043, must be at least 1572864\n"
        ))

    def test_ordinary_launcher_logging_is_not_mistaken_for_the_dialog(self):
        # The same category logs INFO on every single launch, several times.
        self.assertFalse(self.game_matches(
            "9079 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - "
            "Running with the following mods (in order of priority):\n"
            "9080 [Thread-3] INFO  com.fs.starfarer.StarfarerLauncher  - Mod list finished\n"
        ))
        # A mod erroring is not the launcher's top-level handler firing.
        self.assertFalse(self.game_matches(
            "9421 [Thread-3] ERROR com.fs.starfarer.loading.SpecStore  - bad ship spec\n"
        ))

    def test_the_two_hangs_are_reported_as_different_things(self):
        # One is HotSpot, one is the game throwing. Telling someone to drop --profile because
        # a mod handed the loader a short buffer would send them at the wrong problem.
        body = re.search(
            r"report_fatal_jvm_error\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(body)
        text = body.group("body")
        self.assertIn("FATAL_GAME_PATTERN", text)
        self.assertIn("Sys.alert", text)
        # The game-side branch has to come first; the HotSpot advice is the fallthrough.
        self.assertLess(text.index("Sys.alert"), text.index("ShowMessageBoxOnError"))

    def test_both_hang_shapes_are_watched_during_a_launch(self):
        body = re.search(
            r"watch_for_fatal_jvm_error\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(body)
        self.assertIn("$FATAL_JVM_PATTERNS|$FATAL_GAME_PATTERN", body.group("body"))

    def test_the_real_crash_is_detected(self):
        self.assertTrue(self.matches(
            "Internal Error at sharedRuntime.cpp:561, pid=44307, tid=146947\n"
            "guarantee(cb != NULL && cb->is_compiled()) failed: safepoint polling\n"
        ))

    def test_the_message_box_prompt_is_detected(self):
        # This is the line that turns a crash into an indefinite hang.
        self.assertTrue(self.matches("Do you want to debug the problem?\n"))

    def test_the_standard_hotspot_banner_is_detected(self):
        self.assertTrue(self.matches(
            "# A fatal error has been detected by the Java Runtime Environment:\n"))

    def test_ordinary_game_logging_is_not_mistaken_for_a_crash(self):
        # The game's own log is interleaved into this file. A mod named "Guarantee Rare
        # Items" matches a naive search for HotSpot's guarantee() failures, and the word
        # "error" appears in normal mod output constantly.
        self.assertFalse(self.matches(
            "131 [main] INFO ModManager - Found mod: guarantee-rare-items\n"
            "25139 [Thread-3] INFO SpecStore - Loaded spec [alpha_core_|_guaranteed_alpha]\n"
            "84701 [Thread-3] WARN TextureData - Defined animation frames for incompatible\n"
            "9000 [Thread-3] ERROR org.dark.shaders.util.ShaderLib - Error creating shader:\n"
            "INFO VersionChecker - Error loading version info for some mod\n"
        ))

    def test_a_crash_is_recorded_as_its_own_reason_not_a_timeout(self):
        self.assertIn('"excluded" "jvm-crash"', SCRIPT_TEXT)

    def test_the_watchdog_runs_alongside_every_launch(self):
        self.assertIn(
            'watch_for_fatal_jvm_error "$wrapper_output" "$pid" "$fatal_flag" &', SCRIPT_TEXT)


class ProcessTreeTest(unittest.TestCase):
    def test_existing_game_detection_uses_java_working_directory(self):
        body = re.search(r"^game_pids\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL | re.M)
        self.assertIsNotNone(body, "game_pids not found")
        self.assertIn("lsof -a -p", body.group("body"))
        self.assertIn('cwd" == "$resolved/"*', body.group("body"))

    def test_existing_game_is_refused_before_a_session_is_created(self):
        refusal = SCRIPT_TEXT.index("A Starsector process is already running inside this installation")
        session = SCRIPT_TEXT.index('ROOT="$HOME/.starsector-preflight/benchmarks/')
        self.assertLess(refusal, session)

    def test_terminate_signals_descendants_not_only_the_wrapper(self):
        # The game is a grandchild: this script -> wrapper -> starsector_mac.sh -> JVM.
        # Signalling only the wrapper left a crashed JVM alive past its own timeout.
        body = re.search(r"^terminate\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL | re.M)
        self.assertIsNotNone(body, "terminate not found")
        self.assertIn("descendants", body.group("body"))

    def test_descendants_are_collected_deepest_first(self):
        # A parent signalled first can reap or orphan its children before they are named.
        body = re.search(r"^descendants\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL | re.M)
        self.assertIsNotNone(body, "descendants not found")
        recursion = body.group("body").index("descendants \"$child\"")
        emit = body.group("body").index("printf '%s\\n' \"$child\"")
        self.assertLess(recursion, emit, "recursion must precede emitting the child")

    def test_descendant_only_termination_preserves_the_wrapper(self):
        body = re.search(
            r"^terminate_descendants\(\) \{(?P<body>.*?)\n\}",
            SCRIPT_TEXT,
            re.DOTALL | re.M,
        )
        self.assertIsNotNone(body, "terminate_descendants not found")
        self.assertIn('tree="$(descendants "$pid")"', body.group("body"))
        self.assertNotIn('printf \'%s\\n\' "$pid"', body.group("body"))

    def test_descendant_stop_prefers_the_published_game_jvm(self):
        body = re.search(
            r"^terminate_descendants\(\) \{(?P<body>.*?)\n\}",
            SCRIPT_TEXT,
            re.DOTALL | re.M,
        )
        self.assertIsNotNone(body, "terminate_descendants not found")
        exact = body.group("body").index('kill "$runtime_pid"')
        fallback = body.group("body").index('for target in $tree; do')
        self.assertLess(exact, fallback)
        self.assertIn('grep -qx "$runtime_pid" <<< "$tree"', body.group("body"))

    def test_descendants_finds_a_real_grandchild(self):
        source = re.search(r"^descendants\(\) \{.*?\n\}", SCRIPT_TEXT, re.DOTALL | re.M)
        script = (
            "set -uo pipefail\n"
            f"{source.group(0)}\n"
            "bash -c 'bash -c \"sleep 5\" & sleep 5' & parent=$!\n"
            "sleep 1\n"
            "descendants $parent | wc -l\n"
        )
        # The descendants inherit stdout, so read the count rather than waiting on EOF.
        result = subprocess.run(["bash", "-c", script], capture_output=True, text=True,
                                timeout=15)
        self.assertGreaterEqual(int(result.stdout.strip().splitlines()[0]), 2,
                                "expected to see the child and the grandchild")


class RecordedShapeTest(unittest.TestCase):
    """The shell writes these records and the Python reads them. Nothing else checks
    that the two agree, so a renamed field would otherwise surface 35 minutes into a
    session with the game already running."""

    def record(self, directory: Path, *args: str, recording: str = "true",
               protocol: str = "clicked") -> None:
        body = re.search(
            r"record_run\(\) \{(?P<body>.*?)\n\}", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(body, "record_run not found")
        script = (
            'set -euo pipefail\n'
            f'ROOT="{directory}"\n'
            f'RECORDING={recording}\n'
            f'PROTOCOL={protocol}\n'
            'RESULTS="$ROOT/results.jsonl"\n'
            f'record_run() {{{body.group("body")}\n}}\n'
            'record_run "$@"\n'
        )
        subprocess.run(
            ["bash", "-c", script, "record_run", *args],
            check=True, capture_output=True, text=True,
        )

    def test_an_accepted_record_reaches_the_report_intact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.record(root, "enabled", "2", "accepted", "", "81234", "9000", "0")
            written = json.loads((root / "results.jsonl").read_text(encoding="utf-8"))
            self.assertEqual("enabled", written["condition"])
            self.assertEqual(2, written["iteration"])
            self.assertIsNone(written["reason"])
            summary = report.summarize([written])
            self.assertEqual(81.23, summary["conditions"]["enabled"]["medianSeconds"])

    def test_the_discarded_settling_launch_never_reaches_the_results(self):
        # It is the slowest launch of the session -- it is the one that regenerates the
        # GraphicsLib cache -- so counting it would drag its condition's median up.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.record(root, "enabled", "0", "accepted", "", "120000", "9000", "0",
                        recording="false")
            self.assertFalse((root / "results.jsonl").exists())

    def test_the_warmup_disables_recording_around_the_settling_launch(self):
        # Checked as an ordering property rather than an exact layout: the settling launch
        # gained a retry loop, and a test pinned to the old three-line shape would have
        # failed for the reformatting while still passing if RECORDING had been dropped.
        block = re.search(
            r"RECORDING=false(?P<between>.*?)RECORDING=true", SCRIPT_TEXT, re.DOTALL
        )
        self.assertIsNotNone(block, "settling launch is not wrapped in RECORDING=false")
        self.assertIn("launch_once fast 0", block.group("between"))

    def test_an_excluded_record_carries_its_reason_and_no_timing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.record(root, "vanilla", "1", "excluded", "profile-drift",
                        "null", "null", "null")
            written = json.loads((root / "results.jsonl").read_text(encoding="utf-8"))
            self.assertEqual("profile-drift", written["reason"])
            self.assertIsNone(written["gameLogStartToMainMenuMs"])
            summary = report.summarize([written])
            self.assertEqual(0, summary["conditions"]["vanilla"]["successfulRuns"])
            self.assertEqual(["profile-drift"],
                             summary["conditions"]["vanilla"]["exclusionReasons"])


class UnattendedPromptTest(unittest.TestCase):
    """The prompts must not need an operator that --unattended promises is not there.

    On 2026-08-01 a ninety-minute campaign was announced as running and was not: piping one
    newline satisfied the settling prompt, the next prompt read EOF, and `set -e` ended the
    script one line after the header. Text assertions cannot catch that, so these run the
    function.
    """

    def confirm(self, stdin, unattended, argument='reply'):
        body = re.search(r"\nconfirm\(\) \{\n(?P<body>.*?)\n\}\n", SCRIPT_TEXT, re.DOTALL)
        self.assertIsNotNone(body, "confirm() not found")
        harness = (
            "set -euo pipefail\n"
            f"UNATTENDED={'true' if unattended else 'false'}\n"
            "note() { :; }\n"
            "confirm() {\n" + body.group("body") + "\n}\n"
            f"reply=UNSET\n"
            f"confirm 'prompt: ' {argument}\n"
            "printf 'rc=0 reply=[%s]' \"${reply-}\"\n"
        )
        return subprocess.run(
            ["bash", "-c", harness], input=stdin, capture_output=True, text=True)

    def test_unattended_never_blocks_and_never_consumes_stdin(self):
        done = self.confirm("", unattended=True)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("rc=0", done.stdout)
        self.assertIn("reply=[]", done.stdout)

    def test_closed_stdin_does_not_kill_the_run_under_set_e(self):
        # This is the exact failure: EOF made read return non-zero, and `set -e` did the rest.
        done = self.confirm("", unattended=False)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("reply=[]", done.stdout)

    def test_an_attended_answer_still_reaches_the_caller(self):
        # `skip` is how an operator declines the settling launch; it has to survive the helper.
        done = self.confirm("skip\n", unattended=False)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("reply=[skip]", done.stdout)

    def test_every_prompt_goes_through_the_helper(self):
        # A bare `read -r -p` reintroduces the bug for whichever prompt it is added to.
        prompts = [line for line in SCRIPT_TEXT.splitlines()
                   if "read -r -p" in line and "confirm()" not in line]
        self.assertEqual(
            1, len(prompts),
            "only confirm() itself may call `read -r -p`; found: " + repr(prompts))


class WindowsPreparedResourcesRunnerTest(unittest.TestCase):
    host = Path(__file__).with_name("run-big-red-windows-startup-cohort.sh").read_text()
    guest = Path(__file__).with_name("run-windows-startup-cohort.ps1").read_text()

    def test_process_collections_are_wrapped_at_every_call_site(self):
        calls = [line.strip() for line in self.guest.splitlines()
                 if "Get-GameProcesses " in line and not line.startswith("function ")]
        self.assertTrue(calls)
        for line in calls:
            with self.subTest(line=line):
                self.assertIn("@(Get-GameProcesses ", line)

    def test_shutdown_telemetry_is_bound_to_the_measured_run(self):
        self.assertIn("$gracefulShutdown = Stop-GameProcesses $Game $RunDirectory", self.guest)
        self.assertIn("$accepted = $graphicsPreloadObserved -and $elapsedMs -ne $null -and $gracefulShutdown",
                      self.guest)

    @unittest.skipUnless(sys.platform == "win32", "CIM regression requires Windows PowerShell")
    def test_shutdown_with_real_cim_objects_and_mocked_effects(self):
        # Prefer Windows PowerShell 5.1: singleton CIM behavior is the regression target.
        import shutil
        powershell = shutil.which("powershell.exe")
        if not powershell:
            self.skipTest("Windows PowerShell 5.1 is unavailable")
        done = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
             str(Path(__file__).with_name("test_windows_startup_shutdown.ps1"))],
            capture_output=True, text=True, timeout=30)
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertIn("PASS: 10 mocked shutdown cases", done.stdout)

    def host_command(self, *args):
        # Retain real parsing/validation and scheduled-task argument construction, but
        # exclude dependency checks, VM access, power changes and task execution.
        prefix = self.host[:self.host.index("for command in virsh")]
        construction = self.host[
            self.host.index('windows_prepared_resources_arg=""'):
            self.host.index('qga_ps "$run_ps"')]
        return subprocess.run(
            ["bash", "-c", prefix + construction + '\nprintf "%s\\n" "$run_ps"\n',
             "cohort-test", *args], capture_output=True, text=True, timeout=10)

    def test_host_forwards_requests_and_preserves_preset(self):
        cases = [
            ([], "preflight", None),
            (["--windows-prepared-resources"], "preflight", "WindowsPreparedResources"),
            (["--disable-windows-prepared-resources"], "preflight", "WindowsDisablePreparedResources"),
            (["--condition", "preflight-prepared-resources"], "preflight-prepared-resources", None),
        ]
        for args, condition, switch in cases:
            with self.subTest(args=args):
                done = self.host_command(*args)
                self.assertEqual(0, done.returncode, done.stderr)
                self.assertIn(f"-Conditions {condition} ", done.stdout)
                self.assertIn("-OptimizationPreset recommended", done.stdout)
                for candidate in ("WindowsPreparedResources", "WindowsDisablePreparedResources"):
                    self.assertEqual(candidate == switch, f" -{candidate}" in done.stdout)
                self.assertNotIn(" -WindowsPreparedPrefetchWorkers", done.stdout)
                self.assertNotIn(" -WindowsKaleidoscopePrefetchProbe", done.stdout)

    def test_host_forwards_read_ahead_and_attribution_controls(self):
        for flag, switch in (("--prepared-pack-read-ahead", "PreparedPackReadAhead"),
                             ("--disable-prepared-pack-read-ahead", "DisablePreparedPackReadAhead"),
                             ("--prepared-load-attribution", "PreparedLoadAttribution"),
                             ("--texture-upload-checkpoint", "TextureUploadCheckpoint")):
            done = self.host_command(flag)
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn(f" -{switch}", done.stdout)
        done = self.host_command("--prepared-pack-read-ahead", "--disable-prepared-pack-read-ahead")
        self.assertEqual(2, done.returncode, done.stderr)
        self.assertEqual("", done.stdout)

    def test_host_forwards_claim_controls_and_rejects_conflicts(self):
        for flag, switch in (
                ("--windows-prepared-resource-claims", "WindowsPreparedResourceClaims"),
                ("--disable-windows-prepared-resource-claims", "WindowsDisablePreparedResourceClaims")):
            done = self.host_command("--condition", "preflight-prepared-resources", flag)
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn(f" -{switch}", done.stdout)
        for conflict in ("--disable-windows-prepared-resource-claims", "--disable-windows-prepared-resources",
                         "--display-thread-texture-probe", "--spec-store-texture-overlap"):
            done = self.host_command("--windows-prepared-resource-claims", conflict)
            self.assertEqual(2, done.returncode, done.stderr)
            self.assertEqual("", done.stdout)
        done = self.host_command("--windows-prepared-resource-claims", "--condition", "preflight-fast-rendering")
        self.assertEqual(2, done.returncode, done.stderr)

    def test_guest_claim_requests_preserve_explicit_baseline(self):
        self.require_powershell()
        resolver = self.guest[self.guest.index("    $usesPreflight ="):
                              self.guest.index("    $usesKaleidoscopePrefetch =")]
        forwarding = self.guest[self.guest.index("            if ($null -ne $requestedPreparedResources)"):
                                self.guest.index("            if ($Condition -eq 'preflight-fast-rendering-prepared')")]
        for flag, expected in (("-WindowsPreparedResourceClaims", True),
                               ("-WindowsDisablePreparedResourceClaims", False)):
            body = ("$Condition = 'preflight-prepared-resources'\n$env:JAVA_TOOL_OPTIONS = ''\n" +
                    resolver + forwarding + "\n@{requested=$requestedPreparedResourceClaims; "
                    "options=$env:JAVA_TOOL_OPTIONS} | ConvertTo-Json -Compress")
            done = self.guest_command("-Conditions preflight-prepared-resources " + flag, body)
            self.assertEqual(0, done.returncode, done.stderr)
            result = json.loads(done.stdout)
            self.assertEqual(expected, result["requested"])
            self.assertEqual("-Dpreflight.texture.windowsPreparedResources=true "
                             f"-Dpreflight.texture.windowsPreparedResourceClaims={str(expected).lower()}",
                             result["options"])
        for conflict in ("-WindowsDisablePreparedResourceClaims", "-WindowsDisablePreparedResources",
                         "-WindowsPreparedSplitQueueProbe", "-WindowsPreparedPrefetchWorkers 2"):
            done = self.guest_command("-Conditions preflight -WindowsPreparedResourceClaims " + conflict)
            self.assertEqual(2, done.returncode, done.stderr)

    def test_host_forwards_barrier_controls_and_rejects_conflicts(self):
        for flag, switch in (
                ("--windows-prepared-byte-barrier", "WindowsPreparedByteBarrier"),
                ("--disable-windows-prepared-byte-barrier", "WindowsDisablePreparedByteBarrier")):
            done = self.host_command("--condition", "preflight-prepared-resources", flag)
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn(f" -{switch}", done.stdout)
        for conflict in ("--disable-windows-prepared-byte-barrier", "--disable-windows-prepared-resources",
                         "--display-thread-texture-probe", "--spec-store-texture-overlap"):
            done = self.host_command("--windows-prepared-byte-barrier", conflict)
            self.assertEqual(2, done.returncode, done.stderr)
            self.assertEqual("", done.stdout)
        done = self.host_command("--windows-prepared-byte-barrier", "--condition", "preflight-fast-rendering")
        self.assertEqual(2, done.returncode, done.stderr)

    def test_guest_barrier_requests_preserve_explicit_baseline(self):
        self.require_powershell()
        resolver = self.guest[self.guest.index("    $usesPreflight ="):
                              self.guest.index("    $usesKaleidoscopePrefetch =")]
        forwarding = self.guest[self.guest.index("            if ($null -ne $requestedPreparedResources)"):
                                self.guest.index("            if ($Condition -eq 'preflight-fast-rendering-prepared')")]
        for flag, expected in (("-WindowsPreparedByteBarrier", True),
                               ("-WindowsDisablePreparedByteBarrier", False)):
            body = ("$Condition = 'preflight-prepared-resources'\n$env:JAVA_TOOL_OPTIONS = ''\n" +
                    resolver + forwarding + "\n@{requested=$requestedPreparedByteBarrier; "
                    "options=$env:JAVA_TOOL_OPTIONS} | ConvertTo-Json -Compress")
            done = self.guest_command("-Conditions preflight-prepared-resources " + flag, body)
            self.assertEqual(0, done.returncode, done.stderr)
            result = json.loads(done.stdout)
            self.assertEqual(expected, result["requested"])
            self.assertEqual("-Dpreflight.texture.windowsPreparedResources=true "
                             f"-Dpreflight.texture.windowsPreparedByteBarrier={str(expected).lower()}",
                             result["options"])
        for conflict in ("-WindowsDisablePreparedByteBarrier", "-WindowsDisablePreparedResources",
                         "-WindowsPreparedSplitQueueProbe", "-WindowsPreparedPrefetchWorkers 2"):
            done = self.guest_command("-Conditions preflight -WindowsPreparedByteBarrier " + conflict)
            self.assertEqual(2, done.returncode, done.stderr)

    def test_host_rejects_conflicts_before_host_effects(self):
        cases = []
        for candidate in (["--windows-prepared-resources"],
                          ["--condition", "preflight-prepared-resources"]):
            for conflict in ("--disable-windows-prepared-resources",
                             "--display-thread-texture-probe",
                             "--display-thread-spec-store-probe", "--spec-store-texture-overlap"):
                cases.append([*candidate, conflict])
        for condition in ("preflight-spec-store-texture-overlap", "fast-rendering",
                          "preflight-fast-rendering", "preflight-fast-rendering-prepared"):
            cases.append(["--windows-prepared-resources", "--condition", condition])
        for args in cases:
            with self.subTest(args=args):
                done = self.host_command(*args)
                self.assertEqual(2, done.returncode, done.stderr)
                self.assertIn("Prepared resources", done.stderr)
                self.assertEqual("", done.stdout)

    def require_powershell(self):
        import shutil
        powershell = shutil.which("pwsh") or shutil.which("powershell")
        if not powershell:
            self.skipTest("PowerShell is not installed; guest parser/behavior checks need pwsh")
        return powershell

    def guest_command(self, arguments, body="'validated'"):
        powershell = self.require_powershell()
        # The real param block and guards end before the first environment mutation.
        prefix = self.guest[:self.guest.index("if ([string]::IsNullOrWhiteSpace($GalliumDriver)")]
        script = "try { & {\n" + prefix + body + "\n} " + arguments + " } catch { Write-Error $_; exit 2 }"
        return subprocess.run([powershell, "-NoProfile", "-NonInteractive", "-Command", script],
                              capture_output=True, text=True, timeout=20)

    def test_guest_rejects_incompatible_scheduling_and_renderers(self):
        self.require_powershell()
        conflicts = ["-WindowsPreparedPrefetchWorkers 2", "-WindowsPreparedSplitQueueProbe",
                     "-WindowsPrefetchBypassProbe", "-WindowsSharedContextTextureProbe",
                     "-WindowsDisplayThreadTextureProbe", "-WindowsDisplayThreadSpecStoreProbe",
                     "-WindowsSpecStoreTextureOverlap", "-WindowsDisablePreparedResources"]
        for conflict in conflicts:
            with self.subTest(conflict=conflict):
                done = self.guest_command("-Conditions preflight-prepared-resources " + conflict)
                self.assertEqual(2, done.returncode, done.stderr)
                self.assertIn("Prepared resources", done.stderr)
        for condition in ("fast-rendering", "preflight-fast-rendering",
                          "preflight-fast-rendering-prepared", "preflight-spec-store-texture-overlap"):
            with self.subTest(condition=condition):
                done = self.guest_command(
                    "-Conditions @('preflight-prepared-resources','" + condition + "')")
                self.assertEqual(2, done.returncode, done.stderr)
                self.assertIn("Prepared resources", done.stderr)
        done = self.guest_command("-WindowsPreparedResources")
        self.assertEqual(2, done.returncode, done.stderr)
        self.assertIn("Fast Rendering", done.stderr)

    def test_guest_resolves_candidate_baseline_and_default_property(self):
        self.require_powershell()
        resolver = self.guest[self.guest.index("    $usesPreflight ="):
                              self.guest.index("    $usesKaleidoscopePrefetch =")]
        forwarding = self.guest[self.guest.index("            if ($null -ne $requestedPreparedResources)"):
                                self.guest.index("            if ($Condition -eq 'preflight-fast-rendering-prepared')")]
        cases = [
            ("-Conditions @('preflight-prepared-resources','preflight')", "preflight-prepared-resources", True),
            ("-Conditions @('preflight-prepared-resources','preflight')", "preflight", False),
            ("-Conditions preflight -WindowsPreparedResources", "preflight", True),
            ("-Conditions preflight -WindowsDisablePreparedResources", "preflight", False),
            ("-Conditions preflight", "preflight", None),
        ]
        for arguments, condition, expected in cases:
            with self.subTest(arguments=arguments, condition=condition):
                body = (f"$Condition = '{condition}'\n$env:JAVA_TOOL_OPTIONS = ''\n" + resolver +
                        forwarding + "\n@{requested=$requestedPreparedResources; options=$env:JAVA_TOOL_OPTIONS} | ConvertTo-Json -Compress")
                done = self.guest_command(arguments, body)
                self.assertEqual(0, done.returncode, done.stderr)
                result = json.loads(done.stdout)
                self.assertEqual(expected, result["requested"])
                if expected is None:
                    self.assertFalse(result["options"])
                else:
                    self.assertEqual(f"-Dpreflight.texture.windowsPreparedResources={str(expected).lower()}",
                                     result["options"])


if __name__ == "__main__":
    unittest.main()
