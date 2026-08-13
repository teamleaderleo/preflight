# Working notes for agents

Project state lives in [docs/next-llm-handoff.md](docs/next-llm-handoff.md) — see
[LLM_HANDOFF.md](LLM_HANDOFF.md). This file is about how to work, not what is true.

## Look in scripts/README.md before doing it by hand

Every script is indexed there with what it does and when you would reach for it. Check it before
driving the game, measuring a launch, or writing a new script — the thing you want usually exists.

Two that keep being missed: `scripts/probe-launch.sh` is one launch that stops itself and prints
where the time went, and `scripts/run-startup-benchmark.sh --unattended` is the repeated campaign.
`preflight run` is the launcher, not a measurement: it never exits on its own, and a launch left
running holds ~4 GB and a GPU context and poisons everything measured after it.

## One observation is not a finding

Say a fresh result in chat. Do not write it into a durable file until it has survived something —
a citation to existing evidence, a second run, or a check that it is even the same condition and
the same clock as the thing you are comparing it against.

A number you got a minute ago is a number, not an explanation of itself. Writing the explanation
down immediately is how a reference doc ends up carrying a guess that the next agent reads as
established, and how one paragraph gets rewritten three times in an afternoon while its author
works out what they actually saw. The measurement was real every time; the story attached to it
was not.

This applies hardest to `scripts/README.md`, `docs/evidence/`, and the handoff — the files that
exist so nobody has to re-derive anything.

## Verify once, at the right level, then move on

`mvn verify` locally is the correctness gate. CI exists because this is a Mac and the project
ships on Windows and Linux; dispatch the three-OS matrix when a change plausibly touches
platform behaviour, read the result, and continue.

Do not stack redundant evidence on a result that is already established. A green matrix is a
green matrix — re-running it to raise confidence, adding a second check that can only agree with
the first, or chasing a flake that has stopped reproducing all cost real time and tell you
nothing you did not already have. If a check would not change what you do next, skip it.

The same applies to explaining the work. State what was done and what it showed. Do not narrate
the reasoning that led there, pre-empt objections nobody raised, or attach confidence
qualifications to a plain result.

## Match the check to the risk

Worth real scrutiny: anything on the launch path, anything that changes what the child JVM
receives, anything that changes bytecode identity. These are shipped behaviour and a mistake is
invisible until a player hits it.

Not worth it: test-only changes, docs, and platform gates in tests. CI catches those, and it is
cheaper to let it than to prove them safe in advance.

## Check against the game, not against yourself

When a question is about what Starsector actually does, read the game's own bytecode under
`/Applications/Starsector.app/Contents/Resources/Java/`. A self-consistent argument about what
the game "must" do has been wrong here before. Read game jar entries in memory (Python
`zipfile`) rather than extracting — obfuscated names differ only by case and collapse on a
case-insensitive filesystem.

Nothing from the installation enters the repo.

## Git

Stage files explicitly. Never `git add -A`: uncommitted work from other agents lives in this
tree. Never `git reset --hard` for the same reason — it has already destroyed unrecoverable
files here.

`main` can look ahead when it is only stale; work gets re-landed under new SHAs. Check
`git patch-id` before concluding you are behind, and rebase rather than force-push.
