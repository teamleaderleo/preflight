# Windows live compiler-unit memo investigation

Objective: improve ordinary Recommended Windows startup while retaining source discovery,
class definition, existing resource policy and main-thread GL ownership. Owner: current Codex task.
Finish condition: measure a correctness-checked candidate, retain only justified behavior,
merge the completed slice into main and restore the ordinary Windows task.

The installed Janino 2.7.8 archive has SHA-256
`60f05562c22b6de06641a1f76148692ef336ad1f6712fe6a76f9e2611f766344` and JavaSourceClassLoader
has SHA-256 `6b0eea7994ab4c314f1bc7cdefaa99b66897d500c2cad6fd2d97cd08b134c4b8`.
Persistent complete-map replay remains disabled on Windows: Linux evidence records source-discovery
failures with that shortcut. No platform gate for persistent replay is changed.

Installed generateBytecodes first calls loadIClass, then iterates every known UnitCompiler,
compiles it, serializes every ClassFile, and repeats until the growing set is exhausted.
Its visited set is local to one call. Thus previously completed units are compiled again on the
next requested class. Candidate: memo successful compileUnit results within the live loader,
retaining the original discovery loop, complete output map and class-definition protocol.

Phase: validate bytecode and dependency fidelity before live measurements. Private installed
fixtures and bytecode are under Windows-Share/Diagnostics/windows-janino; never commit these assets.
