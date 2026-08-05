# The validated texture index is now the launch snapshot

## Result

The prepared-texture runtime already validates the complete current resource index before enabling
its adapter. On the reviewed profile that is 61,695 providers. It nevertheless resolved and
`readAttributes`-checked the winning source again for every prepared texture served, only seconds
after the complete validation pass.

`--fast` now treats that validated index as an immutable snapshot for the duration of startup. An
adjacent live diagnostic served the same 15,469 textures and 2,116,422,119 pixel bytes, with the same
three known entry misses and zero internal error, corruption, quarantine, or dimension fallback.
Time inside the exact texture-load seam fell from **4,962ms to 4,559ms (-403ms)**. The complete
launches measured 22.77s and 22.35s respectively, but the exact seam measurement is the causal
claim; one whole-launch pair is supporting evidence only.

The final diagnostic is:

`~/.starsector-preflight/runs/trusted-texture-index-diagnostic-20260806-034935`

## Correctness boundary

Nothing about cache identity or initial validation changes. A stale, missing, retargeted, resized,
or timestamp-changed provider disables the prepared cache before any class rewrite. The only newly
accepted case is a source file changed after that validation completes while the game is already
starting. Such a change takes effect on the next launch instead of halfway through the current
launch, matching the snapshot semantics of build systems and the fact that live mod replacement is
not a supported game operation.

`--recheck-texture-sources` after `--fast` restores the per-lookup filesystem checks. The existing
`-Dpreflight.texture.verifySourceHash=true` diagnostic also overrides snapshot mode, so requesting
the strongest content check cannot silently be weakened by a preset.
