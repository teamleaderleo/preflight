# Packaged report intake canary

**Date:** 2026-08-08  
**Platform:** macOS arm64  
**Game launched:** no

A release-mode package was built with the production report-intake origin and exercised through
the packaged UI. It exported the newest allowed run and benchmark metadata to a 197,379-byte ZIP,
showed the exact path, entry list, size, SHA-256, exclusions, retention policy and intake origin,
then uploaded the file after the explicit send action. The intake accepted case
`9fa9edc0-16ef-45eb-a597-e258b1b20a6a` with the same size and digest. Its case-specific deletion
grant returned HTTP 204 and removed the object. The local deletion receipt was removed after the
remote cleanup.

The first archive was small enough to finish before the packaged UI accepted a cancellation click.
A second canary added one clearly marked synthetic run session made from allowed JSON metadata,
producing a 3,762,549-byte ZIP with SHA-256
`97a814b5a234487463052a31db863181ed115426b5e108d15f355b7e9c7de36c`. Preflight cancelled after
256 KiB had streamed and reported success only after the native host confirmed deletion of the
incomplete server case. The same local ZIP remained available for retry. The retry completed as
case `cefc8e15-8abe-4613-9b39-8bf7b3fd5682`, returned the same size and digest, and was removed with
its exact scoped grant (HTTP 204).

Together the two runs establish packaged disclosure, consent, upload cancellation with confirmed
server cleanup, local-archive preservation, retry, finalization, receipt persistence and scoped
remote deletion. After the app closed, the exact synthetic evidence session and its exported ZIP
were moved to Trash. No existing run or benchmark session was removed.

The first launch of this package also found a pre-window crash in ordinary builds. The updater
plugin was registered while the base Tauri configuration supplied no `plugins.updater` object, so
plugin deserialization received `null` and panicked. Ordinary packages now carry an inert updater
configuration; signed-release overlays replace its empty public key with the real key. Package
verification now starts the copied native host with a bounded smoke mode after checking the
embedded engine. The rebuilt DMG passed both native-host and engine startup checks before the UI
canary ran.
