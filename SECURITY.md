# Security policy

## Reporting a vulnerability

Please keep exploitable details out of public issues, pull requests, discussions, logs, screenshots, and diagnostic archives.

If GitHub shows **Report a vulnerability** on this repository's Security page, use that private reporting path. If that option is unavailable, open a minimal public issue containing only:

- that you need to report a security vulnerability privately;
- the affected Preflight version or commit, if known;
- the broad component involved, such as desktop updates, report intake, launcher execution, cache handling, or CI.

Do not include reproduction steps, payloads, credentials, tokens, private URLs, user data, or exploit details in that public issue. A private follow-up channel can then be arranged.

Do not send Starsector binaries, mod binaries, saves, registration data, personal paths, or other proprietary/private game data with a report. A minimal synthetic reproduction is preferred whenever possible.

## Security-sensitive boundaries

Reports are especially useful when they involve:

- execution of repository-controlled or downloaded code outside its intended child process or container boundary;
- update-signature verification, rollback, or package-integrity bypasses;
- path traversal, symlink escape, unsafe archive extraction, or writes outside the documented Preflight data roots;
- cache corruption or identity-validation failures that can cause incompatible prepared data to be used instead of falling back;
- desktop child-process ownership, unintended process execution, or command/argument injection;
- diagnostic archive disclosure beyond the documented bounded allowlist and redaction rules;
- report-intake authorization, upload/finalization/deletion, retention, decompression, or object-isolation failures;
- self-hosted CI behavior that lets an unreviewed revision execute on the persistent runner host;
- secrets, signing material, access tokens, or private service credentials committed to source or exposed through build artifacts.

Ordinary crashes, performance regressions, mod compatibility problems, and expected unsigned-package warnings belong in the normal issue/support flow unless they cross one of the boundaries above.

## Supported versions

Security fixes are targeted at the current `main` branch and the latest published Preflight beta/release. Older development snapshots may be asked to update before a report is investigated further.

## Disclosure

Please allow time for the affected boundary to be reproduced, fixed, and validated before publishing technical exploit details. Once a fix is available, coordinated disclosure and credit can be discussed with the reporter.
