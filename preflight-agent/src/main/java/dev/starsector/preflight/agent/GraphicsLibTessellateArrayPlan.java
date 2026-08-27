package dev.starsector.preflight.agent;

import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replays GraphicsLib's already-cached tessellation through one client-array draw. */
final class GraphicsLibTessellateArrayPlan {
    static final String TARGET_CLASS = "org/dark/graphics/util/Tessellate";
    static final String RENDER_METHOD = "render";
    static final String RENDER_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/BoundsAPI;FFFLcom/fs/starfarer/api/combat/ShipAPI;)V";

    private static final String TESS_DATA = TARGET_CLASS + "$TessData";
    private static final String VERTEX_DATA = TARGET_CLASS + "$VertexDataV2";
    private static final String SHIP = "com/fs/starfarer/api/combat/ShipAPI";
    private static final String VECTOR = "org/lwjgl/util/vector/Vector2f";
    private static final String VECTOR_UTILS = "org/lazywizard/lazylib/VectorUtils";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String BUFFER_UTILS = "org/lwjgl/BufferUtils";
    private static final String FLOAT_BUFFER = "java/nio/FloatBuffer";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GraphicsLibTessellateArrayRuntime";
    private static final String BUFFER_FIELD = "preflight$cachedVertexArray";
    private static final String BUFFER_DESCRIPTOR = "Ljava/nio/FloatBuffer;";
    private static final String HELPER = "preflight$drawCachedTessellation";
    private static final String HELPER_DESCRIPTOR = "(L" + TESS_DATA + ";L" + SHIP + ";)V";

    private static final int GL_CLIENT_VERTEX_ARRAY_BIT = 0x00000002;
    private static final int GL_VERTEX_ARRAY = 0x8074;
    private static final int GL_NORMAL_ARRAY = 0x8075;
    private static final int GL_COLOR_ARRAY = 0x8076;
    private static final int GL_INDEX_ARRAY = 0x8077;
    private static final int GL_TEXTURE_COORD_ARRAY = 0x8078;
    private static final int GL_EDGE_FLAG_ARRAY = 0x8079;
    private static final int GL_FOG_COORDINATE_ARRAY = 0x8457;
    private static final int GL_SECONDARY_COLOR_ARRAY = 0x845E;

    private GraphicsLibTessellateArrayPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!GraphicsLibTessellateArrayRuntime.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || signature.majorVersion() != 61
                || !signature.hasMethod(RENDER_METHOD, RENDER_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(
                owner, ClassReader.EXPAND_FRAMES | ClassReader.SKIP_DEBUG);
        if (!TARGET_CLASS.equals(owner.name)
                || hasField(owner, BUFFER_FIELD)
                || hasMethod(owner, HELPER, HELPER_DESCRIPTOR)) {
            return null;
        }

        MethodNode render = uniqueMethod(owner, RENDER_METHOD, RENDER_DESCRIPTOR);
        CachedBlock block = cachedBlock(render);
        if (block == null || hasUnsafeReferences(render, block.start, block.end)) {
            return null;
        }

        owner.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                BUFFER_FIELD,
                BUFFER_DESCRIPTOR,
                null,
                null));
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, block.tessDataLocal));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, block.shipLocal));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, owner.name, HELPER, HELPER_DESCRIPTOR, false));
        render.instructions.insertBefore(block.start, replacement);
        removeInclusive(render, block.start, block.end);
        owner.methods.add(helper(owner.name));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        GraphicsLibTessellateArrayRuntime.installed();
        return writer.toByteArray();
    }

    private static CachedBlock cachedBlock(MethodNode render) {
        if (render == null) {
            return null;
        }
        MethodInsnNode begin = uniqueCall(render, GL11, "glBegin", "(I)V");
        MethodInsnNode end = uniqueCall(render, GL11, "glEnd", "()V");
        if (begin == null || end == null || !precedes(begin, end)) {
            return null;
        }

        AbstractInsnNode glTypeInsn = previousCode(begin);
        AbstractInsnNode tessLoadInsn = previousCode(glTypeInsn);
        if (!(glTypeInsn instanceof FieldInsnNode glType)
                || glType.getOpcode() != Opcodes.GETFIELD
                || !TESS_DATA.equals(glType.owner)
                || !"glType".equals(glType.name)
                || !"I".equals(glType.desc)
                || !(tessLoadInsn instanceof VarInsnNode tessLoad)
                || tessLoad.getOpcode() != Opcodes.ALOAD) {
            return null;
        }

        if (callsBetween(tessLoadInsn, end, GL11, "glColor3f", "(FFF)V") != 1
                || callsBetween(tessLoadInsn, end, GL11, "glVertex2f", "(FF)V") != 1
                || callsBetween(tessLoadInsn, end, VECTOR_UTILS, "rotate", null) != 1
                || callsBetween(tessLoadInsn, end, VECTOR, "add", null) != 1
                || fieldsBetween(tessLoadInsn, end, TESS_DATA, "vertices", "Ljava/util/List;") != 1
                || fieldsBetween(tessLoadInsn, end, VERTEX_DATA, "data", "[D") < 2) {
            return null;
        }

        int facingLocal = uniqueReceiverLocalBetween(
                tessLoadInsn, end, "getFacing", "()F");
        int locationLocal = uniqueReceiverLocalBetween(
                tessLoadInsn, end, "getLocation", "()L" + VECTOR + ";");
        if (facingLocal < 0 || facingLocal != locationLocal) {
            return null;
        }
        return new CachedBlock(tessLoadInsn, end, tessLoad.var, facingLocal);
    }

    private static int uniqueReceiverLocalBetween(
            AbstractInsnNode start, AbstractInsnNode end, String name, String descriptor) {
        int found = -1;
        for (AbstractInsnNode instruction = start; instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                AbstractInsnNode receiver = previousCode(call);
                if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) {
                    return -1;
                }
                if (found >= 0) {
                    return -1;
                }
                found = load.var;
            }
            if (instruction == end) {
                break;
            }
        }
        return found;
    }

    private static boolean hasUnsafeReferences(
            MethodNode method, AbstractInsnNode start, AbstractInsnNode end) {
        Set<AbstractInsnNode> removed = new HashSet<>();
        for (AbstractInsnNode instruction = start; instruction != null; instruction = instruction.getNext()) {
            removed.add(instruction);
            if (instruction == end) {
                break;
            }
        }
        if (!removed.contains(end)) {
            return true;
        }

        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (removed.contains(instruction)) {
                continue;
            }
            if (instruction instanceof JumpInsnNode jump && removed.contains(jump.label)) {
                return true;
            }
            if (instruction instanceof TableSwitchInsnNode table) {
                if (removed.contains(table.dflt) || table.labels.stream().anyMatch(removed::contains)) {
                    return true;
                }
            }
            if (instruction instanceof LookupSwitchInsnNode lookup) {
                if (removed.contains(lookup.dflt) || lookup.labels.stream().anyMatch(removed::contains)) {
                    return true;
                }
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (removed.contains(block.start)
                    || removed.contains(block.end)
                    || removed.contains(block.handler)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode helper(String owner) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                HELPER,
                HELPER_DESCRIPTOR,
                null,
                null);
        InsnList code = method.instructions;
        var returnLabel = new org.objectweb.asm.tree.LabelNode();
        var grow = new org.objectweb.asm.tree.LabelNode();
        var bufferReady = new org.objectweb.asm.tree.LabelNode();
        var loop = new org.objectweb.asm.tree.LabelNode();
        var draw = new org.objectweb.asm.tree.LabelNode();

        // List vertices = tessData.vertices; int count = vertices.size();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, TESS_DATA, "vertices", "Ljava/util/List;"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFLE, returnLabel));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.ICONST_2));
        code.add(new InsnNode(Opcodes.IMUL));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));

        // Grow one reusable direct FloatBuffer only when this polygon exceeds prior capacity.
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, BUFFER_FIELD, BUFFER_DESCRIPTOR));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new JumpInsnNode(Opcodes.IFNULL, grow));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, FLOAT_BUFFER, "capacity", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, bufferReady));
        code.add(grow);
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BUFFER_UTILS,
                "createFloatBuffer",
                "(I)Ljava/nio/FloatBuffer;",
                false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, BUFFER_FIELD, BUFFER_DESCRIPTOR));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "bufferGrow", "(I)V", false));
        code.add(bufferReady);
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                FLOAT_BUFFER,
                "clear",
                "()Ljava/nio/FloatBuffer;",
                false));
        code.add(new InsnNode(Opcodes.POP));

        // Cache ship transform once. The old loop called VectorUtils.rotate/getFacing/getLocation per vertex.
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, SHIP, "getFacing", "()F", true));
        code.add(new InsnNode(Opcodes.F2D));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "toRadians", "(D)D", false));
        code.add(new VarInsnNode(Opcodes.DSTORE, 6));
        code.add(new VarInsnNode(Opcodes.DLOAD, 6));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "cos", "(D)D", false));
        code.add(new VarInsnNode(Opcodes.DSTORE, 8));
        code.add(new VarInsnNode(Opcodes.DLOAD, 6));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "sin", "(D)D", false));
        code.add(new VarInsnNode(Opcodes.DSTORE, 10));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, SHIP, "getLocation", "()L" + VECTOR + ";", true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 12));
        code.add(new VarInsnNode(Opcodes.ALOAD, 12));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, VECTOR, "x", "F"));
        code.add(new VarInsnNode(Opcodes.FSTORE, 13));
        code.add(new VarInsnNode(Opcodes.ALOAD, 12));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, VECTOR, "y", "F"));
        code.add(new VarInsnNode(Opcodes.FSTORE, 14));

        // Transform every cached local vertex into the reusable world-space float buffer.
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 15));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 15));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, draw));
        code.add(new VarInsnNode(Opcodes.ALOAD, 15));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, VERTEX_DATA));
        code.add(new VarInsnNode(Opcodes.ASTORE, 16));
        code.add(new VarInsnNode(Opcodes.ALOAD, 16));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, VERTEX_DATA, "data", "[D"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 17));
        code.add(new VarInsnNode(Opcodes.ALOAD, 17));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.DALOAD));
        code.add(new InsnNode(Opcodes.D2F));
        code.add(new VarInsnNode(Opcodes.FSTORE, 18));
        code.add(new VarInsnNode(Opcodes.ALOAD, 17));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.DALOAD));
        code.add(new InsnNode(Opcodes.D2F));
        code.add(new VarInsnNode(Opcodes.FSTORE, 19));

        code.add(new VarInsnNode(Opcodes.FLOAD, 18));
        code.add(new InsnNode(Opcodes.F2D));
        code.add(new VarInsnNode(Opcodes.DLOAD, 8));
        code.add(new InsnNode(Opcodes.DMUL));
        code.add(new VarInsnNode(Opcodes.FLOAD, 19));
        code.add(new InsnNode(Opcodes.F2D));
        code.add(new VarInsnNode(Opcodes.DLOAD, 10));
        code.add(new InsnNode(Opcodes.DMUL));
        code.add(new InsnNode(Opcodes.DSUB));
        code.add(new InsnNode(Opcodes.D2F));
        code.add(new VarInsnNode(Opcodes.FLOAD, 13));
        code.add(new InsnNode(Opcodes.FADD));
        code.add(new VarInsnNode(Opcodes.FSTORE, 20));

        code.add(new VarInsnNode(Opcodes.FLOAD, 18));
        code.add(new InsnNode(Opcodes.F2D));
        code.add(new VarInsnNode(Opcodes.DLOAD, 10));
        code.add(new InsnNode(Opcodes.DMUL));
        code.add(new VarInsnNode(Opcodes.FLOAD, 19));
        code.add(new InsnNode(Opcodes.F2D));
        code.add(new VarInsnNode(Opcodes.DLOAD, 8));
        code.add(new InsnNode(Opcodes.DMUL));
        code.add(new InsnNode(Opcodes.DADD));
        code.add(new InsnNode(Opcodes.D2F));
        code.add(new VarInsnNode(Opcodes.FLOAD, 14));
        code.add(new InsnNode(Opcodes.FADD));
        code.add(new VarInsnNode(Opcodes.FSTORE, 21));

        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new VarInsnNode(Opcodes.FLOAD, 20));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                FLOAT_BUFFER,
                "put",
                "(F)Ljava/nio/FloatBuffer;",
                false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new VarInsnNode(Opcodes.FLOAD, 21));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                FLOAT_BUFFER,
                "put",
                "(F)Ljava/nio/FloatBuffer;",
                false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));

        code.add(draw);
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                FLOAT_BUFFER,
                "flip",
                "()Ljava/nio/FloatBuffer;",
                false));
        code.add(new InsnNode(Opcodes.POP));

        // Immediate mode used current fixed-function attributes. Disable fixed client arrays so the
        // array draw keeps those current values, restore every saved client-array state afterwards,
        // and leave current color white exactly as the old glColor3f inside glBegin did.
        code.add(new InsnNode(Opcodes.FCONST_1));
        code.add(new InsnNode(Opcodes.FCONST_1));
        code.add(new InsnNode(Opcodes.FCONST_1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL11, "glColor3f", "(FFF)V", false));
        code.add(new LdcInsnNode(GL_CLIENT_VERTEX_ARRAY_BIT));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL11, "glPushClientAttrib", "(I)V", false));
        disableClientState(code, GL_NORMAL_ARRAY);
        disableClientState(code, GL_COLOR_ARRAY);
        disableClientState(code, GL_INDEX_ARRAY);
        disableClientState(code, GL_TEXTURE_COORD_ARRAY);
        disableClientState(code, GL_EDGE_FLAG_ARRAY);
        disableClientState(code, GL_FOG_COORDINATE_ARRAY);
        disableClientState(code, GL_SECONDARY_COLOR_ARRAY);
        code.add(new LdcInsnNode(GL_VERTEX_ARRAY));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL11, "glEnableClientState", "(I)V", false));
        code.add(new InsnNode(Opcodes.ICONST_2));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                GL11,
                "glVertexPointer",
                "(IILjava/nio/FloatBuffer;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, TESS_DATA, "glType", "I"));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL11, "glDrawArrays", "(III)V", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL11, "glPopClientAttrib", "()V", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "batch", "(I)V", false));
        code.add(returnLabel);
        code.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static void disableClientState(InsnList code, int cap) {
        code.add(new LdcInsnNode(cap));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL11, "glDisableClientState", "(I)V", false));
    }

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
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

    private static int callsBetween(
            AbstractInsnNode start,
            AbstractInsnNode end,
            String owner,
            String name,
            String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction = start; instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && (descriptor == null || descriptor.equals(call.desc))) {
                count++;
            }
            if (instruction == end) break;
        }
        return count;
    }

    private static int fieldsBetween(
            AbstractInsnNode start,
            AbstractInsnNode end,
            String owner,
            String name,
            String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction = start; instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && owner.equals(field.owner)
                    && name.equals(field.name)
                    && descriptor.equals(field.desc)) {
                count++;
            }
            if (instruction == end) break;
        }
        return count;
    }

    private static boolean precedes(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == second) return true;
        }
        return false;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static void removeInclusive(MethodNode method, AbstractInsnNode first, AbstractInsnNode last) {
        AbstractInsnNode cursor = first;
        while (cursor != null) {
            AbstractInsnNode next = cursor.getNext();
            method.instructions.remove(cursor);
            if (cursor == last) return;
            cursor = next;
        }
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

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc));
    }

    private static boolean hasField(ClassNode owner, String name) {
        return owner.fields.stream().anyMatch(field -> name.equals(field.name));
    }

    private record CachedBlock(
            AbstractInsnNode start, AbstractInsnNode end, int tessDataLocal, int shipLocal) {
    }
}
