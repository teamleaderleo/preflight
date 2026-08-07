# Release dependency inventory

Every release publishes machine-readable CycloneDX software bills of materials beside the packages
and includes them in the standalone ZIP and tar archive. They describe the code shipped in the
standalone Java launcher and the supported native desktop packages:

- `preflight-java.cdx.json` covers the Java launcher, agent, and their compile/runtime dependency
  graph. Test fixtures and the synthetic-startup test module are excluded.
- `preflight-desktop-web.cdx.json` covers the production web-interface graph locked by
  `preflight-desktop/package-lock.json`. Development and test packages are excluded.
- `preflight-desktop-native-aarch64-apple-darwin.cdx.json` covers the Rust host used by the macOS
  package.
- `preflight-desktop-native-x86_64-pc-windows-msvc.cdx.json` covers the Rust host used by the
  Windows package.
- `preflight-desktop-native-x86_64-unknown-linux-gnu.cdx.json` covers the Rust host used by the
  Linux packages.

The release workflow generates these files from the exact Maven reactor, npm lockfile, Cargo
manifest, and Cargo lockfile at the release commit. Maven and npm emit CycloneDX 1.6; the current
Cargo generator emits CycloneDX 1.5. Each generator validates its output, and the release stops if
an expected inventory is missing or empty. `SBOM-SHA256SUMS.txt` records their checksums.

The distribution job also runs `scripts/verify_release_boundary.py` after assembly. It requires the
exact documented core file set, validates every checksum and CycloneDX document, compares each ZIP
and tar member byte-for-byte with the staged file, rejects links and unsafe paths, and only accepts
reviewed project and third-party namespaces inside `preflight.jar`. An accidental copy from a game
installation, save folder, diagnostics directory, or workspace therefore stops the release job.

The private report-intake service isn't installed on a user's computer, so its Worker dependencies
aren't mixed into the client SBOMs. Its exact production dependency graph remains locked in
`report-intake/package-lock.json` and is audited by the separate Worker verification workflow.

An SBOM records dependency identity and declared metadata. It doesn't replace the project's
[third-party notices](../THIRD_PARTY_NOTICES.md), prove that every upstream license declaration is
correct, or certify that a component is free of vulnerabilities.
