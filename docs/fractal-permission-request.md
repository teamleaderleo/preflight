# Fractal Softworks correspondence

**Status:** sent 2026-08-07; awaiting response

This records the request sent before public distribution. Any reply and conditions remain in the
private release record unless Fractal Softworks authorizes their publication.

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

## After receiving a reply

- Record the date, scope, descriptive-name guidance, required disclaimer or attribution, and any
  technical or distribution conditions in the private release record.
- Convert every condition into a blocking item in [release-readiness.md](release-readiness.md).
- Don’t summarize silence, ambiguity, or a narrow answer as broader permission.
