# Config the game silently never reads

*2026-07-28*

A mod's behaviour lives in JSON-shaped files — `.variant`, `.wpn`, `.ship`, `.faction`, `.proj`,
`.system`, `.skin`, `.skill`, `.json`. The reviewed profile ships **15,353 of them across 91 mod
directories**, and until now nothing in this repository read a single one for syntax.

That is a gap worth closing, because these files fail in a way art does not. A texture that is the
wrong size still draws. A config file with one brace too many *parses cleanly and does less than it
says*, with no error, no log line, and nothing for the author to notice.

## What the dialect actually accepts

The first version of the check was a strict JSON reader. It reported 27 files as broken, in mods as
widely used as MagicLib, Nexerelin and Arma Armatura. All 27 ship and work.

Every one of those was the checker being wrong, not the mod. The dialect accepts, and shipping mods
rely on:

| Shape | Where it was observed |
| --- | --- |
| `#` and `//` comments | throughout |
| trailing commas | throughout |
| unquoted keys — `tips:[...]` | `tips.json` in 26 mods |
| numeric suffixes — `"duration":0.1f` | `expsp_beat_msl.proj` and others |
| a stray `}` after the final brace | 27 files |

The last row is the one that mattered. A reader consumes one top-level value and never looks at what
follows, so a trailing bracket is invisible to the game. Reporting it would have described normal
practice back at the authors of the most-installed mods in the ecosystem — the failure this linter
can least afford, and the same one that forced the rewording of `texture-npot-padding` two days ago.

So the checker reads structure only — brackets, strings, comments — and stays incurious about types.
Trailing punctuation is ignored.

## What survives

Two rules, both `error`, both costing nothing in bytes:

- **`config-unparseable`** — a bracket, string or comment that never closes, or a file that does not
  open an object or array at all. No reader can finish these.
- **`config-unread-content`** — real configuration *after* the top-level value has closed. Not a
  stray bracket: a key.

Across 15,353 files the profile yields **five findings**, every one checked by hand.

| Mod | File | What is wrong |
| --- | --- | --- |
| exshippack | `data/weapons/proj/expsp_beat_msl.proj` | a `PROXIMITY_FUSE` block sits past the closing brace from line 44 — **the missile has no proximity fuse in game** |
| eusan_nation | `data/weapons/eusan_nation_kayak_r.wpn` | `"fireSoundTwo"` stranded at line 105; the second fire sound never plays |
| ORK | `data/world/factions/pirates.faction` | a duplicated `},` on line 50 closes the faction early; `"priorityWeapons"` and everything after it is never read |
| Mayasuran Navy | `data/config/exerelinFactionConfig/mayasuran_guard.json` | the file begins `0{` — a stray digit before the opening brace |
| MagicLib | `data/config/sample_modSettings.json` | begins with `}` |

The last one is a fair hit on the letter and a probable miss on the spirit: the file is a fragment
meant to be pasted into a user's `modSettings.json`, not loaded. It is left in rather than special-
cased, because a `sample_` filename heuristic would be this tool guessing at intent, which its own
rules forbid. One finding in 15,353 files is an acceptable price for not guessing.

The other four are real defects in released mods that no player or author could have seen without
reading the file byte by byte.

## What is asserted and what is not

**Asserted, from the file alone:** these five files contain a second, disconnected chunk, or no
complete value at all. That is a property of the bytes and needs nothing from the game.

**Not asserted:** exactly what the game does with the remainder. The evidence that it reads one value
and stops is behavioural — 27 files with trailing brackets ship inside enormously popular mods and
nobody has noticed — not a reading of the loader, which is obfuscated and not redistributable. Either
the game ignores the remainder or it refuses the file. Both are worth telling the author, and the
rule text does not claim to know which.

## Effect on the linter

The profile went from **1,387 findings to 1,392**. Rounding, that is nothing. But before this change
the entire 86-mod profile produced **zero** `error`-severity findings, and it now produces six — five
of them here. Every other rule says an asset costs more than it needs to. These two say something is
broken.

Run time went from ~6 s to ~8.8 s for the whole profile, which is the cost of reading 15,353 small
files.

## Verification

`ConfigSyntaxTest` pins each accepted shape against the mod that ships it, and each rejected shape
against the file that motivated it. Seven mutations of `ConfigSyntax` — never reporting trailing
content, treating stray brackets as content, dropping `#` comments, not skipping string contents,
never reporting an unclosed bracket, accepting a mismatched bracket, never reporting an unterminated
string — were all caught by the suite.

Depth is handled with an explicit stack rather than recursion. These files come from strangers, and a
corrupt one with 200,000 open brackets should not end someone's lint run with a StackOverflowError;
that case is a test.
