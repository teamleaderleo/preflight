package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Times the exact identity sequence RunCommand builds before spawning the game JVM. */
final class ProfileHashMemoBenchmark {
    public static void main(String[] args) throws Exception {
        Path install = Path.of(args[0]);
        Path index = Path.of(args[1]);
        long total = System.nanoTime();
        List<String> identities = new ArrayList<>();

        long read = System.nanoTime();
        ResourceIndex resources = ResourceIndexIO.read(index);
        report("index-read", read);
        long opened = System.nanoTime();
        try (ProfileIdentityContext context = ProfileIdentityContext.of(install, resources)) {
            report("context-open", opened);
            identities.add(timed("variant", () ->
                    VariantJsonProfileIdentityBuilder.build(context).identitySha256()));
            identities.add(timed("weapon", () ->
                    WeaponJsonProfileIdentityBuilder.build(context).identitySha256()));
            identities.add(timed("projectile", () ->
                    ProjectileJsonProfileIdentityBuilder.build(context).identitySha256()));
            identities.add(timed("hull", () ->
                    HullJsonProfileIdentityBuilder.build(context).identitySha256()));
            identities.add(timed("rules", () ->
                    RulesCsvProfileIdentityBuilder.build(context).identitySha256()));
            identities.add(timed("rule-command", () ->
                    RuleCommandClassProfileIdentityBuilder.build(context).identitySha256()));
            identities.add(timed("merged-read", () ->
                    MergedReadProfileIdentityBuilder.build(context).identitySha256()));
        }
        report("total", total);
        System.out.println("identities=" + String.join(",", identities));
    }

    private static String timed(String label, Work work) throws Exception {
        long started = System.nanoTime();
        String result = work.run();
        report(label, started);
        return result;
    }

    private static void report(String label, long started) {
        System.out.printf(Locale.ROOT, "%s=%.3f%n",
                label, (System.nanoTime() - started) / 1_000_000.0);
    }

    private interface Work {
        String run() throws Exception;
    }
}
