package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

final class AgentInjection {
    private AgentInjection() {
    }

    static String append(String existing, Path agentJar, Path destination) {
        return append(
                existing,
                agentJar,
                destination,
                AdapterMode.OFF,
                null,
                null,
                null,
                null,
                null,
                TextureAdapterMode.COMPATIBILITY);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets) {
        return append(
                existing,
                agentJar,
                destination,
                adapterMode,
                adapterReport,
                adapterTargets,
                null,
                null,
                null,
                TextureAdapterMode.COMPATIBILITY);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex) {
        return append(
                existing,
                agentJar,
                destination,
                adapterMode,
                adapterReport,
                adapterTargets,
                textureCacheDirectory,
                textureManifest,
                textureIndex,
                TextureAdapterMode.COMPATIBILITY);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode) {
        return append(existing, agentJar, destination, adapterMode, adapterReport, adapterTargets,
                textureCacheDirectory, textureManifest, textureIndex, textureAdapterMode, false, true);
    }

    static String append(
            String existing,
            Path agentJar,
            Path destination,
            AdapterMode adapterMode,
            Path adapterReport,
            Path adapterTargets,
            Path textureCacheDirectory,
            Path textureManifest,
            Path textureIndex,
            TextureAdapterMode textureAdapterMode,
            boolean exhaustiveFileReads,
            boolean recordStartup) {
        String current = existing == null ? "" : existing.trim();
        String lower = current.toLowerCase(Locale.ROOT);
        if (lower.contains("-javaagent:") && lower.contains("preflight")) {
            throw new IllegalArgumentException("JAVA_TOOL_OPTIONS already contains a Preflight javaagent");
        }

        StringBuilder arguments = new StringBuilder("dest64=")
                .append(encodedPath(destination))
                .append(",adapter=")
                .append(adapterMode.optionValue())
                .append(",textureMode=")
                .append(textureAdapterMode.optionValue());
        appendPath(arguments, "adapterReport64", adapterReport);
        appendPath(arguments, "targets64", adapterTargets);
        appendPath(arguments, "textureCache64", textureCacheDirectory);
        appendPath(arguments, "textureManifest64", textureManifest);
        appendPath(arguments, "textureIndex64", textureIndex);
        if (exhaustiveFileReads) {
            arguments.append(",fileReads=all");
        }
        if (!recordStartup) {
            arguments.append(",record=off");
        }
        String option = "-javaagent:"
                + quoteJvmOptionValue(agentJar.toAbsolutePath().normalize().toString())
                + "="
                + arguments;
        return current.isEmpty() ? option : current + " " + option;
    }

    private static void appendPath(StringBuilder arguments, String key, Path value) {
        if (value != null) {
            arguments.append(',').append(key).append('=').append(encodedPath(value));
        }
    }

    private static String encodedPath(Path value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    static String quoteJvmOptionValue(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Unsupported character in javaagent path: " + value);
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return '"' + value + '"';
        }
        return value;
    }
}
