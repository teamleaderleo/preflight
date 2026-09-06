# Appearance and callback lifecycle polish

Implementation: 5d7c68a6. Follow-up to #1262.

## Changes

- Theme/palette reads and writes tolerate unavailable WebView storage; choices still apply in the mounted session.
- Home presentation, ship motion, profile search, speed history, remembered installation, and cleanup now resolve the storage getter inside their existing error handling. A getter throwing before function entry previously bypassed those fallbacks.
- Ship focus recovery owns its additional animation frame, coalesces repeated focus events, and cancels frames/timers when hidden or disposed. Queued callbacks cannot restart a disposed display. A still display mounted hidden paints when revealed.
- Theme transition cleanup removes its temporary body class.
- Three native timeout test fixtures use `exec sleep 600`, retaining the sleep as the directly owned child instead of leaving a descendant behind.

## Automated evidence

Node 24.19.0: all 503 frontend tests in 86 files and production build passed (local command receipt `eea5ea19f06f82c9`). Focused callback tests passed (`a1618bd693618a7e`); storage and adjacent tests passed (`d5e40afdd6924ff9`). Native tests and packages belong to Desktop CI on the PR.

Excluded/failed local attempts are retained in command receipts: an initial test invocation accidentally used system Node 26 (`b1e66acf838e9fee`), which is outside the repository Node range and failed jsdom storage setup. A shell PATH quoting attempt failed (`09e5e9ae0409cd7f`). The first full suite caught the newly added hidden/still-display regression (`1e3ee3b89b201cc8`); the final full suite passed after the resume guard correction. A test-writing helper initially used the wrong working directory and wrote no files.

## Browser evidence

An isolated agent-browser Chromium session exercised the actual Vite frontend at 1040×700 and 720×560. Home and the minimum-size Options panel rendered intact. At 720×560, no horizontal document overflow or error overlay was detected. After deliberately replacing the localStorage getter with a throwing getter, dark theme and Phosphor selection applied and no page errors were reported. Pointer movement revealed the existing idle HUD; Options then opened normally. Initial semantic click attempts while the HUD was idle were refused by browser hit-testing, not treated as successful interaction.

Retained local screenshots under `benchmark-results/`:

- `appearance-1040.png`
- `appearance-720.png`
- `appearance-options-720.png`

The browser session and owned Vite server on port 1427 were closed. No game or VM was launched. These checks are browser preview and automated evidence; they do not replace native package acceptance or prove WKWebView focus behavior on the installed app.

## Release scope

The source fixes complete #1262. Focus-refresh coalescing (#1261), short native mutation locks (#1260), recovered Windows Stop classification (#1263), and native candidate acceptance remain separate tracked work. Optional Fast Rendering port compatibility remains opt-in with #1269 open. A frozen package generation is still required for release-bound evidence.
