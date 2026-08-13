# Concise asset progress logs

## Result

`--fast` now removes six exact per-file INFO message-building sites from Starsector 0.98a-RC8's
reviewed projectile, weapon, hull, and variant loaders. One live 83-mod launch avoided 12,584 log
events and 1,560,182 bytes of formatted log output while retaining warnings, errors, loader summary
messages, and the meaningful `Skipping variant [...]` diagnostic.

The unattended live gate reached the main menu in 22.77s and shut down normally. Compared with the
immediately preceding instrumented launch, the four affected loader calls fell by 106ms in total:

| Loader | Before | After | Difference |
|---|---:|---:|---:|
| Projectile definitions | 545ms | 501ms | -44ms |
| Weapon definitions | 481ms | 452ms | -29ms |
| Hull definitions | 420ms | 415ms | -5ms |
| Variants | 300ms | 272ms | -28ms |

This is a measured CPU/allocation/log-volume win, not a causal claim that whole-launch wall time
improved by exactly 106ms. The clean run directory is:

`~/.starsector-preflight/runs/concise-asset-logs-clean-20260806-034417`

## Safety boundary

The rewrite is opt-in (`--suppress-asset-progress-logs`, included by `--fast`) and bound underneath
the existing exact class, source-archive, and game-archive identities. Inside each class it requires
the complete reviewed instruction sequence, including the exact message prefix, StringBuilder
shape, path local, closing bracket, and `Logger.info(Object)` call. The weapon/projectile class is
atomic: if any one of its four sites changes, none are removed. Any future game drift therefore
keeps the original bytecode and launch continues normally.

`mvn verify` passed. Focused tests also prove that unrelated INFO messages and the variant-skip
diagnostic remain, and that a one-call shape drift declines the whole class.
