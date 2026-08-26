package dev.starsector.preflight.agent;

import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Replays the idempotent transformer-side registration represented by cached output bytecode. */
final class AdapterInstallationEffects {
    private static final String PREFIX = "dev/starsector/preflight/agent/";

    private AdapterInstallationEffects() {
    }

    static void replay(AdapterTarget target, ClassSignature signature, byte[] transformed) {
        ClassReader reader = new ClassReader(transformed);
        Set<String> referenced = referencedRuntimeOwners(reader);
        String className = reader.getClassName();
        if (!signature.internalName().equals(className)) {
            throw new IllegalArgumentException("Cached transformation class identity differs");
        }

        if (AiTweaksEngagementRangeRuntime.PLAN_ID.equals(target.planId())) {
            AiTweaksEngagementRangeRuntime.installed();
        }
        if (AudioResourceFallbackRuntime.PLAN_ID.equals(target.planId())) {
            AudioResourceFallbackRuntime.installed();
        }
        if (AudioStreamSourceErrorRuntime.PLAN_ID.equals(target.planId())) {
            AudioStreamSourceErrorRuntime.installed();
            if (has(referenced, AudioMusicTransitionRuntime.class)) {
                AudioMusicTransitionRuntime.installed();
            }
        }
        if (CombatRuntimeIntegrityRuntime.PLAN_ID.equals(target.planId())) {
            CombatRuntimeIntegrityRuntime.installed();
        }
        if (ContrailRenderScratchRuntime.PLAN_ID.equals(target.planId())) {
            ContrailRenderScratchRuntime.installed();
        }
        if (CommodityEventModMemoRuntime.PLAN_ID.equals(target.planId())
                && CommodityEventModMemoPlan.TARGET_CLASS.equals(className)) {
            CommodityEventModMemoRuntime.installed();
        }
        if (DeploymentIconCacheRuntime.PLAN_ID.equals(target.planId())) {
            DeploymentIconCacheRuntime.installed();
        }
        if (GraphicsLibHotSettingsRuntime.PLAN_ID.equals(target.planId())) {
            GraphicsLibHotSettingsRuntime.installed();
        }
        if (LogisticsNotificationsFuelRuntime.PLAN_ID.equals(target.planId())) {
            LogisticsNotificationsFuelRuntime.installed();
        }
        if (LunaCampaignRendererSnapshotRuntime.PLAN_ID.equals(target.planId())) {
            if (LunaCampaignRendererSnapshotPlan.SCRIPT_CLASS.equals(className)) {
                LunaCampaignRendererSnapshotRuntime.scriptInstalled();
            } else if (LunaCampaignRendererSnapshotPlan.ENTITY_CLASS.equals(className)) {
                LunaCampaignRendererSnapshotRuntime.entityInstalled();
            }
        }
        if (MacMemoryWarningRuntime.PLAN_ID.equals(target.planId())) {
            MacMemoryWarningRuntime.installed();
        }
        if (MagicLibPaintjobRuntime.PLAN_ID.equals(target.planId())) {
            MagicLibPaintjobRuntime.installed();
        }
        if (MagicLibPaintjobNotificationRuntime.PLAN_ID.equals(target.planId())) {
            MagicLibPaintjobNotificationRuntime.installed();
        }
        if (has(referenced, MagicLibPaintjobSnapshotRuntime.class)) {
            MagicLibPaintjobSnapshotRuntime.installed();
        }
        if (has(referenced, MagicLibPaintjobLoadRuntime.class)) {
            MagicLibPaintjobLoadRuntime.installed();
        }
        if (RadarRenderRuntime.PLAN_ID.equals(target.planId())) {
            RadarRenderRuntime.installed();
        }
        if (SaveDescriptorCompatibilityRuntime.PLAN_ID.equals(target.planId())) {
            SaveDescriptorCompatibilityRuntime.installed();
        }
        if (IndustryDemandSupplyMemoRuntime.PLAN_ID.equals(target.planId())) {
            if (IndustryDemandSupplyMemoPlan.CODEX_CLASS.equals(className)) {
                IndustryDemandSupplyMemoRuntime.codexInstalled();
            } else if (IndustryDemandSupplyMemoPlan.SETTINGS_CLASS.equals(className)) {
                IndustryDemandSupplyMemoRuntime.settingsInstalled();
            }
        }
        if (has(referenced, CodexLazyFleetMemberRuntime.class)) {
            if (CodexLazyFleetMemberPlan.CODEX_CLASS.equals(className)) {
                CodexLazyFleetMemberRuntime.codexInstalled();
            } else if (CodexLazyFleetMemberPlan.ENTRY_CLASS.equals(className)) {
                CodexLazyFleetMemberRuntime.entryAccessorInstalled();
            }
        }
        if (IndEvoSyntheticMarketRuntime.PLAN_ID.equals(target.planId())) {
            IndEvoSyntheticMarketRuntime.installed();
        }
        if (SimOpponentSafetyRuntime.PLAN_ID.equals(target.planId())
                && SimOpponentSafetyPlan.TARGET_CLASS.equals(className)) {
            SimOpponentSafetyRuntime.installed();
        }
        if (SourceHintIsolationRuntime.PLAN_ID.equals(target.planId())
                || has(referenced, SourceHintIsolationRuntime.class)) {
            SourceHintIsolationRuntime.installed();
        }
        if (has(referenced, StartupPhaseRuntime.class)) {
            StartupPhaseRuntime.installed();
        }
        if (StelnetMarketUpdaterRuntime.PLAN_ID.equals(target.planId())) {
            StelnetMarketUpdaterRuntime.installed();
        }
        if (has(referenced, TexturePaddingRuntime.class)) {
            TexturePaddingRuntime.foldBypassInstalled();
        }
        if (VersionCheckResponseDedupRuntime.PLAN_ID.equals(target.planId())) {
            VersionCheckResponseDedupRuntime.installed();
        }

        if (EntityLookupRuntime.PLAN_ID.equals(target.planId())) {
            if (EntityLookupPlan.TARGET_CLASS.equals(className)) {
                EntityLookupRuntime.locationInstalled();
            } else if (EntityRepositoryListPlan.TARGET_CLASS.equals(className)) {
                EntityLookupRuntime.repositoryInstalled();
            } else if (EntityIdMutationPlan.TARGET_CLASS.equals(className)) {
                EntityLookupRuntime.idMutationInstalled();
            }
        }
        if (AshLibVariantLookupRuntime.PLAN_ID.equals(target.planId())) {
            if (AshLibVariantLookupPlan.REPOSITORY_CLASS.equals(className)) {
                AshLibVariantLookupRuntime.repositoryInstalled();
            } else if (AshLibVariantLookupPlan.LOOKUP_CLASS.equals(className)) {
                AshLibVariantLookupRuntime.lookupInstalled();
            } else if (AshLibVariantLookupPlan.SHIP_JSON_CLASS.equals(className)) {
                AshLibVariantLookupRuntime.shipJsonInstalled();
            }
        }
        if (CampaignEntityMaintenanceRuntime.enabled()) {
            replayCampaignMaintenance(className);
        }
        if (FleetAiProfilerRuntime.PLAN_ID.equals(target.planId())) {
            if (FleetAiProfilerPlan.FLEET_AI_CLASS.equals(className)) {
                FleetAiProfilerRuntime.fleetInstalled();
            } else if (FleetAiProfilerPlan.PROFILER_CLASS.equals(className)) {
                FleetAiProfilerRuntime.profilerInstalled();
            }
        }
    }

