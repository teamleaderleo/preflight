# Preflight — LLM handoff

Use this file as a pointer, not as a frozen project-state snapshot.

Before changing code **or running release evidence**, do this in order:

1. Read [CLAUDE.md](CLAUDE.md) for the shared Codex/Claude working rules.
2. Read the live [release-convergence board #652](https://github.com/teamleaderleo/preflight/issues/652), then query current `main` and the open PRs in the area you are about to touch. Treat SHAs and status copied into dated evidence, old comments, or old handoffs as historical snapshots unless you just re-read them from GitHub.
3. Read [Release readiness](docs/release-readiness.md) for the maintained public-release blocker list.
4. If the Desktop visual blocker is still open, read [#976](https://github.com/teamleaderleo/preflight/issues/976) and its active carrier [#977](https://github.com/teamleaderleo/preflight/pull/977). The remaining acceptance work is a **computer/browser task**: render the verified frontend bytes in Chromium/Playwright at `1040×700` and `720×560`, inspect the required Home/Hangar states, and exercise the hover/focus and keyboard/pointer disclosures called out there. Re-fetch #977 immediately before capture and record the exact PR head plus the artifact/run identity actually reviewed. Unit tests and source inspection do not substitute for this pass.
5. Once #976 is accepted and #652 says the candidate can freeze, move to the live candidate-execution owner [#965](https://github.com/teamleaderleo/preflight/issues/965) and benchmark owner [#418](https://github.com/teamleaderleo/preflight/issues/418). This is also **computer/operator work on immutable packaged bytes**, not a checkout inference. Read [scripts/README.md](scripts/README.md) before driving the game. For the release startup claim, use `scripts/benchmark-startup.sh --campaign --engine ...` against the packaged candidate and keep the candidate/source/bundle or workflow identity beside the result. Never reuse a SHA or benchmark number copied from this handoff, and never let checkout fallback stand in for packaged candidate bytes.
6. Use [the engineering handoff/chronology](docs/next-llm-handoff.md) and dated [docs/evidence/](docs/evidence/) only when you need implementation history or prior evidence. Older priority language can be complete or superseded; refresh the live owners above before acting on it.

Any source change after candidate generation creates new candidate bytes and invalidates package-dependent evidence that was collected against the previous generation. Re-read #652/#965 before each machine run rather than assuming the previous operator session is still the accepted candidate.

The prepared-pixel contract-check material remains useful historical/specialized evidence, but it is no longer the default entry point for current release work. Start from the live release board and the current carrier instead.

Do not maintain a second copy of live blocker status here. The point of this file is to route every coding agent to fresh ownership and exact bytes before it starts work.
