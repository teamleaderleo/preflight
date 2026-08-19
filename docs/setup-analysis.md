# Setup analysis provider contract

#808 composes existing readiness/recovery authorities into one read-only result. This first slice defines the shared finding model only; it does not replace any provider.

A finding has:

- a stable reason code;
- the provider that owns the evidence;
- severity (`blocking`, `warning`, `info`, or `unknown`);
- a short player-facing summary;
- bounded structured parameters;
- bounded affected mod IDs;
- optional reviewed action IDs.

The result is deterministic, bounded to 256 findings, and can carry unavailable providers explicitly. A setup is `ready` only in the narrow sense that no provider has returned a blocking finding; unavailable/unknown evidence remains visible and is never silently promoted to a pass.

Provider ownership remains unchanged. In particular, #557 owns declared dependency/compatibility evidence and #252 owns static cross-reference validation. #590, #573, #578, #555/#575, #556, and #212 consume or contribute through their own contracts as they settle.

The next feasibility slice should route one cheap provider (#557) through this model before adding Desktop presentation or deep static-link analysis.
