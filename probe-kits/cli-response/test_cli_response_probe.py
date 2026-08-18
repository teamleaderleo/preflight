#!/usr/bin/env python3

import os
from pathlib import Path
import sys
import tempfile
import unittest

from cli_response_probe import (
    Case,
    build_cases,
    case_command,
    measure_case,
    parses_json_document,
    redirected_has_ansi,
    selected_cases,
)


class CliResponseProbeTest(unittest.TestCase):
    def test_required_everyday_cases_are_present(self) -> None:
        names = {case.name for case in build_cases()}
        self.assertTrue(
            {
                "help",
                "doctor",
                "profile-list",
                "profile-list-json",
                "cache-summary",
                "cache-summary-json",
                "prepare-plan",
                "prepare-plan-json",
                "cache-repair-preview",
                "cache-repair-preview-json",
                "cache-prune-preview",
                "cache-prune-preview-json",
                "uninstall-preview",
                "uninstall-preview-json",
            }.issubset(names)
        )

    def test_profile_activation_preview_is_opt_in(self) -> None:
        ordinary = {case.name for case in build_cases()}
        with_profile = {case.name for case in build_cases("campaign")}
        self.assertNotIn("profile-activate-preview", ordinary)
        self.assertIn("profile-activate-preview", with_profile)
        self.assertIn("profile-activate-preview-json", with_profile)

    def test_game_and_launcher_are_only_appended_when_supported(self) -> None:
        prefix = ["preflight"]
        doctor = Case("doctor", ("doctor",), accepts_game=True, accepts_launcher=True)
        uninstall = Case("uninstall", ("uninstall",))
        game = Path("/game")
        launcher = Path("/launcher")

        self.assertEqual(
            ["preflight", "doctor", "--game", "/game", "--launcher", "/launcher"],
            case_command(prefix, doctor, game, launcher),
        )
        self.assertEqual(["preflight", "uninstall"], case_command(prefix, uninstall, game, launcher))

    def test_measurement_captures_json_and_redirected_ansi(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory) / "fake_preflight.py"
            fake.write_text(
                "import json, sys\n"
                "if '--json' in sys.argv[1:]:\n"
                "    print(json.dumps({'ok': True}))\n"
                "else:\n"
                "    print('ok')\n",
                encoding="utf-8",
            )
            result = measure_case(
                [sys.executable, str(fake)],
                Case("json", ("cache", "--json"), expects_json=True),
                rounds=2,
                timeout_seconds=5.0,
                environment=dict(os.environ),
            )

        self.assertTrue(result["jsonValid"])
        self.assertFalse(result["redirectedStdoutHasAnsi"])
        self.assertFalse(result["redirectedStderrHasAnsi"])
        self.assertEqual([0, 0], result["exitCodes"])
        self.assertGreater(result["firstProcessMs"], 0)
        self.assertGreater(result["repeatMedianMs"], 0)

    def test_machine_boundary_helpers_reject_noise(self) -> None:
        self.assertTrue(parses_json_document('{"ok":true}'))
        self.assertFalse(parses_json_document('status\n{"ok":true}'))
        self.assertTrue(redirected_has_ansi("\x1b[32mok\x1b[0m"))
        self.assertFalse(redirected_has_ansi("ok"))

    def test_case_filter_rejects_unknown_names(self) -> None:
        cases = build_cases()
        self.assertEqual(["doctor"], [case.name for case in selected_cases(cases, ["doctor"])])
        with self.assertRaises(ValueError):
            selected_cases(cases, ["missing"])


if __name__ == "__main__":
    unittest.main()
