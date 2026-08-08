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

The archive was small enough to finish before the packaged UI accepted a cancellation click. This
run therefore establishes packaged disclosure, consent, upload, finalization, receipt persistence
and scoped remote deletion. It doesn't claim packaged cancellation. The native transport test
covers cancellation during upload, authorized cleanup of the incomplete case and preservation of
the local ZIP; a larger packaged cancellation canary remains in the release checklist.

The first launch of this package also found a pre-window crash in ordinary builds. The updater
plugin was registered while the base Tauri configuration supplied no `plugins.updater` object, so
plugin deserialization received `null` and panicked. Ordinary packages now carry an inert updater
configuration; signed-release overlays replace its empty public key with the real key. Package
verification now starts the copied native host with a bounded smoke mode after checking the
embedded engine. The rebuilt DMG passed both native-host and engine startup checks before the UI
canary ran.
