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
        for publication in ("README.md", "docs/release-readiness.md", "docs/optimization-history.md"):
            path = self.root / publication
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("83-mod development result: 15.88 seconds\n", encoding="utf-8")
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
            "publishedIn": ["README.md", "docs/release-readiness.md", "docs/optimization-history.md"],
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
        self.assertEqual(3, report["publicationsChecked"])

    def test_rejects_missing_evidence_assertion(self):
        self.claim["evidenceAssertions"].append("not in evidence")
        self.write_claims()
        with self.assertRaisesRegex(verify_claims.ClaimError, "missing assertion"):
            verify_claims.validate_claims(self.root)

    def test_rejects_publication_drift(self):
        (self.root / "README.md").write_text("83-mod development result changed\n", encoding="utf-8")
        with self.assertRaisesRegex(verify_claims.ClaimError, "no longer carries"):
            verify_claims.validate_claims(self.root)

    def test_rejects_evidence_path_escape(self):
        self.claim["evidence"]["path"] = "../outside.md"
        self.write_claims()
        with self.assertRaisesRegex(verify_claims.ClaimError, "unsafe repository path|must stay under"):
            verify_claims.validate_claims(self.root)


if __name__ == "__main__":
    unittest.main()
