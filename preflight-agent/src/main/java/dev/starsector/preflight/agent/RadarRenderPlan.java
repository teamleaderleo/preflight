package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Reuses the exact vanilla campaign radar's immutable seven-class membership set. */
final class RadarRenderPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/coreui/A/oOoO";
    static final String ORIGINAL_SHA256 =
            "d946e2eddc6ecfca0b56e178928c349aa17ec178888e78701373679e818c0260";
    static final String RENDER_METHOD = "renderStuff";
    static final String RENDER_DESCRIPTOR = "(FZ)V";

    private static final String FIELD = "preflight$radarTypes";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/RadarRenderRuntime";
    private static final List<String> TYPES = List.of(
            "com/fs/starfarer/campaign/CampaignPlanet",
            "com/fs/starfarer/campaign/CustomCampaignEntity",
            "com/fs/starfarer/campaign/CampaignOrbitalStation",
            "com/fs/starfarer/campaign/JumpPoint",
            "com/fs/starfarer/campaign/fleet/CampaignFleet",
            "com/fs/starfarer/campaign/CampaignTerrain",
            "com/fs/starfarer/campaign/NascentGravityWell");

    private RadarRenderPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(RENDER_METHOD, RENDER_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name)
                || owner.fields.stream().anyMatch(field -> FIELD.equals(field.name))) {
            return null;
        }
        MethodNode render = uniqueMethod(owner, RENDER_METHOD, RENDER_DESCRIPTOR);
        MethodNode initializer = uniqueMethod(owner, "<clinit>", "()V");
        Block block = radarSetBlock(render);
        AbstractInsnNode initializerReturn = singleReturn(initializer);
        if (block == null || initializerReturn == null) {
            return null;
        }

        owner.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNTHETIC, FIELD, "Ljava/util/Set;", null, null));
        initializeSet(initializer, initializerReturn, owner.name);
        reuseSet(render, block, owner.name);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        RadarRenderRuntime.installed();
        return writer.toByteArray();
    }

    private static void reuseSet(MethodNode method, Block block, String owner) {
        LabelNode original = new LabelNode();
        LabelNode after = new LabelNode();
        InsnList shortcut = new InsnList();
        shortcut.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enabled", "()Z", false));
        shortcut.add(new JumpInsnNode(Opcodes.IFEQ, original));
        shortcut.add(new FieldInsnNode(
                Opcodes.GETSTATIC, owner, FIELD, "Ljava/util/Set;"));
        shortcut.add(new VarInsnNode(Opcodes.ASTORE, block.local));
        shortcut.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hit", "()V", false));
        shortcut.add(new JumpInsnNode(Opcodes.GOTO, after));
        shortcut.add(original);
        method.instructions.insertBefore(block.start, shortcut);
        method.instructions.insert(block.end, after);
    }

    private static void initializeSet(
            MethodNode initializer, AbstractInsnNode before, String owner) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false));
        for (String type : TYPES) {
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new LdcInsnNode(Type.getObjectType(type)));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
            code.add(new InsnNode(Opcodes.POP));
        }
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, FIELD, "Ljava/util/Set;"));
        initializer.instructions.insertBefore(before, code);
    }

    /** Finds only the reviewed NEW/store followed by the seven exact class additions. */
    private static Block radarSetBlock(MethodNode method) {
        if (method == null) return null;
        Block found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof TypeInsnNode type)
                    || type.getOpcode() != Opcodes.NEW
                    || !"java/util/HashSet".equals(type.desc)) {
                continue;
            }
            AbstractInsnNode cursor = nextCode(instruction);
            if (cursor == null || cursor.getOpcode() != Opcodes.DUP) continue;
            cursor = nextCode(cursor);
            if (!(cursor instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"java/util/HashSet".equals(constructor.owner)
                    || !"<init>".equals(constructor.name)
                    || !"()V".equals(constructor.desc)) continue;
            cursor = nextCode(cursor);
            if (!(cursor instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
            AbstractInsnNode end = store;
            boolean valid = true;
            for (String expected : TYPES) {
                cursor = nextCode(end);
                if (!(cursor instanceof VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD || load.var != store.var) {
                    valid = false; break;
                }
                cursor = nextCode(cursor);
                if (!(cursor instanceof LdcInsnNode literal)
                        || !(literal.cst instanceof Type classType)
                        || !expected.equals(classType.getInternalName())) {
                    valid = false; break;
                }
                cursor = nextCode(cursor);
                if (!(cursor instanceof MethodInsnNode add)
                        || add.getOpcode() != Opcodes.INVOKEINTERFACE
                        || !"java/util/Set".equals(add.owner)
                        || !"add".equals(add.name)
                        || !"(Ljava/lang/Object;)Z".equals(add.desc)) {
                    valid = false; break;
                }
                cursor = nextCode(cursor);
                if (cursor == null || cursor.getOpcode() != Opcodes.POP) {
                    valid = false; break;
                }
                end = cursor;
            }
            if (!valid || found != null) return null;
            found = new Block(instruction, end, store.var);
        }
        return found;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }

    private static AbstractInsnNode singleReturn(MethodNode method) {
        if (method == null) return null;
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            if (found != null) return null;
            found = instruction;
        }
        return found;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private record Block(AbstractInsnNode start, AbstractInsnNode end, int local) {
    }
}
