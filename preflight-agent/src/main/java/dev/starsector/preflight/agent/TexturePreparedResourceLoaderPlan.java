package dev.starsector.preflight.agent;

import java.util.HashMap;
import java.util.Map;
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
import org.objectweb.asm.tree.VarInsnNode;

/** Windows-only completion consumption, composed after prepared pixels and the dimension fold.
 * The supplied signature must describe the original, unmodified class, not composedBytes.
 * A non-null result is the installation signal; this plan does not modify runtime gates.
 */
final class TexturePreparedResourceLoaderPlan {
    static final String WINDOWS_SHA256 = "7d89b44c9401a122529450d17407dbfc8d52e13a9f7eb941dc93125eb5fc153b";
    static final String OWNER = "com/fs/graphics/TextureLoader";
    static final String LOAD_DESCRIPTOR =
            "(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIZ)Lcom/fs/graphics/Object;";
    static final String RUNTIME = "dev/starsector/preflight/agent/TexturePreparedResourceRuntime";
    static final String COMPLETION = RUNTIME + "$Completion";
    static final String PIXEL = "dev/starsector/preflight/agent/TexturePreparedPixelRuntime$PreparedPixel";
    static final String TAKE_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)L" + COMPLETION + ";";
    private static final String CONVERT = TexturePreparedPixelPlan.CONVERT_DESCRIPTOR;
    private static final String DECODE = TexturePreparedPixelPlan.DECODE_DESCRIPTOR;

    private TexturePreparedResourceLoaderPlan() {}

    static byte[] transform(ClassSignature originalSignature, byte[] composedBytes) {
        if (!TexturePreparedResourceRuntime.requested()
                || !OWNER.equals(originalSignature.internalName())
                || !WINDOWS_SHA256.equals(originalSignature.sha256())) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(composedBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!OWNER.equals(owner.name)
                || method(owner, "preflight$original$convertPixels", CONVERT) == null
                || method(owner, "preflight$original$foldDimension", "(I)I") == null) {
            return null;
        }
        MethodNode load = method(owner, "o00000", LOAD_DESCRIPTOR);
        MethodNode wrapper = method(owner, "o00000", CONVERT);
        if (load == null || wrapper == null || (load.access & Opcodes.ACC_STATIC) != 0) {
            return null;
        }
        for (MethodNode m : owner.methods) {
            for (AbstractInsnNode n : m.instructions) {
                if (n instanceof MethodInsnNode c && (c.owner.equals(RUNTIME) || c.owner.equals(COMPLETION))) {
                    return null;
                }
            }
        }
        MethodInsnNode decode = call(load, OWNER, "Ô00000", DECODE);
        MethodInsnNode convert = call(load, OWNER, "o00000", CONVERT);
        // Keep the prepared plan's catch-all release around both new branches.
        if (decode == null || convert == null || !covered(load, decode) || !covered(load, convert)) {
            return null;
        }
        int existing = load.maxLocals++;
        int completion = load.maxLocals++;
        int pixel = load.maxLocals++;
        int image = load.maxLocals++;
        int texture = load.maxLocals++;
        InsnList replay = replay(wrapper, pixel, texture);
        if (replay == null) {
            return null;
        }
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.add(new VarInsnNode(Opcodes.ASTORE, existing));
        entry.add(new InsnNode(Opcodes.ACONST_NULL));
        entry.add(new VarInsnNode(Opcodes.ASTORE, completion));
        load.instructions.insert(entry);

        // Original bci 52: replace only the decode invocation, retaining the image consumer body.
        LabelNode ordinaryDecode = new LabelNode();
        LabelNode decoded = new LabelNode();
        InsnList d = new InsnList();
        d.add(new InsnNode(Opcodes.POP2)); // original receiver and path
        d.add(new VarInsnNode(Opcodes.ALOAD, 2));
        d.add(new VarInsnNode(Opcodes.ALOAD, 0));
        d.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "new", "Lcom/fs/graphics/I;"));
        d.add(new VarInsnNode(Opcodes.ALOAD, existing));
        d.add(invoke(Opcodes.INVOKESTATIC, RUNTIME, "take", TAKE_DESCRIPTOR));
        d.add(new VarInsnNode(Opcodes.ASTORE, completion));
        d.add(new VarInsnNode(Opcodes.ALOAD, completion));
        d.add(new JumpInsnNode(Opcodes.IFNULL, ordinaryDecode));
        d.add(new VarInsnNode(Opcodes.ALOAD, completion));
        d.add(invoke(Opcodes.INVOKEVIRTUAL, COMPLETION, "image", "()Ljava/awt/image/BufferedImage;"));
        d.add(new JumpInsnNode(Opcodes.GOTO, decoded));
        d.add(ordinaryDecode);
        d.add(new VarInsnNode(Opcodes.ALOAD, 0));
        d.add(new VarInsnNode(Opcodes.ALOAD, 2));
        d.add(decode.clone(new HashMap<>()));
        d.add(decoded);
        load.instructions.insertBefore(decode, d);
        load.instructions.remove(decode);

        // Original bci 141: choose typed preparation, coherent original conversion, or stock wrapper.
        LabelNode ordinaryConvert = new LabelNode();
        LabelNode coherent = new LabelNode();
        LabelNode converted = new LabelNode();
        InsnList c = new InsnList();
        c.add(new VarInsnNode(Opcodes.ASTORE, texture));
        c.add(new VarInsnNode(Opcodes.ASTORE, image));
        c.add(new InsnNode(Opcodes.POP));
        c.add(new VarInsnNode(Opcodes.ALOAD, completion));
        c.add(new JumpInsnNode(Opcodes.IFNULL, ordinaryConvert));
        c.add(new VarInsnNode(Opcodes.ALOAD, completion));
        c.add(invoke(Opcodes.INVOKEVIRTUAL, COMPLETION, "prepare", "()L" + PIXEL + ";"));
        c.add(new VarInsnNode(Opcodes.ASTORE, pixel));
        c.add(new VarInsnNode(Opcodes.ALOAD, pixel));
        c.add(new JumpInsnNode(Opcodes.IFNULL, coherent));
        c.add(replay);
        c.add(new JumpInsnNode(Opcodes.GOTO, converted));
        c.add(coherent);
        c.add(new VarInsnNode(Opcodes.ALOAD, completion));
        c.add(invoke(Opcodes.INVOKEVIRTUAL, COMPLETION, "creditOriginalFallback", "()V"));
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new VarInsnNode(Opcodes.ALOAD, completion));
        c.add(invoke(Opcodes.INVOKEVIRTUAL, COMPLETION, "image", "()Ljava/awt/image/BufferedImage;"));
        c.add(new VarInsnNode(Opcodes.ALOAD, texture));
        c.add(invoke(Opcodes.INVOKESPECIAL, OWNER, "preflight$original$convertPixels", CONVERT));
        c.add(new JumpInsnNode(Opcodes.GOTO, converted));
        c.add(ordinaryConvert);
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new VarInsnNode(Opcodes.ALOAD, image));
        c.add(new VarInsnNode(Opcodes.ALOAD, texture));
        c.add(convert.clone(new HashMap<>()));
        c.add(converted);
        load.instructions.insertBefore(convert, c);
        load.instructions.remove(convert);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, true);
        owner.accept(writer);
        return writer.toByteArray();
    }

    // Clone the installed prepared wrapper's setters and ordered color writes. A direct completion
    // bypasses the converter entirely, so backing dimensions must be replayed even with gates off.
    private static InsnList replay(MethodNode wrapper, int pixel, int texture) {
        MethodInsnNode prepare = call(wrapper,
                "dev/starsector/preflight/agent/TexturePreparedPixelRuntime", "prepare",
                "(Ljava/awt/image/BufferedImage;)L" + PIXEL + ";");
        if (prepare == null) return null;
        AbstractInsnNode store = next(prepare);
        AbstractInsnNode read = next(store);
        AbstractInsnNode branch = next(read);
        if (!(store instanceof VarInsnNode s) || s.getOpcode() != Opcodes.ASTORE || s.var != 3
                || !(read instanceof VarInsnNode r) || r.getOpcode() != Opcodes.ALOAD || r.var != 3
                || !(branch instanceof JumpInsnNode j) || j.getOpcode() != Opcodes.IFNULL) return null;
        AbstractInsnNode end = branch.getNext();
        while (end != null && end.getOpcode() != Opcodes.ARETURN && end != j.label) end = end.getNext();
        if (end == null || end.getOpcode() != Opcodes.ARETURN) return null;
        AbstractInsnNode start = branch.getNext();
        while (start != end && !(start instanceof VarInsnNode v
                && v.getOpcode() == Opcodes.ALOAD && v.var == 2)) start = start.getNext();
        if (start == end) return null;
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode n = start; n != end; n = n.getNext()) {
            if (n instanceof LabelNode l) labels.put(l, new LabelNode());
        }
        InsnList result = new InsnList();
        int colors = 0;
        int dimensions = 0;
        int buffers = 0;
        for (AbstractInsnNode n = start; n != end; n = n.getNext()) {
            if (n instanceof FrameNode || n instanceof LineNumberNode) continue;
            if (n instanceof JumpInsnNode jump && !labels.containsKey(jump.label)) return null;
            AbstractInsnNode copy = n.clone(labels);
            if (copy instanceof VarInsnNode v) {
                if (v.getOpcode() != Opcodes.ALOAD || (v.var != 0 && v.var != 2 && v.var != 3)) return null;
                if (v.var == 2) v.var = texture;
                else if (v.var == 3) v.var = pixel;
            }
            if (copy instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD) {
                if (!OWNER.equals(f.owner) || !"Ljava/awt/Color;".equals(f.desc)) return null;
                colors++;
            }
            if (copy instanceof MethodInsnNode m) {
                if (m.owner.equals("com/fs/graphics/Object") && m.desc.equals("(I)V")) dimensions++;
                if (m.owner.equals(PIXEL) && m.name.equals("buffer")) buffers++;
            }
            result.add(copy);
        }
        return colors == 3 && dimensions == 2 && buffers == 1 ? result : null;
    }

    private static boolean covered(MethodNode method, AbstractInsnNode instruction) {
        int index = method.instructions.indexOf(instruction);
        return method.tryCatchBlocks.stream().anyMatch(t -> "java/lang/Throwable".equals(t.type)
                && method.instructions.indexOf(t.start) <= index && index < method.instructions.indexOf(t.end));
    }

    private static AbstractInsnNode next(AbstractInsnNode n) {
        if (n == null) return null;
        do { n = n.getNext(); } while (n != null && n.getOpcode() < 0);
        return n;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        var matches = owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static MethodInsnNode call(MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode found = null;
        for (AbstractInsnNode n : method.instructions) {
            if (n instanceof MethodInsnNode m && m.owner.equals(owner) && m.name.equals(name) && m.desc.equals(descriptor)) {
                if (found != null) return null;
                found = m;
            }
        }
        return found;
    }

    private static MethodInsnNode invoke(int opcode, String owner, String name, String descriptor) {
        return new MethodInsnNode(opcode, owner, name, descriptor, false);
    }
}
