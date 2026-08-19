from pathlib import Path


def require_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    assert count == 1, f"{label}: expected exactly one match, got {count}"
    return text.replace(old, new, 1)


# Distribution: remove only the report-intake compile-time credential. Preserve updater signing,
# candidate archive encryption and all package/candidate/publication gates.
workflow_path = Path(".github/workflows/distribution.yml")
workflow = workflow_path.read_text()
workflow = require_once(
    workflow,
    "Validate private-candidate report intake",
    "Validate private-candidate archive credentials",
    "candidate credential step name",
)
removed = [
    line for line in workflow.splitlines(keepends=True)
    if "PREFLIGHT_REPORT_INTAKE_ORIGIN" in line or "REPORT_INTAKE_ORIGIN" in line
]
assert len(removed) >= 7, f"expected reviewed report-origin lines, found {len(removed)}"
workflow = "".join(
    line for line in workflow.splitlines(keepends=True)
    if "PREFLIGHT_REPORT_INTAKE_ORIGIN" not in line and "REPORT_INTAKE_ORIGIN" not in line
)
assert "PREFLIGHT_REPORT_INTAKE_ORIGIN" not in workflow
assert "REPORT_INTAKE_ORIGIN" not in workflow
assert "TAURI_SIGNING_PRIVATE_KEY" in workflow
assert "PREFLIGHT_UPDATER_PUBLIC_KEY" in workflow
assert "PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD" in workflow
workflow_path.write_text(workflow)

# Existing Distribution policy: drop the old remote-origin requirements and replace them with one
# global absence assertion. Keep every updater/platform/encryption assertion around it.
policy_path = Path("preflight-desktop/scripts/distribution-workflow.node-test.mjs")
policy = policy_path.read_text()
policy = require_once(
    policy,
    '  assert.match(distribution, /PREFLIGHT_REPORT_INTAKE_ORIGIN is required for a private signed candidate/);\n',
    '  assert.doesNotMatch(workflow, /PREFLIGHT_REPORT_INTAKE_ORIGIN|REPORT_INTAKE_ORIGIN/);\n',
    "private-candidate origin policy",
)
policy = require_once(
    policy,
    '    assert.match(platformJob, /PREFLIGHT_REPORT_INTAKE_ORIGIN:.*inputs\\.signed_candidate/);\n',
    '',
    "platform origin environment assertion",
)
old_multiline = '''    assert.match(\n      platformJob,\n      /Test frontend and prepare desktop engine[\\s\\S]*?PREFLIGHT_UPDATER_PUBLIC_KEY:[\\s\\S]*?PREFLIGHT_REPORT_INTAKE_ORIGIN:/,\n    );\n'''
policy = require_once(
    policy,
    old_multiline,
    '    assert.match(platformJob, /Test frontend and prepare desktop engine[\\s\\S]*?PREFLIGHT_UPDATER_PUBLIC_KEY:/);\n',
    "prepared frontend credential assertion",
)
assert "PREFLIGHT_REPORT_INTAKE_ORIGIN:" not in policy
policy_path.write_text(policy)

# Closeout: the first beta is now deliberately local-only. Remove release-origin owner work and the
# remote canary, replacing it with exact candidate evidence for the capability that will actually ship.
closeout_path = Path("docs/release-gate-closeout.md")
closeout = closeout_path.read_text()
closeout = require_once(
    closeout,
    '`PREFLIGHT_REPORT_INTAKE_ORIGIN` is an HTTPS origin. A `release-signing` Environment variable is the narrowest useful production scope for it.\n\n',
    '',
    "release-signing report origin paragraph",
)
start = closeout.index("### Production report-intake candidate canary\n")
end = closeout.index("### Checksums, SBOMs, dependencies, legal/privacy/install docs\n", start)
replacement = '''### Local-only diagnostics candidate evidence\n\nThe first beta deliberately ships no remote report-intake capability. Final candidate evidence should prove that smaller boundary instead of exercising a service the package cannot contact.\n\nOn each exact candidate package:\n\n1. open Help and create the bounded disclosed support ZIP;\n2. verify the inclusion/exclusion disclosure and retained local ZIP bytes/SHA-256;\n3. verify Help exposes no remote review/send/delete controls and Settings exposes no automatic-report toggle;\n4. verify a stale automatic-report preference cannot inspect, export, or send a failed run once authoritative local-only status is known;\n5. verify a transient intake-status read failure remains fail-closed for runtime remote actions without being treated as an authoritative state reset;\n6. verify the packaged native build contains no configured report-intake origin and Distribution supplied no report-intake environment key;\n7. verify full Preflight-data removal clears local reporting preferences/receipts without contacting a remote service.\n\nRemote reporting remains post-beta work and requires its own reviewed authority, migration, retention, and deletion evidence before a later release enables it.\n\n'''
closeout = closeout[:start] + replacement + closeout[end:]
closeout = require_once(
    closeout,
    '- configure the exact production report-intake origin/service credentials;\n',
    '',
    "owner-only report origin blocker",
)
closeout = require_once(
    closeout,
    '- run the final packaged production report-intake canary;\n',
    '- run the final packaged local-only diagnostics capability audit;\n',
    "candidate report canary item",
)
closeout_path.write_text(closeout)
