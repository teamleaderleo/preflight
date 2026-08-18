# Engineering case-study notes

**Status:** working notes, 2026-08-18  
**Purpose:** retain the engineering stories that are easy to lose inside issues, reviews, evidence files, and release work. This is source material for a later portfolio/case-study page, interviews, README compression, release retrospectives, and project handoffs. It is **not** a public performance claim and should not be read as a substitute for the exact release-candidate evidence.

Preflight has accumulated enough technical depth that the difficult part is no longer finding impressive work. The difficult part is compressing it into a few stories that another engineer can understand quickly and then drill into.

The portfolio-level thesis is roughly:

> Preflight started as an attempt to reduce heavily modded Starsector startup time. It became a cross-platform performance launcher whose optimizations, caches, mutations, process lifecycle, measurements, removal behavior, and UI are all forced to answer the same question: **what can the program actually prove, and what should it do when that proof is unavailable?**

That thesis is stronger than a list of technologies. The useful evidence is in the decisions, failed assumptions, adversarial cases, and the way the product changed after those were found.

## System snapshot

The desktop product is deliberately split across three layers:

- React/Vite renderer for the player-facing application;
- Tauri/Rust host for native process/filesystem/lifecycle boundaries;
- a bundled Java engine and Java agent for Starsector inspection, preparation, launch, evidence, and runtime optimization.

The repository also contains controlled benchmark/probe tooling, synthetic workloads, package/release verification, report-intake code, and a substantial deterministic regression suite.

This breadth is useful only when the stories stay concrete. The sections below are the strongest candidates so far.

---

# 1. The launch was waiting for two audio threads

**Good for:** performance engineering, profiling, concurrency judgment, exact-input caching, knowing when *not* to parallelize.  
**Primary retained evidence:** `docs/evidence/2026-08-03-the-launch-was-waiting-for-two-threads.md`.

## The tempting answer was wrong

After earlier startup work, a heavily modded launch would not fall below roughly forty seconds. The phase probe showed time going somewhere inside loading, but a phase boundary alone could not tell whether the main thread was doing useful work or waiting for something else.

The game log supplied the missing dimension: every line had a timestamp and thread name. Looking at gaps between main-thread log lines exposed a 3.46-second interval in which two pool threads emitted hundreds of `Loading sound [...]` lines while the main thread emitted nothing. Static inspection then found a fixed two-thread executor followed by `awaitTermination`.

The obvious performance patch would have been “make the pool wider.” That was rejected.

The decode path eventually wrote a plain `HashMap` and called OpenAL on shared state. The game had validated that behavior with two workers. Increasing the worker count would have mixed a concurrency/correctness change into a performance change without an ownership model for the shared state.

That is a useful engineering story because the optimization came from changing **where the work happened**, not from turning a knob.

## The safe seam

The actual Vorbis decode was separable from the unsafe shared-state work. The decoder returned channel count, PCM bytes, and sample rate before the OpenAL/shared-map portion.

Preflight therefore prepared exact decoded audio ahead of launch and served it only when the encoded input bytes and decoder identity matched. On the investigated corpus:

- 2,099 files;
- 140.7 MB encoded input;
- 30.5 thread-seconds of decode work;
- 1.23 GB prepared PCM;
- 2,099 equivalence checks, zero mismatches in the retained replay.

A same-day probed launch moved from **40.13 s to 35.93 s**. That single run is evidence for the mechanism, not a universal benchmark claim; later controlled startup work owns the larger product claim.

## Why this story is useful

It demonstrates several habits at once:

1. measure wall time rather than guessing from code size;
2. use a second evidence source when the first probe cannot distinguish busy from waiting;
3. reject unsafe parallelism even when it looks like the shortest path to a win;
4. move deterministic expensive work out of the critical path instead;
5. key prepared output to the exact bytes and producer/decoder identity that justify reuse;
6. prove equivalence against the real installed classes rather than an approximate reimplementation.

A good interview follow-up is: **“Why did you cache decoded output instead of increasing the executor size?”** The answer forces the discussion into concurrency, exactness, fallback behavior, and evidence rather than microbenchmark trivia.

---

# 2. `stat` before and after hashing did not prove which file was hashed

**Good for:** filesystem semantics, TOCTOU/ABA races, cache correctness, adversarial testing, proof lifecycle.  
**Issues/PR:** #608, PR #650.  
**Status:** still converging as of this note; update after merge.

## The original proof looked reasonable

Direct provider evidence originally followed a familiar pattern:

1. inspect the provider path;
2. hash the path;
3. inspect the path again;
4. publish the digest only if the before/after metadata and file identity matched.

