# Working notes for agents

The canonical repository-working rules are in [CLAUDE.md](CLAUDE.md). Read and follow them before changing files, regardless of which coding agent or client is doing the work.

In particular, read **Treat scope as coordination, not a quality ceiling** before interpreting words such as `narrow`, `bounded`, `focused`, `lane`, or `owner`. Live maintainer product direction wins over stale scope wording; improve the chosen product direction instead of reverting it merely to make a diff smaller.

For current project state, start at [LLM_HANDOFF.md](LLM_HANDOFF.md). It points to the maintained release-readiness and maintenance checkpoint before the longer engineering chronology.

Keep the full working rules in `CLAUDE.md` rather than copying them here. This file exists so agents that discover `AGENTS.md` receive the same rules instead of growing a second version that can drift.
