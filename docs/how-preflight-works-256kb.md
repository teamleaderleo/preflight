# How Preflight works if your brain has 256 KB

You have game.

Game is slow to start because game does same expensive work every time.

Preflight tries to do that work once and reuse the answer.

That is most of the project.

## The whole thing

```text
First time:

mods + game files
      ↓
Preflight does expensive work
      ↓
saves reusable answers

Later:

click Launch
      ↓
Preflight checks: same game? same mods? same files?
      ↓
    yes ─────→ use saved answers → game starts much quicker
      │
      no
      ↓
let the game do the normal thing
```

That last bit is important.

If Preflight is unsure, it gets out of the way.

## What is all the code for?

Very small map:

```text
React = what you see
Rust  = talks to your operating system
Java  = understands Starsector and mods
Agent = temporary helper inside the running game
Cache = saved expensive answers
```

### React

Buttons. Screens. Settings. Progress. Pretty ship.

React does not get unlimited access to your computer.

### Rust

Rust is the small trusted middleman.

It can start the Java engine, open native dialogs, manage the app update, and do a few other approved operating-system things.

### Java

Java does most of the Starsector-specific work.

It finds the game, reads the mod setup, prepares reusable data, launches the game, manages profiles, records playtime, runs checks, and creates diagnostics.

### Java agent

When Preflight launches Starsector, a small helper goes inside that one game process.

It can say:

> Oh, the game is about to do that expensive thing again. I already have the checked answer.

The helper disappears when the game exits.

Preflight does not permanently rewrite the game or mod JARs to make this happen.

## What gets prepared?

Stuff that is expensive and repeatable.

Examples:

- textures;
- merged game/mod data;
- generated Java code;
- audio;
- indexes and other repeated answers.

If the inputs change, the old answer stops matching.

New mod version? Different answer.

Changed file? Different answer.

Broken cache entry? Throw it away and use the normal game path.

## Why does launch become so much quicker?

Because this:

```text
load file
parse file
decode image
compile code
scan giant list
calculate same answer
```

turns into this when it is safe:

```text
check identity
use answer we already have
```

Do that in enough expensive places and a nearly two-minute launch can become a roughly fourteen-second launch on the measured development setup.

## Does Preflight edit my game?

Usually, no.

Most writes go into Preflight's own folders.

Two explicit features can change game-owned settings:

- activating a saved mod profile;
- changing supported launch settings.

Those are deliberate user actions with preview/backup/recheck behavior.

Saves, mod contents, game JARs, and executables are left alone.

## What if a mod is weird?

Preflight checks whether each shortcut recognizes what it expects.

```text
recognize it → shortcut may run
confused      → shortcut steps aside
```

The normal game code remains available.

This is how Preflight can be aggressive about performance without requiring every mod on Earth to behave identically.

## What is the linter?

A read-only snitch.

It looks at mods and says things like:

> this asset is huge

> this encoding is expensive

> this dependency/config looks broken

It reports the problem. It does not secretly rewrite the mod.

## What is all the CI stuff?

Tests asking:

> Did we break something we already fixed?

Normal Java behavior goes through normal `mvn verify`.

Separate workflows are kept mainly when the operating system, native package, release signature, exact release bytes, or a deliberate stress test changes what is being tested.

## What happens when we release it?

Roughly:

```text
source code
   ↓
tests
   ↓
make Windows/macOS/Linux packages
   ↓
check exact package contents
   ↓
exercise install/update/remove behavior
   ↓
sign the update path
   ↓
publish the exact checked files
```

Release checks are more paranoid than normal development checks because those are the files real people receive.

## The entire philosophy in one sentence

**Do expensive work once when possible, prove when the saved answer is still valid, use it while that proof holds, and let the original game do its thing whenever the proof fails.**

If that sentence makes sense, you understand the core of Preflight.

If you have more than 256 KB available now, continue with [the normal walkthrough](how-preflight-works.md).
