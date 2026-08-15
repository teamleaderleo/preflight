# Publishing a verified release

This is the maintainer procedure for turning a verified tagged candidate into a public GitHub release.

## Boundary

Pushing a `v*` tag does **not** authorize public publication. The `Distribution` workflow builds the core and three desktop packages, constructs the updater feed, runs the complete release verifier, preserves that exact verified file set as a workflow artifact, and creates a **draft** GitHub release containing those same files.

The draft stays private until a maintainer explicitly runs the separate **Publish verified release** workflow.

Publication never rebuilds the candidate. If the original verified artifact is unavailable, the tag moved, the draft changed, or any draft asset differs from the preserved verified bytes, publication fails and a new candidate must be reviewed.

## Maintainer approval step

After the tagged `Distribution` run succeeds and the release/publication decision has been accepted:

1. Open the successful tagged `Distribution` workflow run and record its numeric run ID.
2. Review the draft release, release notes, candidate evidence, and any remaining release-readiness gates.
3. In GitHub Actions, run **Publish verified release** manually from the default branch.
4. Enter the exact release tag, such as `v0.1.0`, and the successful tagged `Distribution` run ID that created the draft.
5. Treat a failed publication check as a changed or unbound candidate. Do not bypass it by rebuilding or manually undrafting the release.

The manual workflow checks all of the following before it can make the release public:

- the requested tag is a valid release version;
- the supplied run is the successful `Distribution` workflow triggered by a tag push;
- that run's `head_sha` equals the current tag commit;
- the GitHub release still exists as a draft with the reviewed title and notes;
- the preserved `preflight-complete-release-<run id>` workflow artifact is still available;
- every current draft asset name, size, and SHA-256 digest matches the preserved verified artifact byte-for-byte;
- `scripts/verify_complete_release.py` still accepts the downloaded draft asset set;
- the draft release metadata/asset identities do not change during verification; and
- the remote tag does not move during verification.

Only after those checks pass does the workflow run `gh release edit <tag> --draft=false`.

## Failure and retry

A failed publication workflow leaves the release as a draft. Fix the cause and rerun the manual workflow only when the same reviewed candidate remains valid.

If the tag or candidate bytes changed, create and review a new tagged candidate. If the preserved workflow artifact expired, rerun the release-candidate process under a new reviewed tag instead of reconstructing the old candidate from unbound files.