That catches many mutations. It does **not** close an ABA replacement window.

An adversary or external writer can temporarily replace pathname A with same-size/same-timestamp B while the hashing callback reads the name, then restore A before the post-read inspection. The endpoints both describe A while the accepted digest can belong to B.

The important realization is that the proof was about the **pathname at two moments**, while the claim was about the **bytes consumed during the interval**.

## Binding the read to a stable file object

PR #650 moved the uncached read through a hard-link proof alias. A temporary alias names the same file object even if another file is renamed over the original pathname during hashing. The original pathname and proof have to agree before the read, the digest is read through the proof, and the pathname must rejoin the same identity afterward before exact evidence becomes publishable.

That closes the original A -> B -> A case, but the review history is more interesting than the first fix.

### Review found the first implementation put the proof on the wrong filesystem

An early version created the proof name in the process default temporary directory. Hard links cannot cross filesystems/volumes. A very ordinary Windows layout with `%TEMP%` on C: and Starsector on D: therefore turned stable direct observations into conservative `STALE` results.

The proof namespace moved beside the provider so the link lives on the provider filesystem/FileStore.

### Review then found publication could outrun proof cleanup

A digest that became reusable before successful proof cleanup created a mismatch between “observation succeeded” and “proof lifecycle actually completed.” The implementation was changed so cleanup finishes before either the digest result or memo entry becomes visible. A cleanup failure therefore cannot seed a future exact memo hit.

### Review then found the cleanup test was not testing a real partial cleanup failure

The injected test originally performed real cleanup successfully and threw afterward. That proved the memo rule, but it did not model an OS/filesystem refusing deletion while the proof artifact still existed.

Because the proof namespace lives beside the provider, a leaked `.preflight-provider-proof-*` artifact can itself become visible to a recursive resource scan. That creates a deeper failure mode: Preflight's own proof object can influence the next profile/resource identity.

As of this note, that partial-cleanup/indexing case is the remaining #650 convergence blocker.

## Why this story is useful

This is a very good example of a correctness proof getting stronger under review:

- before/after metadata was insufficient;
- a stable alias closed the read-binding hole;
- filesystem topology invalidated the first alias-placement assumption;
- cleanup ordering mattered to memo publication;
- cleanup residue mattered to a *different subsystem* that recursively observes the same directory.

The lesson is broader than “know how hard links work.” A safety mechanism has a lifecycle and a footprint. It can be locally correct and still violate the global model if its temporary state is visible to another observer.

A good interview question is: **“Why doesn't `stat` -> read -> `stat` prove the hash belongs to the file?”** A second is: **“What did review find after you had already closed that race?”**

---

# 3. “Atomic write” was not the same thing as “create this profile only if the name is still free”

**Good for:** transactional filesystem semantics, reviewed mutations, optimistic concurrency, commit points, external writers.  
**Issue/PR:** #582, PR #649.  
**Status:** implementation is green/frozen pending lower-level convergence as of this note; update after merge.

Profile duplication sounds trivial: read profile A, copy it to a new name, do not activate it.

The interesting engineering work appeared around what “copy” and “reviewed” actually meant when files can change between preview and apply.

## Stale reviewed source

The first preview/apply contract checked the source's stored profile fingerprint. But `enabledMods` was parsed separately; an external writer could modify the JSON's mod list while leaving the stored fingerprint unchanged. Confirmation could therefore create a duplicate containing data the player had not reviewed.

The fix had to bind confirmation to the actual reviewed source record/relevant fields and re-read before publication. The important principle is that a convenient stored identity is not automatically a valid optimistic-concurrency token if the store does not prove that identity was recomputed from the current contents.

## Destination TOCTOU

The implementation also checked `Files.exists(target)` and later performed an atomic write whose final move used replacement semantics.

Those two properties sound safe separately:

- collisions are checked;
- publication is atomic.

Together they still allow this race:

1. Preflight sees the target name is free;
2. an external writer creates that profile;
3. Preflight's later atomic replace overwrites it.

The requirement was stronger than “no partial file”: it was **publish this new record only if the name is still absent at the commit point**.

The final publication therefore needed create-if-absent/no-overwrite semantics while preserving complete-file publication.

## Committed outcome versus cleanup failure

A later review found another subtle semantic boundary. Once the create-if-absent publication succeeds, the mutation is committed. A failure while cleaning staging data afterward must not make the command report “duplication failed” as though the profile did not exist. That false failure can cause callers/users to retry an operation that already committed.

This forced a precise commit-point definition:

