package dev.starsector.preflight.agent;

import java.security.CodeSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-shot proof of the loaded weapon/fitted-module type relationship implicated by a crash. */
public final class CombatRuntimeIntegrityRuntime {
    static final String PLAN_ID = "vanilla-combat-runtime-integrity-v1";
    private static final String WEAPON_CLASS =
            "com.fs.starfarer.combat.entities.ship.A.J";
    private static final String FITTED_MODULE_CLASS =
            "com.fs.starfarer.combat.entities.ship.A.null";

    private static volatile boolean observed;
    private static boolean installed;
    private static boolean assignable;
    private static boolean sameLoader;
    private static String weaponClass;
    private static String fittedModuleClass;
    private static String weaponLoader;
    private static String fittedModuleLoader;
    private static String weaponSource;
    private static String fittedModuleSource;
    private static List<String> weaponInterfaces = List.of();
    private static String failure;

    private CombatRuntimeIntegrityRuntime() {
    }

    static synchronized void beginSession() {
        observed = false;
        installed = false;
        assignable = false;
        sameLoader = false;
        weaponClass = null;
        fittedModuleClass = null;
        weaponLoader = null;
        fittedModuleLoader = null;
        weaponSource = null;
        fittedModuleSource = null;
        weaponInterfaces = List.of();
        failure = null;
    }

    static synchronized void installed() {
        installed = true;
    }

    /** Called once from the reviewed CombatEngine advance seam. */
    public static void observe() {
        if (observed) return;
        synchronized (CombatRuntimeIntegrityRuntime.class) {
            if (observed) return;
            try {
                ClassLoader loader = CombatRuntimeIntegrityRuntime.class.getClassLoader();
                Class<?> weapon = Class.forName(WEAPON_CLASS, false, loader);
                Class<?> fittedModule = Class.forName(FITTED_MODULE_CLASS, false, loader);
                observeClasses(weapon, fittedModule);
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable problem) {
                failure = message(problem);
                observed = true;
            }
        }
    }

    static synchronized void observeClasses(Class<?> weapon, Class<?> fittedModule) {
        if (observed) return;
        weaponClass = weapon.getName();
        fittedModuleClass = fittedModule.getName();
        weaponLoader = loaderName(weapon.getClassLoader());
        fittedModuleLoader = loaderName(fittedModule.getClassLoader());
        sameLoader = weapon.getClassLoader() == fittedModule.getClassLoader();
        assignable = fittedModule.isAssignableFrom(weapon);
        weaponSource = source(weapon);
        fittedModuleSource = source(fittedModule);
        weaponInterfaces = Arrays.stream(weapon.getInterfaces())
                .map(Class::getName)
                .sorted()
                .toList();
        observed = true;
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", installed);
        result.put("observed", observed);
        result.put("diagnosticJvmMode",
                System.getProperty("preflight.combatIntegrity.jvmMode", "launcher-default"));
        result.put("assignable", assignable);
        result.put("sameLoader", sameLoader);
        result.put("weaponClass", weaponClass);
        result.put("fittedModuleClass", fittedModuleClass);
        result.put("weaponLoader", weaponLoader);
        result.put("fittedModuleLoader", fittedModuleLoader);
        result.put("weaponSource", weaponSource);
        result.put("fittedModuleSource", fittedModuleSource);
        result.put("weaponInterfaces", weaponInterfaces);
        result.put("failure", failure);
        return result;
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        String name = loader.getName();
        return loader.getClass().getName() + (name == null ? "" : ":" + name);
    }

    private static String source(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null
                    ? null : source.getLocation().toString();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String message(Throwable problem) {
        String detail = problem.getMessage();
        return problem.getClass().getName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
}
