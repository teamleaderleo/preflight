# Patreon page draft

Working copy for <https://www.patreon.com/cw/teamleaderleo>. This is preparation copy rather than a
permanent tier contract; revise it whenever the page, the projects, or my taste changes. Preflight
stays free and open source.

Use [Public writing style](public-writing-style.md) for voice and current release documents for facts.

## Page line

Preflight and other projects that got out of hand.

## About

I make stuff that tends to become much larger than I expect, and right now most of my public work is
going into Preflight, a free and open-source performance launcher for Starsector that began because
my heavily modded installation could take roughly 101 seconds to reach the main menu and I wanted to
know where the time was actually going.

The latest controlled comparison on that 83-mod setup measured an **89.00-second ordinary-launch
median** and a **15.53-second Preflight median**, with one launch reaching **15.25 seconds**. That
would have been enough project for a reasonable person, except the investigation kept finding
adjacent things I wanted from a giant Starsector installation, so Preflight now has its own
before-and-after benchmark, local playtime tracking, named mod profiles, the useful game settings
beside Launch, storage planning and recovery, read-only setup analysis, privacy-conscious support,
signed updates, a Hangar that can draw installed ships as local wireframes, and a mod linter whose
calibration result includes 44 completely clean mods out of 86 inspected.

The repository keeps the embarrassing parts too: experiments that failed, measurements that turned
out to be wrong, and regression tests that exist because those failures were instructive enough to
deserve permanent consequences.

Membership helps support development time, testing hardware, hosting, release work, compatibility
work after Starsector and mod updates, and whatever I disappear into next. Preflight access does not
depend on membership; the application, source, features, and public support stay available to
everyone.

## Membership tiers

The tiers are contribution levels. Pick whichever amount feels right; they all support the same
public work.

### Supporter

**Price:** $5 per month

Help support Preflight and the other things I make: development, testing, releases, hosting,
hardware, and the unglamorous maintenance that keeps a project alive after the exciting part is
finished.

### Backer

**Price:** $10 per month

A little more toward ongoing development, compatibility work, testing, and future projects; a good
fit if you use Preflight regularly or enjoy following an investigation that keeps mutating into more
software.

**Highlighted tier:** yes.

### Sustainer

**Price:** $20 per month

Help cover a meaningful share of cross-platform testing, release preparation, hardware, hosting, and
continued development.

### Sponsor

**Price:** $50 per month

For people or organizations who want to make a substantial contribution to Preflight and my other
work, especially the expensive parts that are hard to make glamorous: test machines, release
exercise, hosting, and the time required to keep compatibility work moving.

## Welcome note

Thank you. This helps support the time and costs behind Preflight and whatever I end up working on
next.

Preflight lives at <https://github.com/teamleaderleo/preflight>. If something breaks, use **Copy
setup** or the support tools in the app, or open an issue; membership is never required for support.

## First current public post

### Preflight got out of hand

It has been a while since I posted here, which is partly because I have the recurring problem where
I start pulling on one thread and discover, several months later, that I have accidentally made an
application around it.

The thread this time was Starsector startup. I have 83 mods enabled on the development installation,
and earlier in the project the game could take roughly 101 seconds to reach the main menu. I wanted
to know what it was doing during all of that time, especially how much of the work was stable enough
that doing it again on every launch was mostly ceremony, and the answer was: enough to send me much
farther down this problem than I expected.

In the latest controlled comparison on the same 83-mod profile, five ordinary launches had an
**89.00-second median** and five Preflight launches had a **15.53-second median**; the lowest recorded
Preflight launch in that comparison was **15.25 seconds**. Results vary with the machine and mod
stack, so the desktop now contains the same normal-versus-Preflight benchmark and can produce a
compact **Copy result** summary for the installation in front of you.

Once I had a launcher I actually wanted to use every day, the radius kept expanding. It tracks
Starsector playtime locally even if the desktop minimizes or exits after launch; it has named mod
profiles with preview and backup before switching; it puts resolution, fullscreen, sound,
antialiasing, UI scale, RAM, and battle size beside Launch, including 600, 1000, 1500, and 2000
point presets on a standard installation; and it calculates the current profile's preparation cost
before writing, with a minimal-disk route, repair, normal-speed fallback, and previewed cleanup when
the big cache is inconvenient or unusable.

Then there are the tools I wanted once the mod list itself became a source of mystery. `scan`
inventories the enabled profile; `analyze setup` can find things like a required dependency that is
installed but disabled, duplicate mod IDs, broken metadata, or a winning variant that refers to a
hull absent from the resolved profile; and `lint` can inspect one mod or a complete profile for
measurable asset and configuration costs without grading the author or rewriting the files.

