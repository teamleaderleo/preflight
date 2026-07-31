package dev.starsector.preflight.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Resolves the system properties that make Starsector start the game without ever building its
 * launcher.
 *
 * <p>The game ships this itself. {@code StarfarerLauncher}'s constructor checks {@code launchDirect}
 * before it decides between the legacy Swing UI and the OpenGL one, and when the property is present
 * it reads {@code startRes}, {@code startFS} and {@code startSound}, calls the same static start
 * method the Play button would have called, and returns without constructing a UI at all. This is
 * not a hook Preflight invented or a UI it drives; it is a supported path we are switching on.
 *
 * <p><b>Why the values come from the game's own preferences.</b> A clicked launch reads the
 * resolution, fullscreen and sound state off the launcher's controls, and those controls are
 * initialised from {@code Preferences.userRoot().node("/com/fs/starfarer")} — the same store this
 * class reads. Hard-coding them would mean a benchmark that silently measures a different
 * configuration than the one the operator sees when they click, which is the failure this whole
 * harness exists to avoid.
 *
 * <p><b>Fail-closed.</b> Every way of being unsure resolves to unavailable, because each has a
 * failure mode that costs a full timeout rather than an error:
 *
 * <ul>
 *   <li>A missing or malformed resolution reaches {@code startRes.split("x")[1]} inside the game's
 *       own try block, and the handler there answers with {@code Sys.alert} — a modal native dialog
 *       with nothing to dismiss it.
 *   <li>An unregistered copy makes the game take the direct branch and then <em>return without
 *       launching</em>. The process exits having done nothing, which reads as a launch that never
 *       produced a log line.
 * </ul>
 */
record DirectLaunchSettings(String width, String height, boolean fullscreen, boolean sound) {
    /** The node the game's own launcher settings live under. */
    static final String PREFERENCES_NODE = "/com/fs/starfarer";

    private static final String RESOLUTION_KEY = "resolution";
    private static final String FULLSCREEN_KEY = "fullscreen";
    private static final String SOUND_KEY = "sound";
    private static final String SERIAL_KEY = "serial";

    /** Reads one launcher preference, or null when it has never been written. */
    @FunctionalInterface
    interface Lookup {
        String get(String key);
    }

    /**
     * Whether a direct launch can be configured, and the settings when it can.
     *
     * <p>{@code reason} is populated only when unavailable, and is meant to be shown: every reason
     * here is something an operator can act on.
     */
    record Availability(boolean available, String reason, DirectLaunchSettings settings) {
        static Availability unavailable(String reason) {
            return new Availability(false, reason, null);
        }

        static Availability of(DirectLaunchSettings settings) {
            return new Availability(true, null, Objects.requireNonNull(settings, "settings"));
        }
    }

    static Availability resolve(Lookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        if (blank(lookup.get(SERIAL_KEY))) {
            // The game checks registration before it honours launchDirect and simply returns when
            // the check fails. Reporting that as "no resolution" would send the operator looking in
            // the wrong place entirely.
            return Availability.unavailable(
                    "The game's launcher preferences hold no serial, and Starsector refuses a direct"
                            + " launch on an unregistered copy. Start the game once through its own"
                            + " launcher first.");
        }
        String resolution = lookup.get(RESOLUTION_KEY);
        if (blank(resolution)) {
            return Availability.unavailable(
                    "The game's launcher preferences hold no resolution yet, so a direct launch"
                            + " would not know what the clicked launch uses. Start the game once"
                            + " through its own launcher first.");
        }
        String[] axes = resolution.split("x");
        if (axes.length != 2 || !positiveInteger(axes[0]) || !positiveInteger(axes[1])) {
            return Availability.unavailable(
                    "The saved resolution \"" + resolution + "\" is not WIDTHxHEIGHT. Starsector"
                            + " splits it on \"x\" with no validation, so passing it on would fail"
                            + " inside the game and surface as a modal dialog that nothing"
                            + " dismisses.");
        }
        // Absent means "never toggled", and the launcher shows its controls in their default state.
        // Fullscreen defaults off; sound defaults on. Sound in particular cannot be left to fall
        // through, because Boolean.parseBoolean(null) is false and a silently muted launch skips the
        // audio subsystem -- which is both a different measurement and the exact signal the operator
        // uses to decide the game has loaded.
        return Availability.of(new DirectLaunchSettings(
                axes[0],
                axes[1],
                Boolean.parseBoolean(lookup.get(FULLSCREEN_KEY)),
                booleanOrDefault(lookup.get(SOUND_KEY), true)));
    }

    /** Reads the live store the installed game writes to. */
    static Lookup installedPreferences() {
        Preferences node = Preferences.userRoot().node(PREFERENCES_NODE);
        return key -> node.get(key, null);
    }

    /** True when the store exists at all, so an empty result can be told from an unreadable one. */
    static boolean preferencesReadable() {
        try {
            return Preferences.userRoot().nodeExists(PREFERENCES_NODE);
        } catch (BackingStoreException | SecurityException unreadable) {
            return false;
        }
    }

    String resolution() {
        return width + "x" + height;
    }

    /**
     * The JVM options that select the direct launch.
     *
     * <p>{@code launchDirect} is tested with {@code System.getProperty(...) != null}, so the value
     * is immaterial; it carries {@code true} anyway so that a human reading a command line is not
     * left wondering whether an empty property means off.
     */
    List<String> javaOptions() {
        return List.of(
                "-DlaunchDirect=true",
                "-DstartRes=" + resolution(),
                "-DstartFS=" + fullscreen,
                "-DstartSound=" + sound);
    }

    Map<String, Object> toReportValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("resolution", resolution());
        values.put("fullscreen", fullscreen);
        values.put("sound", sound);
        values.put("javaOptions", javaOptions());
        return values;
    }

    private static boolean booleanOrDefault(String value, boolean fallback) {
        return blank(value) ? fallback : Boolean.parseBoolean(value);
    }

    private static boolean positiveInteger(String value) {
        if (blank(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException tooLarge) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
