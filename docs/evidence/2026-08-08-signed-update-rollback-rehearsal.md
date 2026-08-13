# Signed macOS update and rollback rehearsal

**Date:** 2026-08-08

**Platform:** macOS arm64
**Scope:** isolated Preflight application copies; Starsector wasn't launched

## Result

A signed 0.1.0 build discovered, downloaded, verified, installed, and restarted into 0.1.1 through
the packaged updater UI. A second 0.1.2 feed retained a structurally valid Minisign document with
an altered cryptographic signature. The updater downloaded its complete 41,371,576-byte archive,
reported `The signature verification failed`, and left the installed 0.1.1 application tree
byte-for-byte unchanged.

Rollback used the preserved 0.1.0 DMG instead of enabling updater downgrades. Its published local
SHA-256 manifest passed, the DMG was mounted read-only, and the copied application matched the
mounted package tree exactly. The rolled-back app reported version 0.1.0 and reopened with the
existing Starsector installation and prepared-profile state intact.

The native macOS removal exercise also mounted the update-enabled DMG, copied the app into a fresh
temporary installation directory, ran the bundled-engine smoke check, removed the copied bundle,
and confirmed that a separately owned data sentinel remained unchanged.

## Isolation and transport

Production builds still compile the fixed GitHub `latest.json` endpoint. The rehearsal adds an
HTTPS-only compile-time endpoint seam, and a workflow test requires the distribution workflow to
omit it. Both temporary builds used the real project updater key and the same short-lived HTTPS
endpoint.

The first transport attempt used Wrangler's tunneled static-assets server. Cloudflare refused the
39.5 MiB archive because Workers static assets have a 25 MiB per-file limit. The completed check
used a Quick Tunnel to the repository's localhost-only server instead. That server allows only
exact top-level `GET` and `HEAD` requests, rejects directories and symbolic links, and sends
`Cache-Control: no-store`. The tunnel and local server were stopped after the rehearsal; no Worker,
route, bucket object, or GitHub release was created.

## Package evidence

Baseline 0.1.0:

```text
63f07122b8f837afb167aeec5c3dbee9975ed0b956dac2e9fccc8cfda7e59e05  Preflight-macOS-arm64.dmg
a5e176ee92a2249e260dfdc918f15de5d91b245b172659f34475069e8c3af81f  Preflight-macOS-arm64.app.tar.gz
2557d4fc5e51ea01f4cf051e9231371610ef0ba21c6d3de19093a369555a114c  Preflight-macOS-arm64.app.tar.gz.sig
```

Update 0.1.1:

```text
b19f0ebdc6cb18bb75db3fa232e2af885fb8e86eb1cc01e43b0f837ce1244e9a  Preflight-macOS-arm64.dmg
911c60c9a2ecfaf5fd6a2f21ef44490efd3f893d0457f7fc77189e959e02583c  Preflight-macOS-arm64.app.tar.gz
cb74181ece0442b77da919299ad939efa96aaead1578e8bec70f4fd82939f335  Preflight-macOS-arm64.app.tar.gz.sig
```

Both package builds passed DMG mounting, updater-archive/DMG tree equivalence, the 106-file bundled
runtime boundary, and the engine smoke command. The expected development packages have no paid
Developer ID signature; the independent Tauri update signature is the one exercised here.

## Automated gates

- Release-script tests passed, including single-platform feed staging, exact serving, malformed
  paths, rejected signatures, pre-creation validation, and the production-workflow endpoint guard.
- 19 frontend tests and 17 Rust tests passed.
- Rust clippy passed with warnings denied.
- `mvn verify` passed across the full reactor, with only the existing environment-dependent skips.

Windows and Linux still need their installed update, rollback, OS-warning, and real-game checks.
The hosted three-platform signed candidate also remains a separate release gate.
