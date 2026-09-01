package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Gives {@code getCommandClass} the answer its package walk was going to reach.
 *
 * <p>The reviewed method is, in full:
 *
 * <pre>{@code
 * if (memo.containsKey(name)) return memo.get(name);
 * String tried = "";
 * for (String pkg : ruleCommandPackages) {
 *     tried = tried + pkg + "\n";
 *     String fqn = pkg + "." + name;
 *     try {
 *         Class.forName(fqn, false, ScriptStore.loader().getParent()).newInstance();
 *     } catch (Exception ignored) { continue; }
 *     memo.put(name, fqn);
 *     return fqn;
 * }
 * throw new RuntimeException("Command [" + name + "] not found in packages:\n" + tried);
 * }</pre>
 *
 * <p>41 packages are declared on the measured profile and 671 distinct names reach the walk, for
 * 641ms of failed {@code Class.forName} calls against the modded classpath.
 *
 * <h2>Three insertions, all inside this one method</h2>
 *
 * <ol>
 *   <li><b>At the walk's first instruction</b> -- the branch target of the memo miss, so it cannot
 *       run on the 25,091 calls that hit the memo -- ask the runtime for the winning package. On an
 *       answer the inserted code performs vanilla's own {@code memo.put} and returns; on {@code null}
 *       it falls through into the untouched walk.
 *   <li><b>At the top of the catch handler</b>, report which package failed and with what. This is
 *       the only way to learn whether skipping the walk would skip a side effect: {@code forName} is
 *       called with {@code initialize = false}, but {@code newInstance()} initialises, so a package
 *       ahead of the winner holding a same-named class that loads and then fails to instantiate has
 *       already run its static initialiser. Only names whose every failure was a
 *       {@code ClassNotFoundException} are ever recorded.
 *   <li><b>After vanilla's {@code memo.put}</b>, report the winner.
 * </ol>
 *
 * <p>Insertions 2 and 3 are what make a cold run a learning run; they cost two calls per walk and
 * nothing at all once the walk stops happening.
 *
 * <h2>Nothing here is matched by name</h2>
 *
 * <p>Every anchor is found by shape: the unique {@code Map.containsKey}, the {@code IFEQ} that
 * follows it, the unique {@code List.iterator}, the unique {@code Class.forName} and
 * {@code newInstance}, the unique {@code Map.put}, and the single {@code java/lang/Exception}
 * handler. The two static fields and the two instructions that fetch the class loader are read out
 * of the method rather than declared here, so the obfuscated names of the memo map, the package
 * list, and {@code ScriptStore}'s accessor can all change without touching this file. Anything that
 * does not match exactly declines and the class is left alone.
 */