The linter has become one of my favorite side stories because the calibration was pleasantly
anti-sensational. I ran it over 86 installed mod directories individually and the median result was
zero findings, with **44 of 86 completely clean**. Progressive JPEGs were one of the clearest
exceptions, decoding about **8.75 times slower** through the game's ImageIO path than equivalent
baseline images; there were also some large texture/audio costs, shadowed resources, editor source
files, extension mismatches, and a small number of released configuration files containing data the
game never actually reads. A useful linter should be willing to look at somebody's mod and say,
"yep, fine," which turns out to be a surprisingly good design constraint.

The Hangar is the more decorative example of the same tendency. Preflight can read the installed
hull catalog, remember the ship you picked for its display, and for featured ships trace the
installed sprite locally into a new wireframe rather than packaging Starsector's artwork. There are
five app palettes, per-hull wireframe tuning, optional movement, and a Compact launch-first Home for
the days when all of this is too much launcher and you simply want the large button.

Support and updates acquired their own rabbit holes because those are the places where a polished
screenshot stops helping. Ordinary launches upload no logs or telemetry. **Copy setup** collects a
small public-support summary while leaving out private paths, credentials, saves, and arbitrary logs;
a deeper support ZIP is a separate reviewed action, and automatic failed-run reporting starts off.
Supported desktop packages use a signed updater, the release process exercises install, update,
rollback, and removal, and native packages carry a capability receipt for anyone who wants to inspect
what the package can write, launch, link to, or contact over the network.

The performance work itself also produced a healthy pile of counterexamples. The first texture cache
had great-looking hit counters and broken visuals. A supposed timing split came from a stale
benchmark anchor. Java Flight Recorder's clock was wrong by about 2.49 times under one runtime
setting. A GraphicsLib replay made its target path slower. AppCDS failed to earn its complexity and
came back out. Those failures stay in the repository because the interesting part of optimization
work is frequently discovering that the number you were pleased with was answering a different
question.

I am working toward the first public beta now. The product is largely there, and the live gate has
four candidate/platform jobs left: real-game Windows and Linux exercise, the complete hosted
three-platform candidate, the startup benchmark on the packaged candidate, and the final packaged
support-intake cancel/retry/delete canary. The Fractal Softworks request is courtesy correspondence
and sits outside that publication gate; the continuing generation-authority and other hardening
research is post-RC unless a candidate failure promotes something.

Preflight will remain free and open source. This Patreon is here for anyone who wants to support the
time, hardware, hosting, release work, and ongoing compatibility work behind it, or who simply enjoys
watching me disappear into a problem and come back with far too much software.

Project: <https://github.com/teamleaderleo/preflight>

## Follow-up posts worth writing

These should remain separate stories; the project is large enough that one giant announcement makes
everything less interesting.

### How 101 seconds became 15.25

Walk the accepted performance chronology: texture prefetch, the 0-percent `SpecStore` plateau, mod
callbacks, generated bytecode, audio, and the smaller serial tail that only became visible after the
large costs disappeared. Keep the failed experiments because they are some of the best parts.

### I pointed the profiler at 86 mods

Tell the linter story and lead with the calibration result that 44 of 86 were completely clean, then
explain the progressive-JPEG result and the narrower cases where a finding really did point to a
measurable cost or deterministic configuration problem.

### Yes, the battle-size button goes to 2000

A lighter product post about the settings beside Launch, high-DPI resolution handling, RAM editing,
and why the extended control still writes Starsector's own preference.

### How Preflight draws Starsector ships without shipping Starsector art

A design/dev post about installed hull discovery, local sprite tracing, display selection, per-hull
wireframe tuning, and the decision to keep source artwork out of the package.

### When the profiler lies to you

The stale benchmark anchor, wrong JFR clock, broken texture-cache pilots, and the larger lesson that a
convincing local metric can still be describing the wrong thing.

### Why a launcher rehearses rollback

Signed updates, package lifecycle, cache evolution, and capability receipts explained through the
ordinary question underneath them: what happens to a working installation when the next release
arrives?

## Images

Use the creator avatar or Preflight mark for the profile image according to whichever fits the page
at the time; this is a creator page rather than a permanent Preflight-only identity. While Preflight
is the main public project, the wireframe ship on the drafting grid makes a good header, with the ship
kept to the right so Patreon can crop the left side safely. Keep essential text out of either image
because the crop changes across devices.
