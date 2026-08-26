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

Selected repeated product facts live in [docs/project-facts.json](docs/project-facts.json). When the
maintainer changes one of those facts, edit that file and run:

```bash
python3 scripts/sync_project_facts.py --write
```

Do not hand-update the scattered public copies. The sync command owns that propagation and the claim
headline page. Historical measurements stay in their evidence records and technical chronology.
Campaign statistics answer comparison/attribution questions; they do not choose the current public
headline.

Private signing rehearsals prove that the release machinery works. They are not final release
evidence. Final operator evidence must use the same selected tag, source, Distribution, and package
generation throughout. A source change creates new bytes and invalidates affected package evidence.

Use [the engineering chronology](docs/next-llm-handoff.md) and dated
[evidence](docs/evidence/) when implementation history is relevant. Never take a SHA, owner, or
priority from historical notes without checking the live repository first.
