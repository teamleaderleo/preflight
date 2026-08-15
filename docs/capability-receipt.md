# What the packaged app can do

Every Preflight package carries `engine/capability-receipt.json`. It records the exact source
revision and engine JAR in that package, then lists the native commands, filesystem writes, child
processes, fixed links, update endpoint, and optional report-upload origin available to it.

The short version:

- Preflight writes its own caches, profiles, evidence, settings, and backups.
- It changes `enabled_mods.json`, Starsector's launcher preferences, or the selected launcher's
  memory file only after an explicit user action. Those paths are bounded, rechecked, and backed up.
- It doesn't write saves, game JARs, mod archives, game assets, or activation data.
- It launches its bundled Java engine and the Starsector launcher the user selected. The UI and
  network don't supply arbitrary shell commands.
- Ordinary preparation and launch send no telemetry. Update checks use the fixed signed-update URL
  in the receipt. A support ZIP is uploaded only after the user confirms it, and only when that
  release was compiled with the exact HTTPS intake origin shown in the receipt.

## Why the receipt is useful

The readable policy lives in
`preflight-desktop/capabilities/release-receipt-policy.json`. Native command names, Tauri
permissions, fixed links, and endpoints are derived from the code during packaging. A separate
source lock covers the native host and Java files that own writes and child processes. If one of
those boundaries changes, package verification fails until the capability review is deliberately
updated.

`bundle.json` pins the receipt's byte length and SHA-256. The receipt pins the engine JAR's SHA-256.
Each platform also publishes a `CAPABILITIES-<platform>-<architecture>.json` receipt that binds the
same statement to the byte length and SHA-256 of every installer, package, update archive, and
signature from that build. The final release verifier requires one matching statement across
Windows, macOS, and Linux. This makes the result specific to the artifacts being downloaded.
