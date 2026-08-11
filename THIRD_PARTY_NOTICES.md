# Third-party notices

## IBM Plex Sans

The desktop interface bundles IBM Plex Sans through `@fontsource-variable/ibm-plex-sans`. IBM Plex
Sans is copyright IBM Corp. and is licensed under the SIL Open Font License 1.1. The complete
license is packaged at `licenses/IBM-Plex-Sans-OFL.txt` and retained in the source tree as
[IBM-Plex-Sans-OFL.txt](preflight-desktop/src-tauri/licenses/IBM-Plex-Sans-OFL.txt).

## B612 Mono

The desktop interface bundles B612 Mono through `@fontsource/b612-mono`. B612 is copyright The
B612 Project Authors and is licensed under the SIL Open Font License 1.1. The complete license is
packaged at `licenses/B612-OFL.txt` and retained in the source tree as
[B612-OFL.txt](preflight-desktop/src-tauri/licenses/B612-OFL.txt).

## Orbitron

The desktop interface bundles the Orbitron variable font through
`@fontsource-variable/orbitron`. Orbitron is copyright 2018 The Orbitron Project Authors and is
licensed under the SIL Open Font License 1.1. The complete license is packaged at
`licenses/Orbitron-OFL.txt` and retained in the source tree as
[Orbitron-OFL.txt](preflight-desktop/src-tauri/licenses/Orbitron-OFL.txt).

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