    private static void replayCampaignMaintenance(String className) {
        if (CampaignEntityMaintenancePlan.ENTITY_CLASS.equals(className)) {
            CampaignEntityMaintenanceRuntime.entityScriptsInstalled();
        } else if (CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS.equals(className)) {
            CampaignEntityMaintenanceRuntime.fleetViewInstalled();
        } else if (CampaignEntityMaintenancePlan.MARKET_CLASS.equals(className)) {
            CampaignEntityMaintenanceRuntime.marketSnapshotsInstalled();
        } else if (CampaignEntityMaintenancePlan.MEMORY_CLASS.equals(className)) {
            CampaignEntityMaintenanceRuntime.memoryInstalled();
        } else if (CampaignEntityMaintenancePlan.ECONOMY_CLASS.equals(className)) {
            CampaignEntityMaintenanceRuntime.pausedConditionsInstalled();
        } else if (CampaignEntityMaintenancePlan.LOCATION_CLASS.equals(className)) {
            CampaignEntityMaintenanceRuntime.pausedLocationSnapshotsInstalled();
            CampaignEntityMaintenanceRuntime.activeLocationSnapshotsInstalled();
        } else if (CampaignEntityMaintenancePlan.HYPERSPACE_AUTOMATON_CLASS.equals(className)) {
            CampaignEntityMaintenanceRuntime.hyperspaceAutomatonInstalled();
        }
    }

    private static Set<String> referencedRuntimeOwners(ClassReader reader) {
        Set<String> owners = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(
                    int access, String name, String descriptor, String signature, Object value) {
                collectDescriptor(owners, descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                collectMethodDescriptor(owners, descriptor);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        collectOwner(owners, owner);
                        collectDescriptor(owners, descriptor);
                    }

                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String name,
                            String descriptor,
                            boolean isInterface) {
                        collectOwner(owners, owner);
                        collectMethodDescriptor(owners, descriptor);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        collectOwner(owners, type);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return owners;
    }

    private static void collectMethodDescriptor(Set<String> owners, String descriptor) {
        for (Type argument : Type.getArgumentTypes(descriptor)) collectType(owners, argument);
        collectType(owners, Type.getReturnType(descriptor));
    }

    private static void collectDescriptor(Set<String> owners, String descriptor) {
        collectType(owners, Type.getType(descriptor));
    }

    private static void collectType(Set<String> owners, Type type) {
        Type element = type;
        while (element.getSort() == Type.ARRAY) element = element.getElementType();
        if (element.getSort() == Type.OBJECT) collectOwner(owners, element.getInternalName());
    }

    private static void collectOwner(Set<String> owners, String owner) {
        if (owner != null && owner.startsWith(PREFIX)) owners.add(owner);
    }

    private static boolean has(Set<String> owners, Class<?> runtime) {
        return owners.contains(runtime.getName().replace('.', '/'));
    }
}
