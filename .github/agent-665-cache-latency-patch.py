from pathlib import Path

path = Path('preflight-cli/src/main/java/dev/starsector/preflight/cli/CacheCommand.java')
text = path.read_text(encoding='utf-8')

old = '''        CurrentProfile currentProfile = currentProfile(game, launcher);
        String current = currentProfile.fingerprint();
'''
new = '''        boolean fullIdentity = requiresFullIdentity(inspect, health, repair);
        CurrentProfile currentProfile = fullIdentity
                ? currentProfile(game, launcher)
                : currentFingerprintOnly(game, launcher);
        String current = currentProfile.fingerprint();
'''
if old not in text:
    raise SystemExit('execute identity block drifted')
text = text.replace(old, new, 1)

old = '''    private static String currentFingerprint(Path game, Path launcher) {
        return currentProfile(game, launcher).fingerprint();
    }

    static CurrentProfile currentProfile(Path game, Path launcher) {
        return currentProfile(game, launcher, ResourceIndexBuilder.DEFAULT_SCAN_WORKERS);
    }

    /** Lets an interactive caller bound transient load without changing the resulting identity. */
    static CurrentProfile currentProfile(Path game, Path launcher, int scanWorkers) {
        try {
'''
new = '''    private static String currentFingerprint(Path game, Path launcher) {
        return currentFingerprintOnly(game, launcher).fingerprint();
    }

    static boolean requiresFullIdentity(boolean inspect, boolean health, boolean repair) {
        return inspect || health || repair;
    }

    static CurrentProfile currentFingerprintOnly(Path game, Path launcher) {
        return currentProfile(game, launcher, ResourceIndexBuilder.DEFAULT_SCAN_WORKERS, false);
    }

    static CurrentProfile currentProfile(Path game, Path launcher) {
        return currentProfile(game, launcher, ResourceIndexBuilder.DEFAULT_SCAN_WORKERS, true);
    }

    /** Lets an interactive caller bound transient load without changing the resulting identity. */
    static CurrentProfile currentProfile(Path game, Path launcher, int scanWorkers) {
        return currentProfile(game, launcher, scanWorkers, true);
    }

    private static CurrentProfile currentProfile(
            Path game, Path launcher, int scanWorkers, boolean includeAudioIdentity) {
        try {
'''
if old not in text:
    raise SystemExit('current profile wrapper block drifted')
text = text.replace(old, new, 1)

old = '''            String fingerprint = resourceIndex.index().profileFingerprint();
            try {
                List<Path> gameJars = PrepareAudioCommand.jars(target.installRoot());
'''
new = '''            String fingerprint = resourceIndex.index().profileFingerprint();
            if (!includeAudioIdentity) {
                return new CurrentProfile(fingerprint, null, null, null);
            }
            try {
                List<Path> gameJars = PrepareAudioCommand.jars(target.installRoot());
'''
if old not in text:
    raise SystemExit('audio identity block drifted')
text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
