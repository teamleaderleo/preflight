package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Marks exact loading-screen, progress, audio, and mod-callback boundaries. */
final class StartupPhasePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/loading/ResourceLoaderState";
    static final String INIT_METHOD = "init";
    static final String INIT_DESCRIPTOR = "(Ljava/util/Map;)V";
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    private StartupPhasePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) return null;
        byte[] transformed = write(owner);
        StartupPhaseRuntime.installed();
        return transformed;
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(INIT_METHOD, INIT_DESCRIPTOR)) {
            return false;
        }
        MethodNode init = uniqueMethod(owner, INIT_METHOD, INIT_DESCRIPTOR);
        MethodNode renderProgress = uniqueMethod(owner, "renderProgress", "(F)V");
        if (init == null || calls(init, RUNTIME, "mark", "(Ljava/lang/String;)V").size() > 0) {
            return false;
        }

        MethodInsnNode renderBackground = uniqueCall(init,
                TARGET_CLASS, "renderBg", "()V");
        MethodInsnNode resourceManifest = uniqueCall(init,
                "com/fs/starfarer/settings/StarfarerSettings",
                "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V");
        List<MethodInsnNode> scriptStages = calls(init,
                "com/fs/starfarer/loading/scripts/ScriptStore", "()V");
        MethodInsnNode scriptDiscovery = stage(scriptStages, 0);
        MethodInsnNode scriptCompile = stage(scriptStages, 1);
        MethodInsnNode scriptPrime = stage(scriptStages, 2);
        MethodInsnNode scripts = stage(scriptStages, 3);
        MethodInsnNode specStore = uniqueCall(init,
                "com/fs/starfarer/loading/SpecStore",
                "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V");
        MethodInsnNode shutdown = uniqueCall(init, "java/util/concurrent/ExecutorService",
                "shutdown", "()V");
        MethodInsnNode await = uniqueCall(init, "java/util/concurrent/ExecutorService",
                "awaitTermination", "(JLjava/util/concurrent/TimeUnit;)Z");
        MethodInsnNode graphicsFinalize = previousOpcode(scripts) instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && "com/fs/graphics/L".equals(call.owner)
                        && "()V".equals(call.desc)
                ? call : null;
        MethodInsnNode enabledPlugins = uniqueCall(init,
                "com/fs/starfarer/launcher/ModManager", "getEnabledModPlugins", "()Ljava/util/List;");
        MethodInsnNode pluginCallback = uniqueCall(init,
                "com/fs/starfarer/api/ModPlugin", "onApplicationLoad", "()V");
        AbstractInsnNode onlyReturn = uniqueOpcode(init, Opcodes.RETURN);
        MethodInsnNode firstProgress = calls(init, TARGET_CLASS, "renderProgress", "(F)V")
                .stream().findFirst().orElse(null);
        MethodInsnNode postSpecProgress = firstCallAfter(
                specStore, TARGET_CLASS, "renderProgress", "(F)V");
        MethodInsnNode shipWeaponSpriteQueue = uniqueCall(
                init, TARGET_CLASS, "queueShipAndWeaponSprites", "()V");
        MethodInsnNode postQueueProgress = firstCallAfter(
                shipWeaponSpriteQueue, TARGET_CLASS, "renderProgress", "(F)V");
        MethodInsnNode resourceExecutor = uniqueCall(
                init, "java/util/concurrent/Executors", "newFixedThreadPool",
                "(I)Ljava/util/concurrent/ExecutorService;");
        MethodInsnNode resourceBatches = firstCallAfter(
                resourceExecutor, "java/util/List", "iterator", "()Ljava/util/Iterator;");
        MethodInsnNode resourceNext = firstCallAfter(
                resourceBatches, "java/util/Iterator", "next", "()Ljava/lang/Object;");
        VarInsnNode resourceStore = resourceStoreAfter(resourceNext);
        String resourceClass = TARGET_CLASS + "$Oo";
        FieldInsnNode resourceType = firstFieldAfter(
                resourceStore, resourceClass, "L" + TARGET_CLASS + "$o;");
        FieldInsnNode resourcePath = firstFieldAfter(
                resourceStore, resourceClass, "Ljava/lang/String;");
        MethodInsnNode resourceDedupAdd = firstCallAfter(
                resourceNext, "java/util/Set", "add", "(Ljava/lang/Object;)Z");
        AbstractInsnNode resourceStartAnchor = nextOpcode(resourceDedupAdd);
        FieldInsnNode resourceWeight = firstFieldAfter(
                resourceDedupAdd, resourceClass, "I");
        MethodInsnNode titleData = previousCall(firstProgress == null ? specStore : firstProgress);
        if (titleData != null && (titleData.getOpcode() != Opcodes.INVOKESTATIC
                || !titleData.owner.startsWith("com/fs/starfarer/title/")
                || !titleData.desc.startsWith("()L"))) {
            titleData = null;
        }
        JumpInsnNode awaitRetry = nextOpcode(await) instanceof JumpInsnNode jump
                && jump.getOpcode() == Opcodes.IFEQ ? jump : null;
        JumpInsnNode pluginLoop = pluginCallback == null ? null : pluginLoopJump(pluginCallback);
        AbstractInsnNode modStart = previousOpcode(enabledPlugins);
        if (!(modStart instanceof MethodInsnNode getInstance)
                || !"com/fs/starfarer/launcher/ModManager".equals(getInstance.owner)
                || !"getInstance".equals(getInstance.name)
                || !"()Lcom/fs/starfarer/launcher/ModManager;".equals(getInstance.desc)
                || renderProgress == null || renderBackground == null || resourceManifest == null
                || scriptDiscovery == null || scriptCompile == null || scriptPrime == null
                || titleData == null || specStore == null
                || postSpecProgress == null || shipWeaponSpriteQueue == null
                || postQueueProgress == null || resourceExecutor == null || resourceBatches == null
                || resourceNext == null || resourceStore == null || resourceType == null
                || resourcePath == null || resourceDedupAdd == null
                || resourceStartAnchor == null || resourceStartAnchor.getOpcode() != Opcodes.POP
                || resourceWeight == null
                || !comesBefore(specStore, postSpecProgress)
                || !comesBefore(postSpecProgress, shipWeaponSpriteQueue)
                || !comesBefore(shipWeaponSpriteQueue, postQueueProgress)
                || !comesBefore(postQueueProgress, resourceExecutor)
                || !comesBefore(resourceExecutor, resourceBatches)
                || shutdown == null || awaitRetry == null
                || graphicsFinalize == null || scripts == null
                || pluginCallback == null || pluginLoop == null || onlyReturn == null) {
            return false;
        }

        init.instructions.insertBefore(init.instructions.getFirst(), mark("resource-init-enter"));
        init.instructions.insert(renderBackground, mark("loading-screen-ready"));
        init.instructions.insertBefore(resourceManifest, mark("resource-manifest-start"));
        init.instructions.insert(resourceManifest, mark("resource-manifest-complete"));
        init.instructions.insertBefore(scriptDiscovery, mark("script-discovery-start"));
        init.instructions.insert(scriptDiscovery, mark("script-discovery-core-complete"));
        init.instructions.insertBefore(scriptCompile, mark("script-plugin-registration-complete"));
        init.instructions.insert(scriptCompile, mark("script-compile-complete"));
        init.instructions.insert(scriptPrime, mark("script-store-prime-complete"));
        init.instructions.insert(titleData, mark("pre-progress-data-complete"));
        init.instructions.insertBefore(specStore, mark("spec-store-start"));
        init.instructions.insert(specStore, mark("spec-store-complete"));
        init.instructions.insertBefore(postSpecProgress, mark("post-spec-progress-start"));
        init.instructions.insert(postSpecProgress, mark("post-spec-progress-complete"));
        init.instructions.insertBefore(shipWeaponSpriteQueue, mark("ship-weapon-sprite-queue-start"));
        init.instructions.insert(shipWeaponSpriteQueue, mark("ship-weapon-sprite-queue-complete"));
        init.instructions.insertBefore(postQueueProgress, mark("post-queue-progress-start"));
        init.instructions.insert(postQueueProgress, marks(
                "post-queue-progress-complete", "resource-ordering-start"));
        init.instructions.insertBefore(resourceExecutor, marks(
                "resource-ordering-complete", "resource-executor-start"));
        init.instructions.insert(resourceExecutor, mark("resource-executor-complete"));
        init.instructions.insertBefore(resourceBatches, mark("resource-batches-start"));
        boolean textureThreadCpu = Boolean.getBoolean(
                StartupPhaseRuntime.TEXTURE_THREAD_CPU_PROPERTY);
        int resourceStartedLocal = init.maxLocals;
        int resourceThreadCpuStartedLocal = resourceStartedLocal + 2;
        init.maxLocals += textureThreadCpu ? 4 : 2;
        InsnList resourceStart = new InsnList();
        resourceStart.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "hotCallStart", "()J", false));
        resourceStart.add(new VarInsnNode(Opcodes.LSTORE, resourceStartedLocal));
        if (textureThreadCpu) {
            resourceStart.add(new VarInsnNode(Opcodes.ALOAD, resourceStore.var));
            resourceStart.add(new FieldInsnNode(
                    Opcodes.GETFIELD, resourceType.owner, resourceType.name, resourceType.desc));
            resourceStart.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    RUNTIME,
                    "textureThreadCpuStart",
                    "(Ljava/lang/Object;)J",
                    false));
            resourceStart.add(new VarInsnNode(Opcodes.LSTORE, resourceThreadCpuStartedLocal));
        }
        init.instructions.insert(resourceStartAnchor, resourceStart);
        InsnList resourceEnd = new InsnList();
        resourceEnd.add(new VarInsnNode(Opcodes.ALOAD, resourceStore.var));
        resourceEnd.add(new FieldInsnNode(
                Opcodes.GETFIELD, resourceType.owner, resourceType.name, resourceType.desc));
        resourceEnd.add(new VarInsnNode(Opcodes.ALOAD, resourceStore.var));
        resourceEnd.add(new FieldInsnNode(
                Opcodes.GETFIELD, resourcePath.owner, resourcePath.name, resourcePath.desc));
        resourceEnd.add(new VarInsnNode(Opcodes.ALOAD, resourceStore.var));
        resourceEnd.add(new FieldInsnNode(
                Opcodes.GETFIELD, resourceWeight.owner, resourceWeight.name, resourceWeight.desc));
        resourceEnd.add(new VarInsnNode(Opcodes.LLOAD, resourceStartedLocal));
        if (textureThreadCpu) {
            resourceEnd.add(new VarInsnNode(Opcodes.LLOAD, resourceThreadCpuStartedLocal));
        }
        resourceEnd.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "resourceLoadEnd",
                textureThreadCpu
                        ? "(Ljava/lang/Object;Ljava/lang/String;IJJ)V"
                        : "(Ljava/lang/Object;Ljava/lang/String;IJ)V",
                false));
        init.instructions.insertBefore(resourceWeight, resourceEnd);
        init.instructions.insertBefore(shutdown, mark("progress-100"));
        init.instructions.insert(awaitRetry, mark("audio-workers-complete"));
        init.instructions.insert(graphicsFinalize, mark("graphics-finalize-complete"));
        init.instructions.insertBefore(scripts, mark("script-store-start"));
        init.instructions.insert(scripts, mark("script-store-complete"));
        init.instructions.insertBefore(modStart, mark("mod-callbacks-start"));

        InsnList pluginStart = new InsnList();
        pluginStart.add(new InsnNode(Opcodes.DUP));
        pluginStart.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "pluginStart", "(Ljava/lang/Object;)V", false));
        init.instructions.insertBefore(pluginCallback, pluginStart);
        init.instructions.insert(pluginCallback, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "pluginEnd", "()V", false));
        init.instructions.insert(pluginLoop, mark("mod-callbacks-complete"));
        init.instructions.insertBefore(onlyReturn, mark("resource-init-complete"));

        InsnList progress = new InsnList();
        progress.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.FLOAD, 1));
        progress.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "progress", "(F)V", false));
        renderProgress.instructions.insertBefore(renderProgress.instructions.getFirst(), progress);

        return true;
    }

    static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static InsnList mark(String name) {
        InsnList instructions = new InsnList();
        instructions.add(new org.objectweb.asm.tree.LdcInsnNode(name));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "mark", "(Ljava/lang/String;)V", false));
        return instructions;
    }

    private static InsnList marks(String... names) {
        InsnList instructions = new InsnList();
        for (String name : names) {
            instructions.add(mark(name));
        }
        return instructions;
    }

    private static JumpInsnNode pluginLoopJump(MethodInsnNode pluginCallback) {
        for (AbstractInsnNode cursor = pluginCallback.getNext(); cursor != null; cursor = cursor.getNext()) {
            if (cursor instanceof MethodInsnNode call
                    && "java/util/Iterator".equals(call.owner)
                    && "hasNext".equals(call.name)
                    && "()Z".equals(call.desc)) {
                AbstractInsnNode next = nextOpcode(call);
                return next instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE
                        ? jump : null;
            }
        }
        return null;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> matches = calls(method, owner, name, descriptor);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String descriptor) {
        List<MethodInsnNode> matches = calls(method, owner, descriptor);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static MethodInsnNode firstCallAfter(
            AbstractInsnNode start, String owner, String name, String descriptor) {
        for (AbstractInsnNode cursor = start == null ? null : start.getNext();
                cursor != null; cursor = cursor.getNext()) {
            if (cursor instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    private static boolean comesBefore(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == second) return true;
        }
        return false;
    }

    private static VarInsnNode resourceStoreAfter(MethodInsnNode resourceNext) {
        AbstractInsnNode cast = nextOpcode(resourceNext);
        AbstractInsnNode store = nextOpcode(cast);
        if (!(cast instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.CHECKCAST
                || !(store instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ASTORE
                || !(TARGET_CLASS + "$Oo").equals(type.desc)) {
            return null;
        }
        return variable;
    }

    private static FieldInsnNode firstFieldAfter(
            AbstractInsnNode start, String owner, String descriptor) {
        for (AbstractInsnNode cursor = start == null ? null : start.getNext();
                cursor != null; cursor = cursor.getNext()) {
            if (cursor instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && owner.equals(field.owner) && descriptor.equals(field.desc)) {
                return field;
            }
        }
        return null;
    }

    private static MethodInsnNode stage(List<MethodInsnNode> stages, int index) {
        return stages.size() == 4 ? stages.get(index) : null;
    }

    private static MethodInsnNode previousCall(AbstractInsnNode instruction) {
        for (AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
                cursor != null; cursor = cursor.getPrevious()) {
            if (cursor instanceof MethodInsnNode call) {
                return call;
            }
        }
        return null;
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        return matches;
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String descriptor) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        return matches;
    }

    private static AbstractInsnNode uniqueOpcode(MethodNode method, int opcode) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == opcode) {
                if (found != null) return null;
                found = instruction;
            }
        }
        return found;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }
}
