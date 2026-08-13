# Updater release origin boundary

Recorded 2026-08-09.

## Gap

The final 32-file release verifier already required HTTPS updater URLs, the expected asset filename,
matching detached signatures, and a checksum-qualified `latest.json`. That still allowed the feed to
name the right package on an arbitrary HTTPS host or under a different release tag. A valid final
directory could therefore point installed clients at bytes outside the reviewed publication path.

## Boundary

`verify_complete_release.py` now accepts one consistent URL mode per candidate:

- a public release uses
  `https://github.com/teamleaderleo/preflight/releases/download/v<manifest-version>/<asset>`;
- a private candidate uses
  `https://private-candidate.invalid/run-<positive-integer>/<asset>`.

The private host is intentionally inert and remains suitable for candidate assembly. Public and
private URLs can't be mixed. Alternate hosts, explicit ports, a tag that differs from the manifest
version, credentials, query strings, fragments, encoded filename substitutions, and unexpected
paths fail the complete-release check.

This closes a launch-free publication-boundary gap. It doesn't prove updater installation or
rollback; those still require signed packages and a running application.

## Verification

`python3 scripts/test_verify_complete_release.py` now covers the exact public path, the inert private
candidate path, an arbitrary HTTPS origin, another release tag, mixed URL modes, another asset,
signature drift, checksum drift, extra files, an explicit port, and the command-line report. All ten
tests pass.
