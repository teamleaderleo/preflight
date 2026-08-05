package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes redundant vanilla campaign-entity and market maintenance allocations. */
final class CampaignEntityMaintenancePlan {
    static final String ENTITY_CLASS = "com/fs/starfarer/campaign/BaseCampaignEntity";
    static final String ENTITY_SHA256 =
            "7e4683903f4ad35219912dfd7aecb0db1e302957bb7d896e09620b1a8135b18b";
    static final String SCRIPT_METHOD = "runScripts";
    static final String SCRIPT_DESCRIPTOR = "(F)V";

    static final String FLEET_VIEW_CLASS = "com/fs/starfarer/campaign/fleet/CampaignFleetView";
    static final String FLEET_VIEW_SHA256 =
            "55ad696cd51ec7b39a1d34797c77d78dfaebc386af6bb4d12015b235d3c28b2c";
    static final String MARKET_CLASS = "com/fs/starfarer/campaign/econ/Market";
    static final String MARKET_SHA256 =
            "8e9c1e400b1491406836378df83976c31eec453d2a77f8f13c6d030a52bbc0ae";
    static final String MEMORY_CLASS = "com/fs/starfarer/campaign/rules/Memory";
    static final String MEMORY_SHA256 =
            "48811db41f31f2bafdeaf73e2f98f864a055efa69dfd8442400042ab967b77d3";
    static final String ECONOMY_CLASS = "com/fs/starfarer/campaign/econ/Economy";
    static final String ECONOMY_SHA256 =
            "e3c66eca6a70cdfb17b298f45b020994146a1c29cd64422401dbdf11107cb529";
    static final String LOCATION_CLASS = "com/fs/starfarer/campaign/BaseLocation";
    static final String LOCATION_SHA256 =
            "ab16080b8c40d8f61d522089f3c3696fe3b7c8d8f8b287f9c12a47fa449bae24";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(F)V";
    static final String PAUSED_CONDITIONS_METHOD = "advanceMarketConditionsWhenPaused";
    static final String RESTORE_MEMORY_IDS_METHOD = "replaceIdsWithEntities";
    static final String RESTORE_MEMORY_IDS_DESCRIPTOR = "(Ljava/util/LinkedHashMap;)V";
    static final String ACTIVE_LOCATION_METHOD = "advance";
    static final String PAUSED_LOCATION_METHOD = "advanceEvenIfPaused";
    static final String LOCATION_DESCRIPTOR = "(FLcom/fs/starfarer/util/super/B;)V";

    private static final String ORIGINAL_SCRIPT = "preflight$original$runScripts";
    private static final String SCRIPTS_FIELD = "scripts";
    private static final String SCRIPTS_DESCRIPTOR = "Ljava/util/List;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/CampaignEntityMaintenanceRuntime";
    private static final String CAMPAIGN_FLEET = "com/fs/starfarer/campaign/fleet/CampaignFleet";
    private static final String SORTED_MEMBERS = "getSortedMembers";
    private static final String SORTED_DESCRIPTOR = "()Ljava/util/List;";
    private static final int MARKET_CONDITIONS = 0;
    private static final int MARKET_INDUSTRIES = 1;
    private static final int PAUSED_MARKET_CONDITIONS = 2;
    private static final int PAUSED_LOCATION_ENTITIES = 3;
    private static final int PAUSED_LOCATION_SCRIPTS = 4;
    private static final int ACTIVE_LOCATION_ENTITIES = 5;
    private static final int ACTIVE_LOCATION_TOKENS = 6;
    private static final int ACTIVE_ENGAGEMENT_ENTITIES = 7;

    private CampaignEntityMaintenancePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!CampaignEntityMaintenanceRuntime.enabled() || signature.majorVersion() != 61) return null;
        if (ENTITY_CLASS.equals(signature.internalName()) && ENTITY_SHA256.equals(signature.sha256())) {
            return transformEntity(originalBytes);
        }
        if (FLEET_VIEW_CLASS.equals(signature.internalName())
                && FLEET_VIEW_SHA256.equals(signature.sha256())) {
            return transformFleetView(originalBytes);
        }
        if (MARKET_CLASS.equals(signature.internalName())
                && MARKET_SHA256.equals(signature.sha256())) {
            return transformMarket(originalBytes);
        }
        if (MEMORY_CLASS.equals(signature.internalName())
                && MEMORY_SHA256.equals(signature.sha256())) {
            return transformMemory(originalBytes);
        }
        if (ECONOMY_CLASS.equals(signature.internalName())
                && ECONOMY_SHA256.equals(signature.sha256())) {
            return transformEconomy(originalBytes);
        }
        if (LOCATION_CLASS.equals(signature.internalName())
                && LOCATION_SHA256.equals(signature.sha256())) {
            return transformLocation(originalBytes);
        }
        return null;
    }

    private static byte[] transformEntity(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode original = unique(owner, SCRIPT_METHOD, SCRIPT_DESCRIPTOR);
        if (original == null || unique(owner, ORIGINAL_SCRIPT, SCRIPT_DESCRIPTOR) != null
                || !reviewScriptShape(original)) return null;
        int access = original.access;
        original.name = ORIGINAL_SCRIPT;

        MethodNode wrapper = new MethodNode(
                Opcodes.ASM9, access, SCRIPT_METHOD, SCRIPT_DESCRIPTOR, null, null);
        LabelNode nonEmpty = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, ENTITY_CLASS, SCRIPTS_FIELD, SCRIPTS_DESCRIPTOR));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "emptyScriptList", "()V", false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        wrapper.instructions.add(nonEmpty);
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "nonEmptyScriptList", "()V", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, ENTITY_CLASS, ORIGINAL_SCRIPT, SCRIPT_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
        owner.methods.add(wrapper);
        CampaignEntityMaintenanceRuntime.entityScriptsInstalled();
        return write(owner);
    }

    private static byte[] transformFleetView(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;
        List<MethodInsnNode> snapshots = matching(
                method, CAMPAIGN_FLEET, SORTED_MEMBERS, SORTED_DESCRIPTOR);
        if (snapshots.size() != 2) return null;
        MethodInsnNode second = snapshots.get(1);
        AbstractInsnNode fieldNode = previousMeaningful(second);
        AbstractInsnNode loadThis = previousMeaningful(fieldNode);
        if (!(fieldNode instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD
                || !FLEET_VIEW_CLASS.equals(field.owner)
                || !"fleet".equals(field.name)
                || !(loadThis instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return null;
        method.instructions.insertBefore(loadThis, new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.remove(loadThis);
        method.instructions.remove(fieldNode);
        method.instructions.remove(second);
        CampaignEntityMaintenanceRuntime.fleetViewInstalled();
        return write(owner);
    }

    private static byte[] transformMarket(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;

        List<MarketSnapshot> snapshots = marketSnapshots(method);
        if (snapshots.size() != 2
                || snapshots.stream().filter(snapshot -> snapshot.kind == MARKET_CONDITIONS).count() != 1
                || snapshots.stream().filter(snapshot -> snapshot.kind == MARKET_INDUSTRIES).count() != 1) {
            return null;
        }
        for (MarketSnapshot snapshot : snapshots) {
            method.instructions.remove(snapshot.allocation);
            method.instructions.remove(snapshot.duplicate);
            method.instructions.insertBefore(snapshot.constructor, new LdcInsnNode(snapshot.kind));
            method.instructions.insertBefore(snapshot.constructor, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "marketSnapshotIterator",
                    "(Ljava/util/List;I)Ljava/util/Iterator;", false));
            method.instructions.remove(snapshot.constructor);
            method.instructions.remove(snapshot.iterator);
        }
        CampaignEntityMaintenanceRuntime.marketSnapshotsInstalled();
        return write(owner);
    }

    private static byte[] transformMemory(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        MethodNode restoration = unique(
                owner, RESTORE_MEMORY_IDS_METHOD, RESTORE_MEMORY_IDS_DESCRIPTOR);
        if (method == null || restoration == null
                || callsRuntime(method) != 0 || callsRuntime(restoration) != 0
                || !rewriteMemoryIdRestoration(restoration)) return null;

        List<IteratorStart> expires = iteratorStarts(
                method, "expire", "Ljava/util/List;", "java/util/List", null);
        List<IteratorStart> requirements = iteratorStarts(
                method, "require", "Ljava/util/LinkedHashMap;",
                "java/util/Collection", "java/util/LinkedHashMap");
        if (expires.size() != 1 || requirements.size() != 1) return null;

        IteratorStart expire = expires.get(0);
        IteratorStart require = requirements.get(0);
        LabelNode requireGate = new LabelNode();
        LabelNode requireBody = new LabelNode();
        InsnList requireGuard = new InsnList();
        requireGuard.add(requireGate);
        requireGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        requireGuard.add(new FieldInsnNode(
                Opcodes.GETFIELD, MEMORY_CLASS, "require", "Ljava/util/LinkedHashMap;"));
        requireGuard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "memoryRequirementsPresent",
                "(Ljava/util/Map;)Z", false));
        requireGuard.add(new JumpInsnNode(Opcodes.IFNE, requireBody));
        requireGuard.add(new InsnNode(Opcodes.RETURN));
        requireGuard.add(requireBody);
        method.instructions.insertBefore(require.loadThis, requireGuard);

        InsnList expireGuard = new InsnList();
        expireGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        expireGuard.add(new FieldInsnNode(
                Opcodes.GETFIELD, MEMORY_CLASS, "expire", "Ljava/util/List;"));
        expireGuard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "memoryExpirationsPresent",
                "(Ljava/util/List;)Z", false));
        expireGuard.add(new JumpInsnNode(Opcodes.IFEQ, requireGate));
        method.instructions.insertBefore(expire.loadThis, expireGuard);

        CampaignEntityMaintenanceRuntime.memoryInstalled();
        return write(owner);
    }

    /** Retains the stable key snapshot while removing its ArrayList and two literal regexes. */
    private static boolean rewriteMemoryIdRestoration(MethodNode method) {
        List<MethodInsnNode> constructors = matching(
                method, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V");
        if (constructors.size() != 1) return false;
        MethodInsnNode constructor = constructors.get(0);
        AbstractInsnNode keySet = previousMeaningful(constructor);
        AbstractInsnNode loadMap = previousMeaningful(keySet);
        AbstractInsnNode duplicate = previousMeaningful(loadMap);
        AbstractInsnNode allocation = previousMeaningful(duplicate);
        AbstractInsnNode iterator = nextMeaningful(constructor);
        if (!(keySet instanceof MethodInsnNode keys)
                || keys.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !"java/util/LinkedHashMap".equals(keys.owner)
                || !"keySet".equals(keys.name) || !"()Ljava/util/Set;".equals(keys.desc)
                || !(loadMap instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 1
                || duplicate.getOpcode() != Opcodes.DUP
                || !(allocation instanceof TypeInsnNode type)
                || allocation.getOpcode() != Opcodes.NEW
                || !"java/util/ArrayList".equals(type.desc)
                || !(iterator instanceof MethodInsnNode cursor)
                || cursor.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !"java/util/ArrayList".equals(cursor.owner)
                || !"iterator".equals(cursor.name)
                || !"()Ljava/util/Iterator;".equals(cursor.desc)) return false;

        List<MethodInsnNode> replacements = matching(
                method, "java/lang/String", "replaceFirst",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        if (replacements.size() != 2) return false;
        boolean entity = false;
        boolean market = false;
        for (MethodInsnNode replacement : replacements) {
            AbstractInsnNode empty = previousMeaningful(replacement);
            AbstractInsnNode prefix = previousMeaningful(empty);
            if (!(empty instanceof LdcInsnNode emptyString) || !"".equals(emptyString.cst)
                    || !(prefix instanceof LdcInsnNode prefixString)
                    || !(prefixString.cst instanceof String value)) return false;
            int length;
            if ("enRef_".equals(value) && !entity) {
                entity = true;
                length = 6;
            } else if ("mRef_".equals(value) && !market) {
                market = true;
                length = 5;
            } else {
                return false;
            }
            method.instructions.insertBefore(prefix, new LdcInsnNode(length));
            method.instructions.remove(prefix);
            method.instructions.remove(empty);
            replacement.name = "substring";
            replacement.desc = "(I)Ljava/lang/String;";
        }
        if (!entity || !market) return false;

        method.instructions.insertBefore(iterator, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "memoryIdSnapshotIterator",
                "(Ljava/util/Map;)Ljava/util/Iterator;", false));
        method.instructions.remove(allocation);
        method.instructions.remove(duplicate);
        method.instructions.remove(keySet);
        method.instructions.remove(constructor);
        method.instructions.remove(iterator);
        return true;
    }

    private static byte[] transformEconomy(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode method = unique(owner, PAUSED_CONDITIONS_METHOD, ADVANCE_DESCRIPTOR);
        if (method == null || callsRuntime(method) != 0) return null;
        List<MarketSnapshot> snapshots = pausedConditionSnapshots(method);
        if (snapshots.size() != 1) return null;
        MarketSnapshot snapshot = snapshots.get(0);
        method.instructions.remove(snapshot.allocation);
        method.instructions.remove(snapshot.duplicate);
        method.instructions.insertBefore(snapshot.constructor,
                new LdcInsnNode(PAUSED_MARKET_CONDITIONS));
        method.instructions.insertBefore(snapshot.constructor, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "marketSnapshotIterator",
                "(Ljava/util/List;I)Ljava/util/Iterator;", false));
        method.instructions.remove(snapshot.constructor);
        method.instructions.remove(snapshot.iterator);
        CampaignEntityMaintenanceRuntime.pausedConditionsInstalled();
        return write(owner);
    }

    private static byte[] transformLocation(byte[] originalBytes) {
        ClassNode owner = read(originalBytes);
        MethodNode active = unique(owner, ACTIVE_LOCATION_METHOD, LOCATION_DESCRIPTOR);
        MethodNode paused = unique(owner, PAUSED_LOCATION_METHOD, LOCATION_DESCRIPTOR);
        if (active == null || paused == null
                || callsRuntime(active) != 0 || callsRuntime(paused) != 0) return null;
        List<LocationSnapshot> pausedSnapshots = pausedLocationSnapshots(paused);
        if (pausedSnapshots.size() != 2
                || pausedSnapshots.stream().filter(snapshot ->
                        snapshot.kind == PAUSED_LOCATION_ENTITIES).count() != 1
                || pausedSnapshots.stream().filter(snapshot ->
                        snapshot.kind == PAUSED_LOCATION_SCRIPTS).count() != 1) return null;
        List<LocationSnapshot> activeSnapshots = activeLocationSnapshots(active);
        if (activeSnapshots.size() != 3
                || activeSnapshots.stream().filter(snapshot ->
                        snapshot.kind == ACTIVE_LOCATION_ENTITIES).count() != 1
                || activeSnapshots.stream().filter(snapshot ->
                        snapshot.kind == ACTIVE_LOCATION_TOKENS).count() != 1
                || activeSnapshots.stream().filter(snapshot ->
                        snapshot.kind == ACTIVE_ENGAGEMENT_ENTITIES).count() != 1) return null;
        rewriteLocationSnapshots(paused, pausedSnapshots);
        rewriteLocationSnapshots(active, activeSnapshots);
        CampaignEntityMaintenanceRuntime.pausedLocationSnapshotsInstalled();
        CampaignEntityMaintenanceRuntime.activeLocationSnapshotsInstalled();
        return write(owner);
    }

    private static void rewriteLocationSnapshots(
            MethodNode method, List<LocationSnapshot> snapshots) {
        for (LocationSnapshot snapshot : snapshots) {
            method.instructions.remove(snapshot.allocation);
            method.instructions.remove(snapshot.duplicate);
            method.instructions.insertBefore(snapshot.constructor, new LdcInsnNode(snapshot.kind));
            method.instructions.insertBefore(snapshot.constructor, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "locationSnapshot",
                    "(Ljava/util/List;I)[Ljava/lang/Object;", false));
            method.instructions.remove(snapshot.constructor);
            for (MethodInsnNode iterator : snapshot.iterators) {
                method.instructions.insertBefore(iterator, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, RUNTIME, "locationSnapshotIterator",
                        "([Ljava/lang/Object;)Ljava/util/Iterator;", false));
                method.instructions.remove(iterator);
            }
        }
    }

    private static List<LocationSnapshot> pausedLocationSnapshots(MethodNode method) {
        List<LocationSnapshot> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"java/util/ArrayList".equals(constructor.owner)
                    || !"<init>".equals(constructor.name)
                    || !"(Ljava/util/Collection;)V".equals(constructor.desc)) continue;
            AbstractInsnNode producer = previousMeaningful(constructor);
            AbstractInsnNode duplicate;
            AbstractInsnNode allocation;
            int kind;
            int local;
            int expectedIterators;
            if (producer instanceof MethodInsnNode list
                    && list.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "com/fs/util/container/repo/ObjectRepository".equals(list.owner)
                    && "getList".equals(list.name)
                    && "(Ljava/lang/Class;)Ljava/util/List;".equals(list.desc)) {
                AbstractInsnNode entityType = previousMeaningful(producer);
                AbstractInsnNode objects = previousMeaningful(entityType);
                AbstractInsnNode loadThis = previousMeaningful(objects);
                duplicate = previousMeaningful(loadThis);
                allocation = previousMeaningful(duplicate);
                if (!(entityType instanceof LdcInsnNode constant)
                        || !(constant.cst instanceof Type type)
                        || !"com/fs/starfarer/campaign/CampaignEntity".equals(type.getInternalName())
                        || !(objects instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD
                        || !LOCATION_CLASS.equals(field.owner) || !"objects".equals(field.name)
                        || !"Lcom/fs/util/container/repo/ObjectRepository;".equals(field.desc)
                        || !(loadThis instanceof VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return List.of();
                kind = PAUSED_LOCATION_ENTITIES;
                local = 3;
                expectedIterators = 2;
            } else if (producer instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && LOCATION_CLASS.equals(field.owner) && "scripts".equals(field.name)
                    && "Ljava/util/List;".equals(field.desc)) {
                AbstractInsnNode loadThis = previousMeaningful(producer);
                duplicate = previousMeaningful(loadThis);
                allocation = previousMeaningful(duplicate);
                if (!(loadThis instanceof VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return List.of();
                kind = PAUSED_LOCATION_SCRIPTS;
                local = 4;
                expectedIterators = 1;
            } else {
                return List.of();
            }
            AbstractInsnNode store = nextMeaningful(constructor);
            if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                    || !(allocation instanceof TypeInsnNode type)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !"java/util/ArrayList".equals(type.desc)
                    || !(store instanceof VarInsnNode variable)
                    || store.getOpcode() != Opcodes.ASTORE || variable.var != local) return List.of();
            List<MethodInsnNode> iterators = new ArrayList<>();
            for (AbstractInsnNode candidate = store.getNext(); candidate != null;
                    candidate = candidate.getNext()) {
                if (candidate instanceof VarInsnNode overwrite
                        && overwrite.getOpcode() == Opcodes.ASTORE
                        && overwrite.var == local) break;
                if (!(candidate instanceof MethodInsnNode iterator)
                        || iterator.getOpcode() != Opcodes.INVOKEINTERFACE
                        || !"java/util/List".equals(iterator.owner)
                        || !"iterator".equals(iterator.name)
                        || !"()Ljava/util/Iterator;".equals(iterator.desc)) continue;
                AbstractInsnNode receiver = previousMeaningful(iterator);
                if (receiver instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && load.var == local) iterators.add(iterator);
            }
            if (iterators.size() != expectedIterators) return List.of();
            result.add(new LocationSnapshot(
                    allocation, duplicate, constructor, List.copyOf(iterators), kind));
        }
        return result;
    }

    private static List<LocationSnapshot> activeLocationSnapshots(MethodNode method) {
        List<LocationSnapshot> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"java/util/ArrayList".equals(constructor.owner)
                    || !"<init>".equals(constructor.name)
                    || !"(Ljava/util/Collection;)V".equals(constructor.desc)) continue;
            AbstractInsnNode producer = previousMeaningful(constructor);
            AbstractInsnNode duplicate;
            AbstractInsnNode allocation;
            int kind;
            int local;
            if (producer instanceof MethodInsnNode list
                    && list.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "com/fs/util/container/repo/ObjectRepository".equals(list.owner)
                    && "getList".equals(list.name)
                    && "(Ljava/lang/Class;)Ljava/util/List;".equals(list.desc)) {
                AbstractInsnNode requestedType = previousMeaningful(producer);
                AbstractInsnNode objects = previousMeaningful(requestedType);
                AbstractInsnNode loadThis = previousMeaningful(objects);
                duplicate = previousMeaningful(loadThis);
                allocation = previousMeaningful(duplicate);
                if (!(requestedType instanceof LdcInsnNode constant)
                        || !(constant.cst instanceof Type type)
                        || !(objects instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD
                        || !LOCATION_CLASS.equals(field.owner) || !"objects".equals(field.name)
                        || !"Lcom/fs/util/container/repo/ObjectRepository;".equals(field.desc)
                        || !(loadThis instanceof VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return List.of();
                if ("com/fs/starfarer/campaign/CampaignEntity".equals(type.getInternalName())) {
                    kind = ACTIVE_LOCATION_ENTITIES;
                    local = 4;
                } else if ((LOCATION_CLASS + "$LocationToken").equals(type.getInternalName())) {
                    kind = ACTIVE_LOCATION_TOKENS;
                    local = 5;
                } else {
                    return List.of();
                }
            } else if (producer instanceof VarInsnNode source
                    && source.getOpcode() == Opcodes.ALOAD && source.var == 10) {
                duplicate = previousMeaningful(source);
                allocation = previousMeaningful(duplicate);
                AbstractInsnNode sourceStore = previousMeaningful(allocation);
                AbstractInsnNode list = previousMeaningful(sourceStore);
                AbstractInsnNode requestedType = previousMeaningful(list);
                AbstractInsnNode objects = previousMeaningful(requestedType);
                AbstractInsnNode loadThis = previousMeaningful(objects);
                if (!(sourceStore instanceof VarInsnNode store)
                        || store.getOpcode() != Opcodes.ASTORE || store.var != 10
                        || !(list instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !"com/fs/util/container/repo/ObjectRepository".equals(call.owner)
                        || !"getList".equals(call.name)
                        || !"(Ljava/lang/Class;)Ljava/util/List;".equals(call.desc)
                        || !(requestedType instanceof LdcInsnNode constant)
                        || !(constant.cst instanceof Type type)
                        || !"com/fs/starfarer/campaign/BaseCampaignEntity".equals(
                                type.getInternalName())
                        || !(objects instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD
                        || !LOCATION_CLASS.equals(field.owner) || !"objects".equals(field.name)
                        || !"Lcom/fs/util/container/repo/ObjectRepository;".equals(field.desc)
                        || !(loadThis instanceof VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return List.of();
                kind = ACTIVE_ENGAGEMENT_ENTITIES;
                local = 10;
            } else {
                return List.of();
            }
            AbstractInsnNode store = nextMeaningful(constructor);
            if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                    || !(allocation instanceof TypeInsnNode type)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !"java/util/ArrayList".equals(type.desc)
                    || !(store instanceof VarInsnNode variable)
                    || store.getOpcode() != Opcodes.ASTORE || variable.var != local) return List.of();
            List<MethodInsnNode> iterators = new ArrayList<>();
            for (AbstractInsnNode candidate = store.getNext(); candidate != null;
                    candidate = candidate.getNext()) {
                if (candidate instanceof VarInsnNode overwrite
                        && overwrite.getOpcode() == Opcodes.ASTORE
                        && overwrite.var == local) break;
                if (!(candidate instanceof MethodInsnNode iterator)
                        || iterator.getOpcode() != Opcodes.INVOKEINTERFACE
                        || !"java/util/List".equals(iterator.owner)
                        || !"iterator".equals(iterator.name)
                        || !"()Ljava/util/Iterator;".equals(iterator.desc)) continue;
                AbstractInsnNode receiver = previousMeaningful(iterator);
                if (receiver instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && load.var == local) iterators.add(iterator);
            }
            if (iterators.size() != 1) return List.of();
            result.add(new LocationSnapshot(
                    allocation, duplicate, constructor, List.copyOf(iterators), kind));
        }
        return result;
    }

    private static List<MarketSnapshot> pausedConditionSnapshots(MethodNode method) {
        List<MarketSnapshot> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"java/util/ArrayList".equals(constructor.owner)
                    || !"<init>".equals(constructor.name)
                    || !"(Ljava/util/Collection;)V".equals(constructor.desc)) continue;
            AbstractInsnNode producer = previousMeaningful(constructor);
            AbstractInsnNode loadMarket = previousMeaningful(producer);
            AbstractInsnNode duplicate = previousMeaningful(loadMarket);
            AbstractInsnNode allocation = previousMeaningful(duplicate);
            AbstractInsnNode next = nextMeaningful(constructor);
            if (!(producer instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !"com/fs/starfarer/api/campaign/econ/MarketAPI".equals(call.owner)
                    || !"getConditions".equals(call.name)
                    || !"()Ljava/util/List;".equals(call.desc)
                    || !(loadMarket instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD
                    || duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                    || !(allocation instanceof TypeInsnNode type)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !"java/util/ArrayList".equals(type.desc)
                    || !(next instanceof MethodInsnNode iterator)
                    || iterator.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !"java/util/ArrayList".equals(iterator.owner)
                    || !"iterator".equals(iterator.name)
                    || !"()Ljava/util/Iterator;".equals(iterator.desc)) return List.of();
            result.add(new MarketSnapshot(
                    allocation, duplicate, constructor, iterator, PAUSED_MARKET_CONDITIONS));
        }
        return result;
    }

    private static List<IteratorStart> iteratorStarts(
            MethodNode method,
            String fieldName,
            String fieldDescriptor,
            String iteratorOwner,
            String viewOwner) {
        List<IteratorStart> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode iterator)
                    || iterator.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !iteratorOwner.equals(iterator.owner)
                    || !"iterator".equals(iterator.name)
                    || !"()Ljava/util/Iterator;".equals(iterator.desc)) continue;
            AbstractInsnNode producer = previousMeaningful(iterator);
            if (viewOwner != null) {
                if (!(producer instanceof MethodInsnNode view)
                        || view.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !viewOwner.equals(view.owner) || !"values".equals(view.name)
                        || !"()Ljava/util/Collection;".equals(view.desc)) continue;
                producer = previousMeaningful(view);
            }
            AbstractInsnNode loadThis = previousMeaningful(producer);
            if (producer instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && MEMORY_CLASS.equals(field.owner) && fieldName.equals(field.name)
                    && fieldDescriptor.equals(field.desc)
                    && loadThis instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ALOAD && load.var == 0) {
                result.add(new IteratorStart(loadThis));
            }
        }
        return result;
    }

    private static List<MarketSnapshot> marketSnapshots(MethodNode method) {
        List<MarketSnapshot> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"java/util/ArrayList".equals(constructor.owner)
                    || !"<init>".equals(constructor.name)
                    || !"(Ljava/util/Collection;)V".equals(constructor.desc)) continue;
            AbstractInsnNode producer = previousMeaningful(constructor);
            AbstractInsnNode loadThis = previousMeaningful(producer);
            AbstractInsnNode duplicate = previousMeaningful(loadThis);
            AbstractInsnNode allocation = previousMeaningful(duplicate);
            AbstractInsnNode next = nextMeaningful(constructor);
            if (!(loadThis instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                    || load.var != 0 || duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                    || !(allocation instanceof TypeInsnNode type)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !"java/util/ArrayList".equals(type.desc)
                    || !(next instanceof MethodInsnNode iterator)
                    || iterator.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !"java/util/ArrayList".equals(iterator.owner)
                    || !"iterator".equals(iterator.name)
                    || !"()Ljava/util/Iterator;".equals(iterator.desc)) return List.of();
            int kind;
            if (producer instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && MARKET_CLASS.equals(call.owner) && "getConditions".equals(call.name)
                    && "()Ljava/util/List;".equals(call.desc)) {
                kind = MARKET_CONDITIONS;
            } else if (producer instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && MARKET_CLASS.equals(field.owner) && "industries".equals(field.name)
                    && "Ljava/util/List;".equals(field.desc)) {
                kind = MARKET_INDUSTRIES;
            } else {
                return List.of();
            }
            result.add(new MarketSnapshot(allocation, duplicate, constructor, iterator, kind));
        }
        return result;
    }

    private static boolean reviewScriptShape(MethodNode method) {
        int snapshots = 0;
        int advances = 0;
        int scriptReads = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && "java/util/ArrayList".equals(type.desc)) snapshots++;
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && ENTITY_CLASS.equals(field.owner)
                    && SCRIPTS_FIELD.equals(field.name)
                    && SCRIPTS_DESCRIPTOR.equals(field.desc)) scriptReads++;
            if (instruction instanceof MethodInsnNode call
                    && "com/fs/starfarer/api/EveryFrameScript".equals(call.owner)
                    && "advance".equals(call.name) && "(F)V".equals(call.desc)) advances++;
        }
        return snapshots == 1 && scriptReads == 3 && advances == 1;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE
                || current.getType() == AbstractInsnNode.FRAME)) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE
                || current.getType() == AbstractInsnNode.FRAME)) current = current.getNext();
        return current;
    }

    private static List<MethodInsnNode> matching(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) result.add(call);
        }
        return result;
    }

    private static int callsRuntime(MethodNode method) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) result++;
        }
        return result;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private record MarketSnapshot(
            AbstractInsnNode allocation,
            AbstractInsnNode duplicate,
            MethodInsnNode constructor,
            MethodInsnNode iterator,
            int kind) {
    }

    private record IteratorStart(AbstractInsnNode loadThis) {
    }

    private record LocationSnapshot(
            AbstractInsnNode allocation,
            AbstractInsnNode duplicate,
            MethodInsnNode constructor,
            List<MethodInsnNode> iterators,
            int kind) {
    }
}
