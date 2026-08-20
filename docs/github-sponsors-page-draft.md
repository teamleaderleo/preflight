# GitHub Sponsors page draft

Working copy for the `teamleaderleo` GitHub Sponsors profile. Keep it current as the page changes;
this is creator-page copy, not a permanent tier contract.

Use [Public writing style](public-writing-style.md) for voice and the live release documents for
current claims.

## Short bio

I make Preflight, a free and open-source performance launcher for Starsector, and other software and
creative projects that have a habit of getting out of hand.

## Introduction

I make stuff that tends to become much larger than I expect. Preflight is the current example: it
started because my 83-mod Starsector installation could take roughly 101 seconds to reach the main
menu, and the latest controlled comparison on that same setup measured an **89.00-second ordinary
launch median** and a **15.53-second Preflight median**, with one launch reaching **15.25 seconds**.

Following that time around turned into much more than a cache. Preflight now has the same
before-and-after benchmark in the desktop, local Starsector playtime, named mod profiles, the useful
game settings beside Launch, battle-size presets through 2,000 deployment points on a standard
setup, storage planning and recovery, read-only setup analysis, privacy-conscious support, signed
updates, a CLI over the same engine, a locally generated wireframe Hangar, and a mod linter.

A lot of the work becomes visible only when something changes or fails, which is exactly why I spend
time on it. Runtime optimizations stay inside the launched game process and step aside when the game
or mod code they depend on changes. The updater is signed, rollback is exercised, incompatible cache
formats can coexist across versions, and native packages carry a machine-readable capability receipt
for people who want to inspect the package's commands, writes, child processes, links, and network
endpoints.

The repository keeps the unflattering history too. There are optimization ideas that made things
slower, measurements that turned out to be wrong, a texture-cache experiment with beautiful hit
counters and broken visuals, and the tests that exist because each of those failures taught the
project something worth retaining. I prefer a narrower claim with the evidence it actually earned to
a sweeping claim produced by relabelling green CI.

Sponsorship helps pay for development time, testing hardware, hosting, release work, compatibility
work after Starsector and mod updates, and future projects. Preflight remains free and open source,
and sponsors get the same application everyone else gets; the tiers are different ways to support
the work rather than different editions of it.

## Featured work

- `teamleaderleo/preflight`

## Featured sponsors

Use GitHub's automatic featured-sponsor selection for now. A manual list can come later if there is a
reason to curate it.

## Goal

**Target:** 10 sponsors

Ten sponsors would be a lovely little milestone and would help cover testing hardware, hosting,
release work, and the time that goes into maintaining Preflight after the first public beta as well
as getting it there in the first place.

## Monthly tiers

### $5 a month

**Supporter.** Help support Preflight and my other open-source work: development, testing, releases,
hosting, hardware, and maintenance.

### $10 a month

**Backer.** A little more toward ongoing development, compatibility work, testing, and whatever the
next project turns into; a good fit if you use Preflight regularly or enjoy watching an
investigation acquire extra limbs.

### $20 a month

**Sustainer.** Help cover a meaningful share of cross-platform testing, release preparation,
hardware, hosting, compatibility work, and continued development.

### $50 a month

**Sponsor.** For people, modders, developers, or organizations who want to make a substantial
contribution to Preflight and my other work, especially the expensive and persistent parts such as
testing hardware, hosting, release exercise, and ongoing maintenance.

## One-time tier

### $10 one time

**One-time supporter.** If Preflight helped you out, saved you a pile of waiting, or you simply like
this kind of obsessive open-source work, thank you. One-time support goes toward the same testing,
hardware, hosting, development, and release costs as monthly sponsorships.

## When the Sponsors profile is live

- Add `github: teamleaderleo` beside the existing Patreon entry in `.github/FUNDING.yml`.
- Replace `[GITHUB SPONSORS URL]` in the beta announcement drafts with the live Sponsors URL.
- Add the live Sponsors link to the README support section.
- Keep Patreon available as the alternate membership/payment route.