final class RuleCommandClassCachePlan {
    static final String TARGET_CLASS = RuleExpressionPhasePlan.TARGET_CLASS;
    static final String LOOKUP_METHOD = "getCommandClass";
    static final String LOOKUP_DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/RuleCommandClassCacheRuntime";
    private static final String SHORTCUT_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/util/List;Ljava/lang/ClassLoader;)Ljava/lang/String;";
    private static final String NOTE_FAILURE_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V";
    private static final String NOTE_WINNER_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

    private RuleCommandClassCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!RuleExpressionPhasePlan.isTarget(signature.internalName())
                || !signature.hasMethod(LOOKUP_METHOD, LOOKUP_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode lookup = uniqueMethod(owner, LOOKUP_METHOD, LOOKUP_DESCRIPTOR);
        if (lookup == null
                || (lookup.access & Opcodes.ACC_STATIC) == 0
                || hasRuntimeCalls(lookup)) {
            return null;
        }

        Anchors anchors = Anchors.of(lookup);
        if (anchors == null) {
            return null;
        }

        int resolvedLocal = lookup.maxLocals++;
        LabelNode vanilla = new LabelNode();

        InsnList shortcut = new InsnList();
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 0));
        shortcut.add(clone(anchors.packagesField));
        shortcut.add(clone(anchors.loaderAccessor));
        shortcut.add(clone(anchors.loaderParent));
        shortcut.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "shortcut", SHORTCUT_DESCRIPTOR, false));
        shortcut.add(new InsnNode(Opcodes.DUP));
        shortcut.add(new JumpInsnNode(Opcodes.IFNULL, vanilla));
        shortcut.add(new VarInsnNode(Opcodes.ASTORE, resolvedLocal));
        // Vanilla's own memoisation, in vanilla's own instructions: without it the next call for
        // this name would shortcut again and instantiate the command class a second time.
        shortcut.add(clone(anchors.memoField));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 0));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, resolvedLocal));
        shortcut.add(clone(anchors.put));
        shortcut.add(new InsnNode(Opcodes.POP));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, resolvedLocal));
        shortcut.add(new InsnNode(Opcodes.ARETURN));
        shortcut.add(vanilla);
        shortcut.add(new InsnNode(Opcodes.POP));
        lookup.instructions.insert(anchors.walkStart, shortcut);

        InsnList failure = new InsnList();
        failure.add(new VarInsnNode(Opcodes.ALOAD, 0));
        failure.add(new VarInsnNode(Opcodes.ALOAD, anchors.packageLocal));
        failure.add(new VarInsnNode(Opcodes.ALOAD, anchors.exceptionLocal));
        failure.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "noteFailure", NOTE_FAILURE_DESCRIPTOR, false));
        lookup.instructions.insert(anchors.exceptionStore, failure);

        InsnList winner = new InsnList();
        winner.add(new VarInsnNode(Opcodes.ALOAD, 0));
        winner.add(new VarInsnNode(Opcodes.ALOAD, anchors.packageLocal));
        winner.add(new VarInsnNode(Opcodes.ALOAD, anchors.resolvedLocal));
        winner.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "noteWinner", NOTE_WINNER_DESCRIPTOR, false));
        lookup.instructions.insert(anchors.putDiscard, winner);

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Publishes what the learning run observed, before the rules loader returns.
     *
     * <p>Same anchor the merged-CSV cache writes from, and for the same reason: every command name
     * the profile uses has been resolved by then, and a method that has reached its {@code RETURN}
     * has not failed.
     */
    static byte[] transformLoader(ClassSignature signature, byte[] originalBytes) {
        String loadMethod = RulesLoaderPhasePlan.loadMethod(signature);
        if (!RulesLoaderPhasePlan.TARGET_CLASS.equals(signature.internalName())
                || loadMethod == null) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode load = uniqueMethod(
                owner, loadMethod, RulesLoaderPhasePlan.LOAD_DESCRIPTOR);
        if (load == null || hasRuntimeCalls(load)) {
            return null;
        }
        AbstractInsnNode onlyReturn = uniqueReturn(load);
        if (onlyReturn == null) {
            return null;
        }
        load.instructions.insertBefore(onlyReturn, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "complete", "()V", false));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    /** Everything the rewrite needs, all of it read out of the method's own shape. */
    private record Anchors(
            FieldInsnNode memoField,
            FieldInsnNode packagesField,
            MethodInsnNode loaderAccessor,
            MethodInsnNode loaderParent,
            MethodInsnNode put,
            AbstractInsnNode walkStart,
            AbstractInsnNode exceptionStore,
            AbstractInsnNode putDiscard,
            int packageLocal,
            int exceptionLocal,
            int resolvedLocal) {

        static Anchors of(MethodNode method) {
            MethodInsnNode containsKey = unique(method, Opcodes.INVOKEINTERFACE,
                    "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z");
            MethodInsnNode iterator = unique(method, Opcodes.INVOKEINTERFACE,
                    "java/util/List", "iterator", "()Ljava/util/Iterator;");
            MethodInsnNode next = unique(method, Opcodes.INVOKEINTERFACE,
                    "java/util/Iterator", "next", "()Ljava/lang/Object;");
            MethodInsnNode forName = unique(method, Opcodes.INVOKESTATIC,
                    "java/lang/Class", "forName",
                    "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
            MethodInsnNode newInstance = unique(method, Opcodes.INVOKEVIRTUAL,
                    "java/lang/Class", "newInstance", "()Ljava/lang/Object;");
            MethodInsnNode put = unique(method, Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
            if (containsKey == null || iterator == null || next == null
                    || forName == null || newInstance == null || put == null) {
                return null;
            }

            // containsKey takes one argument and iterator takes none, so the receiver sits a
            // different distance back in each case.
            FieldInsnNode memoField = staticReceiver(containsKey, 1);
            FieldInsnNode packagesField = staticReceiver(iterator, 0);
            if (memoField == null || packagesField == null
                    || memoField.name.equals(packagesField.name)) {
                return null;
            }

            // The walk begins where the memo miss jumps to, which is what keeps the shortcut off
            // the 25,091 calls a launch that never leave the map.
            AbstractInsnNode afterContains = nextReal(containsKey);
            if (!(afterContains instanceof JumpInsnNode miss) || miss.getOpcode() != Opcodes.IFEQ) {
                return null;
            }

            // The two instructions vanilla uses to reach the loader, cloned rather than named.
            AbstractInsnNode parent = previousReal(forName);
            if (!(parent instanceof MethodInsnNode loaderParent)
                    || loaderParent.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !"()Ljava/lang/ClassLoader;".equals(loaderParent.desc)) {
                return null;
            }
            AbstractInsnNode accessor = previousReal(loaderParent);
            if (!(accessor instanceof MethodInsnNode loaderAccessor)
                    || loaderAccessor.getOpcode() != Opcodes.INVOKESTATIC
                    || !loaderAccessor.desc.startsWith("()L")) {
                return null;
            }

            // The loop variable holding the candidate package.
            AbstractInsnNode cast = nextReal(next);
            if (cast == null || cast.getOpcode() != Opcodes.CHECKCAST) {
                return null;
            }
            AbstractInsnNode packageStore = nextReal(cast);
            if (!(packageStore instanceof VarInsnNode storedPackage)
                    || storedPackage.getOpcode() != Opcodes.ASTORE) {
                return null;
            }

            // Exactly one handler, catching exactly what vanilla catches.
            if (method.tryCatchBlocks == null || method.tryCatchBlocks.size() != 1) {
                return null;
            }
            TryCatchBlockNode handler = method.tryCatchBlocks.get(0);
            if (!"java/lang/Exception".equals(handler.type)) {
                return null;
            }
            AbstractInsnNode caught = nextReal(handler.handler);
            if (!(caught instanceof VarInsnNode storedException)
                    || storedException.getOpcode() != Opcodes.ASTORE) {
                return null;
            }

            // The winning path: put, discard its previous value, reload the name, return it.
            AbstractInsnNode discard = nextReal(put);
            if (discard == null || discard.getOpcode() != Opcodes.POP) {
                return null;
            }
            AbstractInsnNode reload = nextReal(discard);
            if (!(reload instanceof VarInsnNode resolved)
                    || resolved.getOpcode() != Opcodes.ALOAD) {
                return null;
            }
            AbstractInsnNode returned = nextReal(reload);
            if (returned == null || returned.getOpcode() != Opcodes.ARETURN) {
                return null;
            }
            if (count(method, Opcodes.ARETURN) != 2) {
                return null;
            }

            return new Anchors(
                    memoField,
                    packagesField,
                    loaderAccessor,
                    loaderParent,
                    put,
                    miss.label,
                    storedException,
                    discard,
                    storedPackage.var,
                    storedException.var,
                    resolved.var);
        }

        /** The static field whose value is the receiver of {@code use}, pushed before its arguments. */
        private static FieldInsnNode staticReceiver(AbstractInsnNode use, int arguments) {
            AbstractInsnNode receiver = use;
            for (int step = 0; step <= arguments && receiver != null; step++) {
                receiver = previousReal(receiver);
            }
            return receiver instanceof FieldInsnNode field && receiver.getOpcode() == Opcodes.GETSTATIC
                    ? field
                    : null;
        }
    }

    private static FieldInsnNode clone(FieldInsnNode field) {
        return new FieldInsnNode(field.getOpcode(), field.owner, field.name, field.desc);
    }

    private static MethodInsnNode clone(MethodInsnNode call) {
        return new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode from) {
        AbstractInsnNode instruction = from == null ? null : from.getNext();
        while (isMetadata(instruction)) {
            instruction = instruction.getNext();
        }
        return instruction;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode from) {
        AbstractInsnNode instruction = from == null ? null : from.getPrevious();
        while (isMetadata(instruction)) {
            instruction = instruction.getPrevious();
        }
        return instruction;
    }

    private static boolean isMetadata(AbstractInsnNode instruction) {
        return instruction instanceof LabelNode
                || instruction instanceof LineNumberNode
                || instruction instanceof FrameNode;
    }

    private static int count(MethodNode method, int opcode) {
        int found = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == opcode) {
                found++;
            }
        }
        return found;
    }

    private static MethodInsnNode unique(
            MethodNode method, int opcode, String owner, String name, String descriptor) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                if (found != null) {
                    return null;
                }
                found = call;
            }
        }
        return found;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (found != null) {
                    return null;
                }
                found = instruction;
            }
        }
        return found;
    }

    private static boolean hasRuntimeCalls(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }
}
