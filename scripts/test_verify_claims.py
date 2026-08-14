#!/usr/bin/env python3

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_claims


class VerifyClaimsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / "docs/evidence").mkdir(parents=True)
        (self.root / "docs/startup-benchmark.md").write_text("protocol\n", encoding="utf-8")
        self.evidence = self.root / "docs/evidence/result.md"
        self.evidence.write_text(
            "0.98a-RC8 83-mod profile 15.88s run-1 fast-1 42/42 15,469 ACTIVE zero decline/failure\n",
            encoding="utf-8",
        )
        for publication in ("README.md", "docs/optimization-history.md"):
            path = self.root / publication
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("83-mod development result: 15.88 seconds\n", encoding="utf-8")
        (self.root / "docs/release-readiness.md").write_text(
            "Performance record: 15.88 seconds; packaged benchmark pending.\n",
            encoding="utf-8",
        )
        self.claim = {
            "id": "development-result",
            "status": "accepted",
            "scope": "development",
            "publicClaimEligibility": "development-context-only",
            "gameBuild": "0.98a-RC8",
            "profile": {"description": "83-mod profile", "modCount": 83},
            "environment": {"platform": "macOS"},
            "protocol": {
                "id": "direct",
                "document": "docs/startup-benchmark.md",
                "measurement": "main-menu-time",
                "clockBoundary": "direct to menu",
            },
            "result": {"seconds": 15.88},
            "evidence": {"path": "docs/evidence/result.md", "runId": "run-1", "condition": "fast-1"},
            "health": {},
            "evidenceAssertions": ["15.88s", "run-1", "fast-1", "42/42"],
            "publishedIn": ["README.md", "docs/optimization-history.md"],
            "mentionedIn": ["docs/release-readiness.md"],
            "releaseCandidate": False,
            "supersedes": [],
            "supersededBy": None,
        }
        self.write_claims()

    def tearDown(self):
        self.temporary.cleanup()

    def write_claims(self):
        (self.root / "docs/claims.json").write_text(
            json.dumps({"format": verify_claims.FORMAT, "claims": [self.claim]}),
            encoding="utf-8",
        )

    def test_accepts_evidence_backed_current_claim(self):
        report = verify_claims.validate_claims(self.root)
        self.assertEqual(1, report["claims"])
        self.assertEqual(1, report["acceptedClaims"])
        self.assertEqual(4, report["evidenceAssertionsChecked"])
        self.assertEqual(2, report["publicationsChecked"])
        self.assertEqual(1, report["mentionsChecked"])

    def test_rejects_missing_evidence_assertion(self):
        self.claim["evidenceAssertions"].append("not in evidence")
        self.write_claims()
        with self.assertRaisesRegex(verify_claims.ClaimError, "missing assertion"):
            verify_claims.validate_claims(self.root)

    def test_rejects_contextualized_publication_drift(self):
        (self.root / "README.md").write_text("83-mod development result changed\n", encoding="utf-8")
        with self.assertRaisesRegex(verify_claims.ClaimError, "no longer carries"):
            verify_claims.validate_claims(self.root)

    def test_rejects_abbreviated_mention_drift(self):
        (self.root / "docs/release-readiness.md").write_text("Packaged benchmark pending.\n", encoding="utf-8")
        with self.assertRaisesRegex(verify_claims.ClaimError, "no longer mentions"):
            verify_claims.validate_claims(self.root)

    def test_accepts_a_published_number_rounded_from_its_evidence(self):
        self.evidence.write_text(
            self.evidence.read_text(encoding="utf-8") + "GraphicsLib compact replay -4.821s\n",
            encoding="utf-8",
        )
        (self.root / "README.md").write_text(
            "83-mod development result: 15.88 seconds\n"
            "**4.82s removed from the measured sequence**\n",
            encoding="utf-8",
        )
        report = verify_claims.validate_claims(self.root)
        self.assertEqual(1, report["publishedSecondsChecked"])

    def test_rejects_a_published_number_with_no_evidence(self):
        (self.root / "README.md").write_text(
            "83-mod development result: 15.88 seconds\n"
            "**Preflight reaches 9.12 seconds on a 120-mod profile.**\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(verify_claims.ClaimError, "no retained evidence"):
            verify_claims.validate_claims(self.root)

    def test_rejects_evidence_path_escape(self):
        self.claim["evidence"]["path"] = "../outside.md"
        self.write_claims()
        with self.assertRaisesRegex(verify_claims.ClaimError, "unsafe repository path|must stay under"):
            verify_claims.validate_claims(self.root)


if __name__ == "__main__":
    unittest.main()