- before final publication, errors mean no new profile is authoritative;
- after final create-if-absent publication, the duplicate exists and the command must report the committed outcome truthfully;
- cleanup residue is a separate housekeeping concern.

## Why this story is useful

This is an excellent compact explanation of why filesystem mutation APIs need product semantics layered on top of them.

“Atomic” answers **can another reader observe a partial object?** It does not necessarily answer:

- was the reviewed source still the same source?
- did another writer claim the destination first?
- should a post-commit cleanup error roll back the reported outcome?

Those are separate questions and they have separate tests.

---

# 4. Destructive actions had to prove ownership, not recognize a filename

**Good for:** safety boundaries, uninstallers, symlink/junction handling, capability/ownership thinking, product trust.

Preflight eventually gained install/update/removal behavior. That changes the standard for filesystem code. A cache bug is annoying; an uninstaller deleting somebody else's file is unacceptable.

Several later hardening changes converged on the same doctrine:

- all-data removal refuses to traverse a Preflight home that is itself a symlink/alias (#631);
- launcher installation refuses symlink/junction targets and parents (#632);
- removing launcher integrations requires exact Preflight ownership evidence rather than a familiar path/name (#596/#640);
- install refuses unowned collisions instead of overwriting something that merely occupies the expected location;
- removal remains preview-first and distinguishes app/launcher removal from all Preflight-owned data.

The interesting product point is that “this path is where Preflight normally puts its launcher” is not ownership proof.

The tool records/recognizes exact markers and receipt/path binding, treats ambiguous state conservatively, and keeps Starsector, mods, saves, and game preferences outside the Preflight-owned deletion boundary.

This story is useful because it shows a shift from ordinary application code to **destructive authority**: the more consequential the operation, the more evidence the program needs before acting.

---

# 5. A benchmark number and a trophy number can both be valid, but they are different products

**Good for:** measurement semantics, product design around evidence, resisting misleading dashboards.  
**Journey issue:** #657.

Preflight has enough performance instrumentation that it is easy to create a UI full of authoritative-looking numbers. The danger is that a number can remain numerically correct while its **meaning silently changes**.

The Speed surface stores a controlled benchmark result with a strong benchmark identity. A player can later change profile/settings and still reasonably want to see the old result. Deleting the number would be unnecessarily joyless: a personal best is a legitimate trophy/history feature.

The mistake would be continuing to present that old result as evidence for the current setup.

The product distinction is now:

- **personal best/history** may remain indefinitely and describe the setup/date that produced it;
- **current comparable evidence** may make a claim about the current setup only when the relevant identity still applies;
- cumulative “matching launches” / time-saved claims need a comparability class strong enough for the aggregate they claim to represent.

A related bug showed why wording has to follow the data model rather than assume success. The engine permits negative improvement, but UI copy could render a regression as `0.8× faster` or `-25% better`. The correct product model needs first-class faster / effectively neutral / slower outcomes.

## Why this story is useful

It connects statistical/evidence discipline to ordinary interface design.

The lesson is not “hide stale measurements.” It is **label the epistemic role of the measurement correctly**. Historical delight and current evidence can coexist if the interface does not pretend they are the same claim.

This is particularly useful for product-engineering interviews because it is a case where the technically simpler UI—one giant number—would be the less truthful product.

---

# 6. Reviewing screens was insufficient; the bugs lived between states

**Good for:** frontend/product engineering, state machines, progressive disclosure, cross-page authority, recovery UX.  
**Issues:** #653 and journey audits #654-#658; active Home/Hangar work in PR #651.

A recurring frontend failure mode was that individual components looked defensible while the end-to-end journey was wrong.

The audit method therefore changed from “is this screen clean?” to tracing player errands across a state graph:

- what is the player trying to do;
- what must they know before acting;
- what does the primary action mean in this exact state;
- what state becomes authoritative after the action;
- what happens when the player leaves and returns;
- what changes after failure or external mutation.

That found several useful examples.

## Hidden dirty launch settings changed the meaning of Launch

The Options disclosure could contain a dirty launch-settings draft. Closing Options removed the settings and its feedback from the visible surface. Pressing Launch, however, automatically saved the dirty draft before launching.

So the visible Home could look settled while a hidden draft changed the semantics of the primary action.

The design issue is not “progressive disclosure is bad.” The rule is narrower: **if hidden state changes what the visible primary action will do, some projection of that state must remain visible or the interaction contract must change.**

## Launch identity disappeared precisely when preparation was required

A compact install/profile cue initially appeared only in the already-prepared ready state. When preparation became necessary, the primary action changed to `Prepare and launch` and the identity disappeared.

That is backwards from the decision consequence: preparing a particular setup is when knowing which setup the action refers to becomes more, not less, valuable.

Later #651 commits specifically carried launch identity through preparation/stale/retry states.

## Recovery content and spectacle competed for the same viewport

The hull-led Home is intentionally expressive, but failures and recovery actions must outrank idle spectacle. Journey review drove work that gives actionable Home errors/recovery priority and suppresses/de-emphasizes decorative ship-caption/playtime content in exceptional states.

## Launch-facing profile identity could be stale after external mod changes

A cached profile list could still identify a previously matching saved profile after `enabled_mods.json` changed externally. The UI could therefore confidently name a saved profile that no longer described what Launch would use.

Later #651 work moved toward conservative launch-profile resolution: when the app cannot justify a unique current saved-profile name, it should say `Current mod setup` rather than guess.

## Why this story is useful

It shows a product-engineering lesson that is difficult to demonstrate with a toy UI:

> local component correctness does not imply journey correctness.

The bugs emerged from interactions among state persistence, disclosure, external mutation, navigation, primary actions, and recovery priority. The fix was not “make everything visible” or “make everything consistent.” It was to model what the user must know at each transition and keep one underlying authority with task-relevant projections.

---

# 7. A background support feature accidentally became a global recovery blocker

**Good for:** lifecycle policy, composability, background work, product semantics versus implementation reuse.  
**Issue:** #662, originating contract #476.

Automatic failed-run reporting had an explicit product requirement: it must not block launching, preparation, cleanup, update, or shutdown.

The implementation reused the same `reportUploading` state used by a manual foreground upload. The renderer's global workflow policy then interpreted that shared state as a Help-owned blocking operation.

The result was internally consistent and product-wrong:

1. game fails;
2. automatic report begins;
3. upload enters the shared foreground workflow state;
4. the app-wide lock disables/re-routes unrelated work;
5. the support feature can delay the player's immediate recovery action after the failure.

This is another example where code reuse erased a semantic distinction. Manual `Review and send` is a foreground user-owned workflow. Automatic reporting is background best-effort work and must yield when the player wants to recover/play.

The useful lesson is that serialization at a native boundary and **product-level blocking** are different concepts. Native code may still refuse/cancel an unsafe overlap; the UI does not have to treat every internally serialized action as the owner of the whole application.

---

# 8. The performance result became credible because the project learned to separate experiments from claims

**Good for:** experimental design, release engineering, scientific restraint, reproducibility.

The development repository contains very strong startup numbers. For example, #418/#450 retain a controlled development campaign around an 83-mod setup with an 89.00-second vanilla median and roughly 15.5-second Preflight median.

Those numbers are intentionally **not yet the exact packaged release-candidate claim**.

#418 exists because checkout bytes, even when controlled carefully, are not identical to “the package we are about to give somebody.” The release benchmark has to run through the exact packaged engine, bind the receipt to that candidate/package identity, and remain unable to silently fall back to checkout-built bytes.

The same discipline appears elsewhere:

- ordinary launch history is not automatically a controlled benchmark;
- unlike profile/settings/runtime identities should not be pooled as “matching” launches;
- incomplete JFR sample coverage must not be converted into absolute wall-clock attribution (#254);
- one faster run is a result, not proof of an optimization (#450);
- hardware/profile scaling claims wait for actually different evidence (#295).

## Why this story is useful

Many side projects can produce a benchmark graph. Fewer can explain why they declined to publish the most flattering interpretation of that graph.

A strong case study should include both the headline result and a section called something like **“What we refused to claim.”** That makes the performance work substantially more credible.

---

# A possible portfolio/interview index

The eventual portfolio page should not force a reader to discover these stories through hundreds of issues. Compress first, then offer drill-down links.

| Story | Signals |
| --- | --- |
| Audio decode moved off launch | profiling, JVM/game internals, safe optimization seam, equivalence testing |
| Provider A -> B -> A proof | filesystem semantics, TOCTOU, adversarial testing, review discipline |
| Profile duplication publication | optimistic concurrency, transactional writes, commit semantics |
| Destructive ownership/removal | security/safety boundaries, symlink handling, uninstall design |
| Trophy vs current benchmark evidence | measurement semantics, product judgment, honest metrics |
| Journey/state UI bugs | React/product engineering, state modeling, progressive disclosure judgment |
| Automatic report blocking recovery | lifecycle/admission modeling, foreground/background semantics |
| Exact packaged-candidate benchmark | reproducibility, experimental design, release engineering |

Different applications should emphasize different subsets. Do not market every subsystem simultaneously.

## Performance / JVM / systems role

Lead with:

1. controlled startup result;
2. audio two-thread diagnosis;
3. exact prepared-data identity/fallback;
4. provider ABA proof;
5. JFR/sample-coverage restraint.

## Product / desktop engineering role

Lead with:

1. React -> Tauri/Rust -> Java boundary;
2. Home/launch journey state-machine findings;
3. native operation ownership/reconciliation;
4. profiles/settings reviewed mutations;
5. update/removal/support lifecycle.

## Reliability / correctness role

Lead with:

1. ABA provider evidence;
2. create-if-absent profile publication;
3. ownership-proven removal and symlink refusal;
4. durability/fault-injection program (#584/#330);
5. exact packaged/release evidence.

---

# Suggested final case-study shape

The final public case study should probably be much shorter than this file.

## 1. One-screen problem/result

- Heavily modded Starsector startup was painfully slow.
- Show the final controlled **packaged candidate** comparison once #418 is complete.
- Say exactly which machine/profile/build the result describes.
- Include a short demo/video rather than expecting a recruiter to build the repository.

## 2. Architecture in one diagram

Show:

`React/Vite desktop UI -> Tauri/Rust native host -> bundled Java engine/agent -> Starsector`

Then show prepared data/evidence stores beside the engine rather than making the diagram a class map.

## 3. Four deep dives

Best current candidates:

- the two-thread audio investigation;
- the A -> B -> A hash proof;
- transactional profile duplication;
- one product-state story from #653/#651.

Four is enough. Everything else can be “more engineering notes.”

## 4. What went wrong while building it

This should be a feature, not an embarrassment.

Examples:

- before/after pathname stats were an insufficient proof;
- the first hard-link proof assumed temp and game lived on one filesystem;
- a cleanup-failure test threw after successful cleanup and therefore did not model the actual failure;
- atomic replacement did not satisfy create-if-absent semantics;
- hidden settings changed Launch while visually collapsed;
- automatic background reporting accidentally inherited foreground blocking semantics.

These are far stronger signals than a retrospective that pretends every design was correct on the first try.

## 5. What we refused to do

Also worth retaining:

- did not increase the unsafe game audio executor just for a benchmark win;
- did not publish exact evidence when identity proof failed;
- did not silently overwrite a profile name claimed by an external writer;
- did not let removal treat path/name recognition as ownership;
- did not turn incomplete JFR sampling into wall-clock seconds;
- did not treat historical personal-best numbers as current evidence;
- did not publish checkout benchmark evidence as the packaged candidate claim.

## 6. Release/user evidence

After release, add:

- exact candidate digest/version;
- package/platform matrix actually exercised;
- real-player #458 observations;
- Windows/Linux evidence boundaries;
- final startup benchmark receipt;
- known limitations that remained deliberate.

---

# Interview prompts worth preparing

These are questions the repository can support with real answers.

1. **What was the highest-leverage optimization and how did you find it?**
2. **Tell me about an optimization you deliberately rejected.**
3. **What is an example of a race your first implementation missed?**
4. **Why was atomic rename insufficient for profile duplication?**
5. **How do you decide whether cached data is safe to reuse?**
6. **What does Preflight do when evidence is ambiguous?**
7. **What was the most surprising interaction between two otherwise-correct subsystems?**
8. **How did you keep benchmark claims honest?**
9. **How do you test destructive filesystem behavior?**
10. **What frontend bug could not be found by reviewing components in isolation?**
11. **Where does the React/native/Java responsibility boundary sit and why?**
12. **If you restarted the project, what would you design differently?**

For each, prepare a 60-90 second answer and one deeper technical branch if the interviewer wants it.

---

# Notes to update after the current convergence wave

This document intentionally describes some stories that are still moving.

After #650/#649/#651 settle:

- update their status from “in progress” to the exact merged commit/PR result;
- preserve important rejected review iterations rather than rewriting history as though the final design was obvious;
- add the final #650 partial-cleanup/indexing resolution;
- add the final #649 publication primitive and exact regression names;
- record which #651 journey findings survived integrated-main review and which were superseded;
- decide whether #662/#663 become additional case-study material or are ordinary follow-up defects.

After the release candidate:

- replace development startup numbers in the lead story with the exact #418 packaged-candidate result;
- add a small architecture diagram;
- retain 3-5 screenshots showing ready, preparation/recovery, Speed evidence, and one maintenance/support flow;
- record actual install/package/platform evidence and public-claim limitations;
- write a much shorter public case study from these notes rather than exposing this entire notebook as the first thing a hiring manager sees.

The goal of this file is to prevent the hard-earned reasoning from disappearing once the issues are closed and the final code looks inevitable.
