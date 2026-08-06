package com.fs.starfarer.api;

public final class Global {
    private static SettingsAPI settings;

    private Global() {
    }

    public static SettingsAPI getSettings() {
        return settings;
    }

    public static void setSettings(SettingsAPI value) {
        settings = value;
    }
}
