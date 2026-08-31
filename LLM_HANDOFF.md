# Preflight LLM handoff

This file routes to live state; it carries no task, owner, SHA, benchmark-value, or release snapshot.

1. Read [CLAUDE.md](CLAUDE.md).
2. Fetch `main` and relevant open PRs, then read the assigned issue and all current comments. Follow live issue ownership and maintainer direction.
3. For release work, fetch the live [release board #652](https://github.com/teamleaderleo/preflight/issues/652), then read [Release readiness](docs/release-readiness.md). Follow the board's current links for operator and evidence owners.
4. For performance or evidence work, [project facts](docs/project-facts.json) owns selected current values, [Startup benchmark](docs/startup-benchmark.md) owns measurement semantics, and [scripts/README.md](scripts/README.md) owns commands.
5. For implementation history, use [the engineering chronology](docs/next-llm-handoff.md) and dated [evidence](docs/evidence/). Verify anything current-looking against the live repository before acting.
