# Public writing style

This is the voice guide for public Preflight writing: README prose, release announcements, Patreon posts, forum posts, interviews, release notes where a little personality belongs, and any future copy that is supposed to sound like Leo talking about the thing he made.

The factual source of truth still lives elsewhere. Current code, tests, release readiness, the product contract, known limitations, and retained evidence outrank this page whenever a claim changes. This page is about how to say the true thing once we know what the true thing is.

## The voice

Confident, conversational, technically literate, a little obsessive, occasionally amused by the amount of project that grew out of one loading screen. The confidence should come from command of the details and willingness to show the interesting ones, including awkward failures and oddly specific numbers, instead of from marketing intensifiers.

The prose can take a long breath. Leo's natural bravado is discursive more often than staccato: a sentence can keep going because the thought keeps going, pick up a parenthetical or a slightly baroque clause on the way, and arrive somewhere more interesting than it would have if every idea had been cut into a six-word declaration. Preserve that when it reads cleanly.

Transitions are part of the voice. Let one observation cause the next one. Let a paragraph remember what the previous paragraph was doing. A good transition can carry argument, chronology, irritation, delight, or a digression; it does more work than a generic bridge sentence and it usually reads better than a stack of miniature mic drops.

## Rhythm

Use sentence-length variation, with a bias toward medium and long sentences when the thought deserves them. Short sentences are useful when one genuinely wants to land. Their scarcity gives them force.

Avoid turning prose into a sequence of immaculate little beats. A paragraph where every sentence could be a social-media card starts sounding like somebody else's brand voice, and Preflight has enough peculiar detail that it never needs that treatment.

Run-ons are allowed when they are legible. Rambling is allowed when the ramble is carrying useful texture, chronology, qualification, comedy, or personality. Edit for loss of the thread, not merely for length.

## Lists

Use lists when the reader genuinely benefits from scanning a set of separate things: download links, supported platforms, exact limitations, commands, measured results, or a long feature inventory whose members really are peers.

Do not impose an arbitrary rule of three. Three items are fine when there happen to be three items. Four, five, seven, or one long sentence can be better. Avoid manufacturing symmetry by deleting an interesting fourth thing or padding a pair with a weak third thing.

When several features are causally or narratively related, prose is often better than bullets. The project itself has a story: performance work exposed adjacent problems, those problems produced tools, and the tools accumulated into a companion app. Let the writing retain that accretion instead of flattening everything into equal-weight cards.

## Vocabulary

Use ordinary language by default and keep technical vocabulary when it is the most exact or interesting word available. An occasional esoteric word is welcome. So is an unexpectedly specific one. There is no prize for sanding every sentence down to the vocabulary of product onboarding copy.

The criterion is whether the word earns its place. A strange word that names the thing precisely or gives the sentence a little electricity is good; jargon imported to make a simple idea sound consequential is dead weight.

Likewise, keep the wonderfully specific project facts. **83 mods**, **89.00 seconds**, **15.53 seconds**, **2,000 deployment points**, **44 of 86 completely clean**, a clock running **2.49×** away from wall time: these details make the writing feel inhabited because they came from the actual work.

## Bravado

The useful bravado is expansive and matter-of-fact: I went very far down this rabbit hole, here is what I found, here is the machine that came out of it, and here are the receipts if you want them.

Avoid startup-founder theatre, LinkedIn triumphalism, fake humility, canned awe, and slogans that sound engineered to become screenshots. Avoid treating every paragraph ending as a punchline. A genuinely funny or swaggering line can stay; surrounding it with ordinary prose makes it hit harder.

The project can sound pleased with itself. The 89.00s → 15.53s result is excellent. The battle-size control really does go to 2,000. The linter really did find that 44 of 86 reviewed mods were completely clean. Say those things with pleasure. Then keep moving.

## Technical proof

Outcome first. Give the reader enough mechanism to understand why the claim is plausible, then put the full proof behind a link unless the venue is explicitly technical.

Public prose should translate internal review vocabulary into player language where that loses no important meaning. Terms such as `exact`, `bounded`, `authority`, `fail-closed`, identity gates, receipts, and ownership models belong where they clarify something concrete; repetition makes the copy sound as though it is arguing a case at the reader.

Concrete behavior beats generic reassurance. Explain what Preflight changes, what it leaves alone, what happens after an update, what a support ZIP contains, or how a failed preparation recovers. Readers can infer the quality from the behavior.

## Transitions and digressions

Prefer transitions with a point of view. "Once textures became cheap, the visible 0% pause became interesting" carries the investigation forward. "And because I apparently cannot leave a project at one job..." can work in a personal post because it connects the performance story to the absurd amount of app that followed.

A digression earns its keep when it reveals something about the project or the person making it. The failed texture cache, the JFR clock, the linter discovering that most mods were fine, and the locally traced Hangar ships all do this. They are better texture than generic declarations that the project was built with care.

## Things to watch for in edits

- Too many consecutive short declarative sentences.
- A paragraph that has been converted into bullets merely because bullets scan well.
- Three-item lists that feel suspiciously composed when the underlying material has a different natural count.
- Repeated sentence openings and repeated feature templates.
- Every section ending on a miniature slogan.
- Technical nouns clustered before the player has been told why they care.
- Sanitizing away an odd, vivid, or esoteric word that was doing useful work.
- Sanding first-person prose into an institutional "we" voice.
- Turning genuine enthusiasm into hype language.
- Turning careful qualification into defensive legalese.

## Venue differences

The README should have momentum and enough eccentricity to make somebody keep reading, while remaining useful as documentation. The forum/Reddit announcement can ramble a little more because it is a person telling the story. Patreon can be more personal still. Release notes should stay calmer and more navigable, though they can retain a few good transitions and specific turns of phrase. Installation and known-limitations copy should optimize for comprehension first.

The common thread is that none of these should sound as though a copywriting framework wrote them.