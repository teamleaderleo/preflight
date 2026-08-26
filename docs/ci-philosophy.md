# CI policy

Preflight keeps separate automation when the environment changes what is being tested.

Keep dedicated workflows for:

- operating-system filesystem/process behavior;
- native package build, install, update, rollback, and removal;
- release identity, signing, candidate provenance, and destructive boundaries;
- operator-owned or real-game exercises;
- deliberately large stress/probe workloads that are run on demand.

Ordinary Java regression tests belong to `mvn verify`. A focused JUnit test does not need its own
GitHub Actions workflow simply because it once came from a focused investigation. The scheduled
three-platform Java run supplies broad portability coverage; focused OS matrices should exist only
when the behavior itself depends on that OS.

Coverage is a report, not a percentage veto. Reproducibility, SBOM generation, and probe-kit
assembly run at release/scheduled/manual boundaries instead of taxing every pull request.

Repeated public benchmark facts live in `project-facts.json` and are propagated by
`scripts/sync_project_facts.py`. Evidence remains historical source material rather than generated
copy.
