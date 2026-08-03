# Merged-read tagged-tree fidelity on the installed game JSON runtime

The production `GameJson` bridge and `JsonTree` codec were replayed offline before any game launch.
This was necessary because the agent tests compile against deliberately small `org.json` stubs: they
prove reflection and bytecode plumbing, but cannot prove behavior against Starsector's 2010-era
`json.jar`.

## Corpus and runtime

The corpus was all 12,584 entries from the four live prepared spec caches: variants, weapons, hulls,
and projectiles. Those are merged objects from the current 83-mod profile and contain 990,602 values
when walked recursively.

The harness was compiled with the system JDK using `javac --release 17`, then run on:

```text
/Applications/Starsector.app/Contents/Home/bin/java
OpenJDK 17.0.10, x86_64
```

The `JSONObject` protection domain confirmed that the runtime class came from:

```text
/Applications/Starsector.app/Contents/Resources/Java/json.jar
sha256 63c3541f323f3dfdd595da9257a2099b6a6c39f35a6b3909d86c48a8aa456911
```

The exact corpus artifact hashes were:

```text
variant    16b4034daf54cd35d53a1a411baf7b61dbc11ae3a97d07f443a4577ce7c5fb58
weapon     24f7f95bb65e70f5b8b199f8bb7c1ca4e201d85d179ec158e9b97e3cbabc01d7
hull       e9d49b8cfda7921294ed402b1b897ebd4780b2b552aea563a29cae7986928907
projectile 49c971ec36a8e9d240f525d315eb372696459a972150f5887862db213c7d23cc
```

## Gate

For every entry the harness performed the exact production path:

```text
new JSONObject(stored text)
GameJson.encode
JsonTree.encode
JsonTree.decode through GameJson's direct-to-org.json sink
recursive comparison against the original
```

Comparison required identical object keys, array lengths, null presence, scalar Java classes and
scalar values. Double raw bits were compared as well, so `-0.0` cannot silently become `0.0`. Object
iteration order was ignored because the installed `JSONObject` is backed by a `HashMap` and order is
not semantic.

Source: `docs/evidence/2026-08-03-merged-read-json-fidelity.java`.

Result:

```text
PASS: 12,584 entries, 990,602 values, 9.0 MB tagged trees
JVM: 17.0.10 (x86_64); json.jar JSONObject: file:/Applications/Starsector.app/Contents/Resources/Java/json.jar
```

This establishes fidelity for the real JSON types already present in the large merged-spec corpus.
Unknown future value types still fail closed in `GameJson`: the runtime counts the read as
`unstorableReads`, reports the type, returns vanilla's object, and does not publish that entry.
