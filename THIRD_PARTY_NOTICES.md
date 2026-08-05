# Third-party notices

## GraphicsLib compact auto-generation replay

`preflight-agent` contains an adapted Java 17 classfile derived from GraphicsLib 1.12.1
`org.dark.shaders.util.TextureData`, copyright Harrison Snodgrass (Dark.Revenant), 2014.
GraphicsLib is licensed under [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/).

The adapted class memoizes immutable JSON reads during texture-map traversal, captures unresolved
normal-map generation requests during the first traversal, and replays that compact request set
instead of walking every specification a second time. Preflight additionally represents captured
requests using existing JVM types so the replacement remains a single-class, mod-classloader-safe
artifact. The source changes are based on GraphicsLib commits `7434a75` and `abb1ffa`.

## Aircompressor

`preflight-core` uses Airlift Aircompressor's pure-Java LZ4 implementation for the optional
lossless balanced prepared-texture format. Aircompressor is copyright The Airlift Project and is
licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). Source:
[airlift/aircompressor](https://github.com/airlift/aircompressor).
