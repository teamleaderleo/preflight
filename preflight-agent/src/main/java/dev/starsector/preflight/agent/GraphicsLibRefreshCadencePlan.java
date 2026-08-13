package dev.starsector.preflight.agent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Reduces redundant native event pumping inside GraphicsLib's bounded startup loops. */
final class GraphicsLibRefreshCadencePlan {
    static final String INPUT_SHA256 = GraphicsLibLazyNormalPlan.OPTIMIZED_SHA256;

    private static final String TEXTURE_DATA = "org/dark/shaders/util/TextureData";
    private static final String SHADER_PLUGIN = "org/dark/shaders/ShaderModPlugin";
    private static final Map<String, Cadence> CADENCES = cadences();

    private GraphicsLibRefreshCadencePlan() {
    }

    static byte[] transform(byte[] input) throws IOException {
        ClassSignature signature = ClassSignature.parse(input);
        if (!TEXTURE_DATA.equals(signature.internalName())
                || !INPUT_SHA256.equals(signature.sha256())) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(input).accept(owner, ClassReader.EXPAND_FRAMES);
        int changed = 0;
        for (MethodNode method : owner.methods) {
            Cadence cadence = CADENCES.get(method.name + method.desc);
            if (cadence == null) {
                continue;
            }
            List<AbstractInsnNode> divisors = refreshDivisors(method);
            if (divisors.size() != cadence.sites()) {
                return null;
            }
            for (AbstractInsnNode divisor : divisors) {
                method.instructions.set(divisor, intConstant(cadence.value()));
            }
            changed++;
        }
        if (changed != CADENCES.size()) {
            return null;
        }
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static List<AbstractInsnNode> refreshDivisors(MethodNode method) {
        java.util.ArrayList<AbstractInsnNode> found = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !SHADER_PLUGIN.equals(call.owner)
                    || !"refresh".equals(call.name)
                    || !"()V".equals(call.desc)) {
                continue;
            }
            AbstractInsnNode branch = previousOpcode(call);
            AbstractInsnNode remainder = previousOpcode(branch);
            AbstractInsnNode divisor = previousOpcode(remainder);
            if (!(branch instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.IFNE
                    || remainder == null
                    || remainder.getOpcode() != Opcodes.IREM
                    || !isIntConstant(divisor)) {
                return List.of();
            }
            found.add(divisor);
        }
        return List.copyOf(found);
    }

    private static boolean isIntConstant(AbstractInsnNode instruction) {
        if (instruction instanceof IntInsnNode integer) {
            return integer.getOpcode() == Opcodes.BIPUSH
                    || integer.getOpcode() == Opcodes.SIPUSH;
        }
        return instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer;
    }

    private static AbstractInsnNode intConstant(int value) {
        return new IntInsnNode(Opcodes.SIPUSH, value);
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static Map<String, Cadence> cadences() {
        Map<String, Cadence> values = new LinkedHashMap<>();
        values.put("readTextureDataCSVInner(Ljava/lang/String;Z)V", new Cadence(500, 1));
        values.put("autoGenMissingNormalMapsInner(Z)V", new Cadence(500, 3));
        values.put("autoGenMissingNormalMaps()V", new Cadence(1000, 1));
        return Map.copyOf(values);
    }

    private record Cadence(int value, int sites) {
    }
}
