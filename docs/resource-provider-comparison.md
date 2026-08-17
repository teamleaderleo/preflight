# Resource provider comparison

Issue #561 gives multi-provider resource paths a content-aware meaning without changing how the
resource index chooses a provider.

## Authorities

Two facts come from two different authorities:

- **winner/order:** `ResourceIndex` provider order. Core is first, enabled mods follow their resolved
  order, and the last provider is selected.
- **content identity:** SHA-256 of the contained provider file bytes. A filename, provider ID, byte
  length, modification time, or display label never establishes equivalence.

`ResourceProviderComparison` keeps those inputs separate. It accepts ordered providers from the
index and an explicit `ContentIdentitySource`. The comparison result contains no physical provider
paths and does not publish content digests.

The CLI's persisted-index observer (`ResourceProviderContentIdentity.direct`) checks real-path
containment plus indexed size/mtime freshness before hashing. A missing, unreadable, escaped/invalid,
or stale provider makes that logical path ambiguous. This prevents an old `.spfi` from confidently
classifying bytes that belong to a newer filesystem snapshot.

Launch-time code can instead use `ResourceProviderContentIdentity.cached(ProfileIdentityContext)`.
That source reuses the same resolved-provider and SHA-256 memo already used by exact profile cache
identities. A digest already paid for during that preparation is read from the memo; a fresh
preparation hashes current bytes again.

## Categories

Every logical path with at least two indexed providers lands in exactly one category:

- `identical-duplicate`: every provider has an exact SHA-256 identity and all identities match;
- `differing-override`: every provider has an exact SHA-256 identity and at least one differs;
- `ambiguous`: one or more providers could not be compared safely.

Ambiguous provider evidence stays explicit as `missing`, `unreadable`, `invalid-path`, or `stale`.
The selected winner is still reported from index order because comparison uncertainty does not alter
the deterministic provider chain.

The aggregate identity is therefore always:

`multiProviderLogicalPaths = identicalDuplicates + differingOverrides + ambiguousComparisons`

## Bounded public view

`Result.toPublicMap()` exposes exact aggregate counts and bounded samples per category. Each sampled
provider chain carries only provider ID, provider/root order, core/winner flags, and evidence state.
Long chains retain the selected winner even when middle providers are truncated. Samples are sorted
by the resource index's canonical logical-path order.

This map is suitable for launcher-facing consumers such as #212: it contains logical resource paths
and provider IDs, with no installation roots, home-directory paths, provider relative paths, or
content digests. Callers may apply a tighter downstream cap while preserving the exact counts.

`preflight index inspect <index.spfi>` includes this map under `providerOverlaps`, along with the
measured comparison duration and the two authority labels. Inspection hashes only providers on
multi-provider logical paths; single-provider resources are never read for #561 classification.

## Downstream use

- #212 should consume the exact counts and public-safe bounded samples instead of inventing a
  separate conflict model.
- #252 can use the same ordered-provider result when explaining which resource a cross-reference
  resolves to.
- #563 can aggregate identical and differing overlap counts per provider while retaining the same
  winner semantics.
- recovery/readiness work should keep `ambiguous` explicit and fail closed when content evidence is
  unavailable.
