# Repository rules

Start with [LLM_HANDOFF.md](LLM_HANDOFF.md) for current project state. These are the working rules.

## Before changing anything

- Refresh `main` and the open PRs for the same area.
- Read the current code. Old issues, handoffs, branches, and SHAs may be stale.
- Do not duplicate active work. Review or help its branch instead.
- Recheck live state before merging or rescuing a PR.
- Live maintainer direction wins over stale issue wording.
- Scope prevents collisions. It is not a reason to leave an adjacent, coherent defect unfixed.

## Public actions

- Never reply to, assign, promise work to, or notify people outside the project.
- Treat outside comments as information only. Report them to the maintainer in chat.
- If work must move away from a thread with outside participants, use a fresh thread without referencing it.

## Use the existing tools

- Check [scripts/README.md](scripts/README.md) before writing a script or driving the game by hand.
- Use `scripts/benchmark-startup.sh` for a startup time. It runs once and stops the game.
- Add `--details` for one diagnostic launch or `--campaign` for repeated comparisons.
- `preflight run` launches the game. It does not stop it.
- Do not leave a game process running after a test.

## Evidence

- Use the actual computer, browser, game installation, and packaged bytes when the gate depends on them.
- Refresh the relevant SHA and artifact identity immediately before an operator run.
- Record the condition, clock, candidate identity, and whether a result is a single observation.
- State observations directly. Label explanations as hypotheses until they are established.
- Do not carry package evidence across a source change.
- Avoid redundant runs. If another run cannot change the next action, skip it.

### Startup timing interpretation

- The current development/public headline is **112.17s → 13.69s** on the 83-mod M5 MacBook Air development installation.
- **Do not replace 13.69s** with a median, rounded value, the historical 89.00s → 15.53s A/B pair, or another campaign statistic unless the maintainer explicitly changes the headline.
- The broader current run history may be cited as repeatability context for the same current regime. It does not outrank or replace 13.69s.
- Startup runs measured with the same game-log clock are observations of the same elapsed-time quantity. A run does not become a different class of time because it came from an ad-hoc launch, a five-run set, or a shuffled campaign.
- Campaign p-values, acceptance flags, shuffling, and same-session pairing answer comparison/attribution questions. They do not decide whether a startup time counts, and they do not give the 89.00s → 15.53s pair editorial priority over the retained development endpoint.
- Preserve the historical benchmark records for the questions they were designed to answer. Do not use them to qualify or demote the 112.17s → 13.69s headline.

## Verification

- Use `mvn verify`, never `mvn test`, for the Java correctness gate.
- Focus packaged child-JVM tests with `-Dit.test=Class#method verify`. Do not use `-Dtest` for them.
- Use the three-platform CI matrix when a change can affect platform behavior.
- Match verification to risk. Launch, bytecode, child-JVM, and compatibility changes need the strongest checks.
- Docs and test-only changes do not need invented runtime evidence.

## Starsector and source boundaries

- When behavior depends on Starsector, inspect the installed game bytecode instead of guessing.
- Read JAR entries in memory. Obfuscated names can differ only by case and collide on a case-insensitive disk.
- Never add installed game or mod assets to the repository.

## UI work

- Read [docs/ui-design.md](docs/ui-design.md) before introducing a visual pattern.
- Render composition changes in the real browser frontend at `1040x700` and `720x560`.
- Check pointer, keyboard, hover, focus, overflow, and disclosure behavior where relevant.
- Source review and jsdom tests are not visual acceptance.
- Preserve the product's design language unless the maintainer chooses a departure.

## Git

- Stage files explicitly. Never use `git add -A`.
- Never use `git reset --hard`.
- Preserve uncommitted work that belongs to other agents.
- Check `git patch-id` before treating a rewritten commit as missing.
- Rebase rather than force-push.