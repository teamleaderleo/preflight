# Preflight LLM handoff

This file is a route to current state, not a copy of it.

Before changing code or collecting release evidence:

1. Read [CLAUDE.md](CLAUDE.md).
2. Fetch `main`, list the open PRs in the area, and read the live
   [release board #652](https://github.com/teamleaderleo/preflight/issues/652).
3. Read [Release readiness](docs/release-readiness.md).
4. Follow the current owner. [#965](https://github.com/teamleaderleo/preflight/issues/965)
   owns candidate exercises, [#818](https://github.com/teamleaderleo/preflight/issues/818) owns
   final package identity, [#418](https://github.com/teamleaderleo/preflight/issues/418) owns the
   packaged startup benchmark, and [#1023](https://github.com/teamleaderleo/preflight/issues/1023)
   holds product observations until one becomes a concrete defect.
5. Read [scripts/README.md](scripts/README.md) before driving the game. Use
   `scripts/benchmark-startup.sh` for one automatic startup measurement, `--details` to explain a
   changed result, and `--campaign` only for a real comparison.
6. For current campaign/combat smoothness work, read the maintained
   [frame-pacing investigation notebook](docs/frame-pacing-investigation.md). It separates durable
   observations from falsifiable hypotheses and records why a previously explored area may still
   be worth revisiting. [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)
   is the canonical parent for that lane; use focused child issues once one experiment is concrete.
   The latest diagnostic checkpoint is the bounded
   [hitch packet v1 record](docs/evidence/2026-08-28-hitch-packet-v1.md): its first complete packet
   narrowed one paused 50 ms miss to pre-swap work, with limiter/cap separation as the next slice.

Private signing rehearsals prove that the release machinery works. They are not final release
evidence. Final operator evidence must use the same selected tag, source, Distribution, and package
generation throughout. A source change creates new bytes and invalidates affected package evidence.

Use [the engineering chronology](docs/next-llm-handoff.md) and dated
[evidence](docs/evidence/) when implementation history is relevant. Never take a SHA, owner, or
priority from historical notes without checking the live repository first.
