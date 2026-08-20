import json
from pathlib import Path

base = json.loads(Path("/tmp/cli-665-baseline.json").read_text())
after = json.loads(Path("/tmp/cli-665-after.json").read_text())

if after["narrowHelp"]["maxLineLength"] > 44:
    raise SystemExit(f"narrow help exceeded 44 columns: {after['narrowHelp']}")
if after["narrowHelp"]["desktopDiscovered"]:
    raise SystemExit("desktop appeared in ordinary narrow help")
if after["narrowHelp"]["containsAnsi"]:
    raise SystemExit("redirected narrow help contained ANSI")
if not after["previewProfileUnchanged"]:
    raise SystemExit("preview-only profile update changed profile bytes")

for label, report in (("current-main", base), ("reconstructed-head", after)):
    for name, result in report["results"].items():
        if result["exitCode"] != 0:
            raise SystemExit(f"{label} {name} exit changed: {result['exitCode']}")
        if result["containsAnsi"]:
            raise SystemExit(f"{label} redirected {name} contained ANSI")
        if not result["deterministicStdout"] or not result["deterministicStderr"]:
            raise SystemExit(f"{label} {name} output varied across repetitions")

rows = [
    ("`--help`", "help"),
    ("`doctor`", "doctor"),
    ("`profile list`", "profile-list"),
    ("`cache`", "cache-summary-human"),
    ("`cache --json`", "cache-summary-json"),
    ("`profile update solo` preview", "profile-update-preview"),
]


def table(report):
    lines = [
        "| path | p50 ms | p95 ms | exit | stdout bytes |",
        "|---|---:|---:|---:|---:|",
    ]
    for title, key in rows:
        item = report["results"][key]
        lines.append(
            f"| {title} | {item['p50Ms']:.3f} | {item['p95Ms']:.3f} | "
            f"{item['exitCode']} | {item['stdoutBytes']} |")
    return "\n".join(lines)


evidence = f'''# Current-main CLI response measurements — 2026-08-20

**Current-main source:** `feca35828c33d06e36e00cbf24b6bcce3c56191d`  
**Runner:** `{base['platform']}`; `{base['java']}`; {base['processors']} logical processors reported  
**Fixture:** one enabled synthetic mod, {base['fixture']['resourceFiles']} small resource files, one saved named profile  
**Discovery:** standard `$user.home/Games/Starsector` location with no `--game` on measured commands

This is a CLI-response benchmark, not a Starsector startup benchmark. Each path receives one untimed
warmup followed by nine separate JVM invocations. Wall time includes JVM startup and command work.
Stdout and stderr are captured, which deliberately exercises redirected-output semantics.

## Current main before this slice

{table(base)}

At `COLUMNS=44`, current-main top-level help reached {base['narrowHelp']['maxLineLength']} columns and
ordinary discovery included the internal desktop bridge: `{str(base['narrowHelp']['desktopDiscovered']).lower()}`.

## Reconstructed head

{table(after)}

At `COLUMNS=44`, reconstructed top-level help reaches at most
{after['narrowHelp']['maxLineLength']} columns and keeps the internal desktop bridge out of ordinary
discovery.

## Contract checks

- Every measured command returned exit code 0 before and after the slice.
- Every timed stdout/stderr stream was byte-stable across its nine repetitions.
- `cache --json` parsed as one JSON document on every repetition and contained no ANSI bytes.
- Captured/redirected human output contained no ANSI bytes even with `TERM=xterm-256color` and no
  `NO_COLOR` override.
- The saved profile's SHA-256 remained identical across all preview-only `profile update solo` timing
  samples.
- Focused tests separately cover interactive styling, presence-based `NO_COLOR`, case-insensitive
  `TERM=dumb`, future-command help fallback, and 44-column wrapping.

## Performance decision

No command-path work is removed in this slice. The hosted synthetic fixture is useful for response-time
regression detection, while it is too small to justify changing the default doctor profile census or any
consumer-visible scan. A real modded installation remains the authority for deciding whether that census
should move behind an explicit option later.
'''
Path("docs/evidence/2026-08-20-cli-response-current-main.md").write_text(evidence, encoding="utf-8")
