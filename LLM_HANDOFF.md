# Preflight — LLM handoff

The single living handoff document is [docs/next-llm-handoff.md](docs/next-llm-handoff.md). Read that, the open issues, and the latest reports under [docs/evidence/](docs/evidence/) before changing code.

For the current prepared-pixel operator gate, use the copy-paste-safe [contract-check command](docs/prepared-pixel-contract-check-now.md). It forces a clean rebuild, verifies the checker is packaged, validates the real Starsector path, and preserves the checker exit status through `tee`.

Do not maintain project state in this file. Dated point-in-time snapshots belong under `docs/evidence/` (for example [the 2026-07-16 snapshot](docs/evidence/2026-07-16-llm-handoff-snapshot.md)); keeping a second live copy here caused the two documents to drift apart and disagree about the merged baseline.
