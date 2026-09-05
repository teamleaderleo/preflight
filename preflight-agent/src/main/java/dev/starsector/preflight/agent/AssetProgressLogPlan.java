package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes only exact, reviewed per-file INFO progress messages from vanilla spec loaders. */
final class AssetProgressLogPlan {
    static final String SCRIPT_WORKER = "com/fs/starfarer/loading/scripts/ScriptStore$3";
    static final String SCRIPT_LOADED_SUFFIX = "] already loaded (perhaps from jar file, or due to a reference from another class), skipping compilation.";
    private static final String LOGGER = "org/apache/log4j/Logger";
    private static final String STRING_BUILDER = "java/lang/StringBuilder";
    private static final String APPEND_DESCRIPTOR =
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;";
    private static final Set<String> WEAPON_PREFIXES = Set.of(
            "Loading projectile [",
            "Loading ship system projectile [",
            "Loading weapon [",
            "Loading ship system-specific weapon [");
    private static final Set<String> HULL_PREFIXES = Set.of("Loading ship hull [");
    private static final Set<String> VARIANT_PREFIXES = Set.of("Loading variant [");

    private AssetProgressLogPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) {
            return null;
        }
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        Expected expected = expected(signature.internalName());
        if (expected == null || !expected.methodsPresent(signature)) {
            return false;
        }
        List<Block> blocks = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            if (!expected.method(method)) {
                continue;
            }
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof LdcInsnNode constant
                        && constant.cst instanceof String prefix
                        && expected.prefixes.contains(prefix)) {
                    Block block = exactBlock(method, constant,
                            SCRIPT_WORKER.equals(signature.internalName()) ? SCRIPT_LOADED_SUFFIX : "]");
                    if (block == null) {
                        return false;
                    }
                    blocks.add(block);
                }
            }
        }
        if (blocks.size() != expected.count) {
            return false;
        }
        for (Block block : blocks) {
            AbstractInsnNode cursor = block.first;
            while (true) {
                AbstractInsnNode next = cursor.getNext();
                block.method.instructions.remove(cursor);
                if (cursor == block.last) {
                    break;
                }
                cursor = next;
            }
        }
        return true;
    }

    static byte[] windowsRules(ClassSignature signature, byte[] bytes) {
        if (!"72f0925d83ff48bfa2c4b8d2f691b10935d4567dc6ab1e12392a2ee388539df9".equals(signature.sha256())) {
            return null;
        }
        ClassNode owner = new ClassNode();
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        List<AbstractInsnNode> selected = null;
        MethodNode selectedMethod = null;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof LdcInsnNode text) || !"Loading rule: ".equals(text.cst)) continue;
                if (selected != null) return null;
                AbstractInsnNode first = previousOpcode(previousOpcode(previousOpcode(text)));
                List<AbstractInsnNode> block = new ArrayList<>();
                for (int i = 0; i < 9 && first != null; i++, first = nextOpcode(first)) block.add(first);
                if (block.size() != 9
                        || !(block.get(0) instanceof FieldInsnNode logger)
                        || logger.getOpcode() != Opcodes.GETSTATIC
                        || !logger.owner.equals(RulesLoaderPhasePlan.TARGET_CLASS)
                        || !logger.desc.equals("L" + LOGGER + ";")
                        || !(block.get(1) instanceof TypeInsnNode allocation)
                        || allocation.getOpcode() != Opcodes.NEW || !allocation.desc.equals(STRING_BUILDER)
                        || block.get(2).getOpcode() != Opcodes.DUP || block.get(3) != text
                        || !constructor(block.get(4))
                        || !(block.get(5) instanceof VarInsnNode id) || id.getOpcode() != Opcodes.ALOAD
                        || id.var != 7 || !append(block.get(6)) || !toStringCall(block.get(7))
                        || !infoCall(block.get(8))) return null;
                selected = block;
                selectedMethod = method;
            }
        }
        if (selected == null) return null;
        for (AbstractInsnNode instruction : selected) selectedMethod.instructions.remove(instruction);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static Block exactBlock(MethodNode method, LdcInsnNode prefix, String suffix) {
        List<AbstractInsnNode> sequence = new ArrayList<>();
        AbstractInsnNode cursor = prefix;
        for (int index = 0; index < 3; index++) {
            cursor = previousOpcode(cursor);
            if (cursor == null) return null;
            sequence.add(0, cursor);
        }
        cursor = prefix;
        for (int index = 0; index < 7; index++) {
            cursor = nextOpcode(cursor);
            if (cursor == null) return null;
            sequence.add(cursor);
        }
        // GETSTATIC Logger; NEW StringBuilder; DUP; LDC prefix; invokespecial constructor;
        // ALOAD path; append; LDC "]"; append; toString; Logger.info.
        if (sequence.size() != 10
                || !(sequence.get(0) instanceof FieldInsnNode logger)
                || logger.getOpcode() != Opcodes.GETSTATIC
                || !("L" + LOGGER + ";").equals(logger.desc)
                || !(sequence.get(1) instanceof TypeInsnNode allocation)
                || allocation.getOpcode() != Opcodes.NEW
                || !STRING_BUILDER.equals(allocation.desc)
                || sequence.get(2).getOpcode() != Opcodes.DUP
                || !constructor(sequence.get(3))
                || !(sequence.get(4) instanceof VarInsnNode path)
                || path.getOpcode() != Opcodes.ALOAD
                || !append(sequence.get(5))
                || !(sequence.get(6) instanceof LdcInsnNode close) || !suffix.equals(close.cst)
                || !append(sequence.get(7))
                || !toStringCall(sequence.get(8))
                || !infoCall(sequence.get(9))) {
            return null;
        }
        return new Block(method, sequence.get(0), sequence.get(9));
    }

    private static boolean constructor(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESPECIAL
                && STRING_BUILDER.equals(call.owner) && "<init>".equals(call.name)
                && "(Ljava/lang/String;)V".equals(call.desc);
    }

    private static boolean append(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && STRING_BUILDER.equals(call.owner) && "append".equals(call.name)
                && APPEND_DESCRIPTOR.equals(call.desc);
    }

    private static boolean toStringCall(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && STRING_BUILDER.equals(call.owner) && "toString".equals(call.name)
                && "()Ljava/lang/String;".equals(call.desc);
    }

    private static boolean infoCall(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && LOGGER.equals(call.owner) && "info".equals(call.name)
                && "(Ljava/lang/Object;)V".equals(call.desc);
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        for (AbstractInsnNode cursor = instruction.getPrevious(); cursor != null;
                cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        for (AbstractInsnNode cursor = instruction.getNext(); cursor != null;
                cursor = cursor.getNext()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private static Expected expected(String className) {
        if (SCRIPT_WORKER.equals(className)) {
            return new Expected(Set.of("Class ["), 1, Set.of(new MethodKey("run", "()V")));
        }
        if (WeaponLoaderPhasePlan.TARGET_CLASS.equals(className)) {
            return new Expected(WEAPON_PREFIXES, 4, Set.of(
                    new MethodKey("o00000", "()V"), new MethodKey("new", "()V")));
        }
        if (ShipHullLoaderPhasePlan.TARGET_CLASS.equals(className)) {
            return new Expected(HULL_PREFIXES, 1, Set.of(
                    new MethodKey(ShipHullLoaderPhasePlan.LOAD_ALL_METHOD, "()V")));
        }
        if (SpecStorePhasePlan.TARGET_CLASS.equals(className)) {
            return new Expected(VARIANT_PREFIXES, 1, Set.of(
                    new MethodKey(VariantLoaderPhasePlan.METHOD, "()V")));
        }
        return null;
    }

    private record MethodKey(String name, String descriptor) {
    }

    private record Expected(Set<String> prefixes, int count, Set<MethodKey> methods) {
        boolean method(MethodNode method) {
            return methods.contains(new MethodKey(method.name, method.desc));
        }

        boolean methodsPresent(ClassSignature signature) {
            return methods.stream().allMatch(method ->
                    signature.hasMethod(method.name, method.descriptor));
        }
    }

    private record Block(MethodNode method, AbstractInsnNode first, AbstractInsnNode last) {
    }
}
