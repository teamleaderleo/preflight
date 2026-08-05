package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact installed checks for campaign entity maintenance; never starts the game. */
class CampaignEntityMaintenanceInstalledAdapterIT {
    @BeforeEach
    void enable() {
        CampaignEntityMaintenanceRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        CampaignMarketFleetTimeRuntime.reset();
    }

    @Test
    void installedOwnersMatchReviewedShapes() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] entity = entry(archive, CampaignEntityMaintenancePlan.ENTITY_CLASS);
        ClassSignature entitySignature = ClassSignature.parse(entity);
        assertEquals(CampaignEntityMaintenancePlan.ENTITY_SHA256, entitySignature.sha256());
        assertNotNull(CampaignEntityMaintenancePlan.transform(entitySignature, entity));

        byte[] composed = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.campaignEntityIdMutationTarget(), entitySignature, entity);
        assertNotNull(composed);
        ClassNode entityOwner = read(composed);
        assertNotNull(method(entityOwner, CampaignEntityMaintenancePlan.SCRIPT_METHOD,
                CampaignEntityMaintenancePlan.SCRIPT_DESCRIPTOR));
        assertNotNull(method(entityOwner, "preflight$original$runScripts",
                CampaignEntityMaintenancePlan.SCRIPT_DESCRIPTOR));
        assertEquals(1L, calls(method(entityOwner, EntityIdMutationPlan.SET_ID_METHOD,
                EntityIdMutationPlan.SET_ID_DESCRIPTOR),
                "dev/starsector/preflight/agent/EntityLookupRuntime", "entityIdChanging"));

        byte[] fleetView = entry(archive, CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS);
        ClassSignature viewSignature = ClassSignature.parse(fleetView);
        assertEquals(CampaignEntityMaintenancePlan.FLEET_VIEW_SHA256, viewSignature.sha256());
        byte[] transformed = CampaignEntityMaintenancePlan.transform(viewSignature, fleetView);
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        long snapshots = owner.methods.stream()
                .filter(method -> CampaignEntityMaintenancePlan.ADVANCE_METHOD.equals(method.name))
                .flatMap(method -> {
                    java.util.List<AbstractInsnNode> values = new java.util.ArrayList<>();
                    method.instructions.forEach(values::add);
                    return values.stream();
                })
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && "getSortedMembers".equals(call.name))
                .count();
        assertEquals(1L, snapshots);

        byte[] market = entry(archive, CampaignEntityMaintenancePlan.MARKET_CLASS);
        ClassSignature marketSignature = ClassSignature.parse(market);
        assertEquals(CampaignEntityMaintenancePlan.MARKET_SHA256, marketSignature.sha256());
        byte[] marketMaintenance = CampaignEntityMaintenancePlan.transform(marketSignature, market);
        assertNotNull(marketMaintenance);
        String maintenanceRuntime = CampaignEntityMaintenanceRuntime.class.getName().replace('.', '/');
        assertEquals(2L, calls(method(read(marketMaintenance),
                CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR),
                maintenanceRuntime, "marketSnapshotIterator"));

        CampaignMarketFleetTimeRuntime.beginSession(true);
        byte[] composedMarket = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.campaignMarketSnapshotTarget(), marketSignature, market);
        assertNotNull(composedMarket);
        MethodNode composedAdvance = method(read(composedMarket),
                CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR);
        assertEquals(2L, calls(composedAdvance, maintenanceRuntime, "marketSnapshotIterator"));
        String timingRuntime = CampaignMarketFleetTimeRuntime.class.getName().replace('.', '/');
        assertEquals(8L, calls(composedAdvance, timingRuntime, "enter"));
        assertEquals(3L, calls(composedAdvance, timingRuntime, "enterClass"));

        byte[] memory = entry(archive, CampaignEntityMaintenancePlan.MEMORY_CLASS);
        ClassSignature memorySignature = ClassSignature.parse(memory);
        assertEquals(CampaignEntityMaintenancePlan.MEMORY_SHA256, memorySignature.sha256());
        byte[] memoryMaintenance = CampaignEntityMaintenancePlan.transform(memorySignature, memory);
        assertNotNull(memoryMaintenance);
        MethodNode memoryAdvance = method(read(memoryMaintenance),
                CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR);
        assertEquals(1L, calls(memoryAdvance, maintenanceRuntime, "memoryExpirationsPresent"));
        assertEquals(1L, calls(memoryAdvance, maintenanceRuntime, "memoryRequirementsPresent"));
        MethodNode memoryRestoration = method(read(memoryMaintenance),
                CampaignEntityMaintenancePlan.RESTORE_MEMORY_IDS_METHOD,
                CampaignEntityMaintenancePlan.RESTORE_MEMORY_IDS_DESCRIPTOR);
        assertEquals(1L, calls(
                memoryRestoration, maintenanceRuntime, "memoryIdSnapshotIterator"));
        assertEquals(0L, calls(memoryRestoration, "java/lang/String", "replaceFirst"));
        assertEquals(2L, calls(memoryRestoration, "java/lang/String", "substring"));

        byte[] economy = entry(archive, CampaignEntityMaintenancePlan.ECONOMY_CLASS);
        ClassSignature economySignature = ClassSignature.parse(economy);
        assertEquals(CampaignEntityMaintenancePlan.ECONOMY_SHA256, economySignature.sha256());
        byte[] economyMaintenance = CampaignEntityMaintenancePlan.transform(
                economySignature, economy);
        assertNotNull(economyMaintenance);
        assertEquals(1L, calls(method(read(economyMaintenance),
                CampaignEntityMaintenancePlan.PAUSED_CONDITIONS_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR),
                maintenanceRuntime, "marketSnapshotIterator"));

        CampaignLocationEconomyTimeRuntime.beginSession(true);
        byte[] composedEconomy = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.campaignPausedConditionSnapshotTarget(),
                economySignature, economy);
        assertNotNull(composedEconomy);
        ClassNode composedEconomyOwner = read(composedEconomy);
        assertEquals(1L, calls(method(composedEconomyOwner,
                CampaignEntityMaintenancePlan.PAUSED_CONDITIONS_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR),
                maintenanceRuntime, "marketSnapshotIterator"));
        assertEquals(3L, calls(method(composedEconomyOwner,
                CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR),
                "dev/starsector/preflight/agent/CampaignLocationEconomyTimeRuntime", "enter"));

        CampaignLocationEconomyTimeRuntime.beginSession(true);
        byte[] location = entry(archive, CampaignEntityMaintenancePlan.LOCATION_CLASS);
        ClassSignature locationSignature = ClassSignature.parse(location);
        assertEquals(CampaignEntityMaintenancePlan.LOCATION_SHA256, locationSignature.sha256());
        byte[] composedLocation = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.campaignEntityIndexTarget(), locationSignature, location);
        assertNotNull(composedLocation);
        ClassNode composedLocationOwner = read(composedLocation);
        MethodNode pausedLocation = method(composedLocationOwner,
                CampaignEntityMaintenancePlan.PAUSED_LOCATION_METHOD,
                CampaignEntityMaintenancePlan.LOCATION_DESCRIPTOR);
        assertEquals(2L, calls(pausedLocation, maintenanceRuntime, "locationSnapshot"));
        assertEquals(3L, calls(pausedLocation, maintenanceRuntime, "locationSnapshotIterator"));
        assertEquals(0L, calls(pausedLocation, "java/util/ArrayList", "<init>"));
        assertEquals(2L, calls(pausedLocation,
                "dev/starsector/preflight/agent/CampaignLocationEconomyTimeRuntime", "enterClass"));
        MethodNode activeLocation = method(composedLocationOwner,
                CampaignEntityMaintenancePlan.ACTIVE_LOCATION_METHOD,
                CampaignEntityMaintenancePlan.LOCATION_DESCRIPTOR);
        assertEquals(3L, calls(activeLocation, maintenanceRuntime, "locationSnapshot"));
        assertEquals(3L, calls(activeLocation, maintenanceRuntime, "locationSnapshotIterator"));
        assertEquals(0L, calls(activeLocation, "java/util/ArrayList", "<init>"));
        assertEquals(1L, calls(activeLocation,
                "dev/starsector/preflight/agent/CampaignLocationEconomyTimeRuntime", "enterClass"));
        assertEquals(1L, calls(method(composedLocationOwner,
                EntityLookupPlan.LOOKUP_METHOD, EntityLookupPlan.LOOKUP_DESCRIPTOR),
                EntityLookupRuntime.class.getName().replace('.', '/'), "lookup"));

        Path apiArchive = archive.resolveSibling("starfarer.api.jar");
        Assumptions.assumeTrue(Files.isRegularFile(apiArchive));
        byte[] automaton = entry(
                apiArchive, CampaignEntityMaintenancePlan.HYPERSPACE_AUTOMATON_CLASS);
        ClassSignature automatonSignature = ClassSignature.parse(automaton);
        assertEquals(CampaignEntityMaintenancePlan.HYPERSPACE_AUTOMATON_SHA256,
                automatonSignature.sha256());
        byte[] optimizedAutomaton = CampaignEntityMaintenancePlan.transform(
                automatonSignature, automaton);
        assertNotNull(optimizedAutomaton);
        MethodNode neighborCount = method(read(optimizedAutomaton),
                CampaignEntityMaintenancePlan.LIVE_NEIGHBOR_METHOD,
                CampaignEntityMaintenancePlan.LIVE_NEIGHBOR_DESCRIPTOR);
        assertEquals(1L, calls(neighborCount, maintenanceRuntime,
                "hyperspaceLiveNeighborCount"));
        assertEquals(0L, calls(neighborCount, "java/lang/Math", "max"));
        assertEquals(0L, calls(neighborCount, "java/lang/Math", "min"));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static long calls(MethodNode method, String owner, String name) {
        long result = 0L;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static byte[] entry(Path archive, String name) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(name + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }
}
