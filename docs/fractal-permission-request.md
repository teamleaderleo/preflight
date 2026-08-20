# Fractal Softworks correspondence

**Status:** sent 2026-08-07; courtesy correspondence; response still welcome

The maintainer publication decision recorded on 2026-08-20 in
[#950](https://github.com/teamleaderleo/preflight/issues/950) treats this request as a courtesy
notice. Waiting for a response is outside the beta/publication gate. Current release blockers and
any later priority change are owned by
[#652](https://github.com/teamleaderleo/preflight/issues/652).

This page preserves the message that was sent before public distribution. Any reply and conditions
remain in the private release record unless Fractal Softworks authorizes their publication.

## Subject

Question about distributing Preflight, a performance launcher for Starsector

## Message

Hi Alex,

I’m preparing a public beta of **Preflight**, a free and open-source performance launcher for
Starsector:

https://github.com/teamleaderleo/preflight

Preflight prepares content-addressed caches and launches the user’s existing Starsector installation
with runtime Java bytecode instrumentation. It doesn’t distribute Starsector code or assets, and it
doesn’t modify game or mod JARs, installation files, or saves.

Each transformation is restricted to exact class and archive fingerprints. If an update changes the
relevant code, that transformation is skipped and the original implementation runs.

Since Preflight is an external launcher rather than a conventional mod, I wanted to ask whether you
have any concerns about its public distribution or compatibility approach.

Several measured improvements may also be useful directly in Starsector, including indexed lookups,
memoized identities, and eliminating repeated construction. I could share the measurements or
prepare focused patches if useful.

I could also provide more technical detail or adjust the implementation based on your feedback.

Thanks,

Leo

## If a reply arrives

- Record the date, scope, descriptive-name guidance, requested disclaimer or attribution, and any
  technical or distribution request in the private release record.
- Compare the reply with the maintainer publication decision in #950 and the current release state in
  #652.
- Let the maintainer decide whether a concrete new constraint changes release priority; do not expand
  the blocker list automatically from correspondence.
- Keep silence, ambiguity, and narrowly scoped answers described at their actual scope.
