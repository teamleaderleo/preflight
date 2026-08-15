package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * Writes a bounded, content-safe structural report for one exact reviewed class identity.
 *
 * <p>The report intentionally excludes class bytes, method bytecode listings, source-file contents,
 * and string constants. It retains only deterministic fingerprints and the minimum structural facts
 * required to review a later target-specific rewrite.</p>
 */
final class BytecodeShapeReport {
    private static final int METHOD_LIMIT = 32;
    private static final int FIELD_LIMIT = 256;
    private static final int CALL_LIMIT = 512;
    private static final int CONSTANT_LIMIT = 128;
    private static final int FLOW_POINT_LIMIT = 128;
    private static final int FRAME_VALUE_LIMIT = 64;
    private static final int DIAGNOSTIC_LIMIT = 100;

    private static final CaptureTarget BUILT_IN_TEXTURE_LOADER = new CaptureTarget(
            "vanilla-texture-loader-upload-shape-v1",
            "com/fs/graphics/TextureLoader",
            "d8fcb4cb90d457fc3075e711b6293940774dcf990ea66a7584c231bd96898b50",
            "STARSECTOR_CORE",
            "contents/resources/java/fs.common_obf.jar",
            "jdk/internal/loader/ClassLoaders$AppClassLoader",
            "app",
            List.of(
                    new MethodKey("o00000", "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;"),
                    new MethodKey("o00000", "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)V"),
                    new MethodKey("Ò00000", "(Ljava/lang/String;Ljava/awt/image/BufferedImage;)Lcom/fs/graphics/Object;"),
                    new MethodKey("Ô00000", "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;"),
                    new MethodKey("o00000", "(Ljava/awt/image/BufferedImage;IIII)Lcom/fs/graphics/Object;"),
                    new MethodKey("o00000", "(Ljava/nio/ByteBuffer;Ljava/lang/String;)V"),
                    new MethodKey("Ò00000", "(Ljava/lang/String;)Ljava/nio/ByteBuffer;"),
                    new MethodKey("o00000", "(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIZ)Lcom/fs/graphics/Object;"),
                    new MethodKey("o00000", "(Ljava/lang/String;)Lcom/fs/graphics/Object;")));

    private final Path destination;
    private final CaptureTarget target;
    private final Instant startedAt = Instant.now();
    private final List<String> diagnostics = new ArrayList<>();
    private Shape shape;
    private boolean exactIdentityObserved;
    private boolean captureFailed;

    BytecodeShapeReport(Path destination) {
        this(destination, BUILT_IN_TEXTURE_LOADER);
    }

    BytecodeShapeReport(Path destination, CaptureTarget target) {
        this.destination = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        this.target = Objects.requireNonNull(target, "target");
    }

    synchronized void observed(
            ClassSignature signature,
            AdapterSourceIdentity source,
            byte[] classfileBuffer) {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(classfileBuffer, "classfileBuffer");
        if (shape != null || captureFailed || !target.matches(signature, source)) {
            return;
        }
        exactIdentityObserved = true;
        try {
            shape = analyze(signature, source, classfileBuffer, target);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            captureFailed = true;
            diagnostic("Could not analyze exact bytecode shape: " + message(error));
        }
    }

    synchronized boolean captured() {
        return shape != null;
    }

    /**
     * Whether this run observed nothing a reader would act on. See {@link AdapterRuntime}.
     *
     * <p>A seam whose identity never matched is not a finding here, tempting as it looks: this
     * report does not record what it saw instead, so the document it writes says only
     * {@code exactIdentityObserved: false}. That a target was not transformed is already in
     * {@code adapter.json}, which is written every launch and which adapter health is derived from.
     * The finding this report alone can carry is an identity that matched and then failed to
     * analyze.
     */
    synchronized boolean routine() {
        return diagnostics.isEmpty() && !captureFailed;
    }

    synchronized void write() throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now());
        root.put("startedAt", startedAt);
        root.put("destination", destination);
        root.put("format", "starsector-preflight-bytecode-shape-v1");
        root.put("targetId", target.id());
        root.put("automaticRewriteGenerated", false);
        root.put("originalClassBytesRetained", true);
        root.put("classBytesIncluded", false);
        root.put("bytecodeListingsIncluded", false);
        root.put("stringConstantsIncluded", false);
        root.put("transformationPlanGenerated", false);
        root.put("transformRegistered", false);
        root.put("cacheReadsEnabled", false);
        root.put("cacheWritesEnabled", false);
        root.put("requiresHumanReview", true);
        root.put("methodLimit", METHOD_LIMIT);
        root.put("fieldLimitPerMethod", FIELD_LIMIT);
        root.put("callLimitPerMethod", CALL_LIMIT);
        root.put("constantLimitPerMethod", CONSTANT_LIMIT);
        root.put("flowPointLimitPerMethod", FLOW_POINT_LIMIT);
        root.put("frameValueLimit", FRAME_VALUE_LIMIT);
        root.put("exactIdentityObserved", exactIdentityObserved);
        root.put("captureFailed", captureFailed);
        root.put("captured", shape != null);
        root.put("shape", shape == null ? null : shape.toMap());
        root.put("diagnostics", List.copyOf(diagnostics));
        writeAtomic(destination, Json.object(root) + System.lineSeparator());
    }

    private static Shape analyze(
            ClassSignature signature,
            AdapterSourceIdentity source,
            byte[] classfileBuffer,
            CaptureTarget target) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(classfileBuffer).accept(classNode, ClassReader.SKIP_DEBUG);

        Map<MethodKey, MethodNode> methodsByKey = new LinkedHashMap<>();
        for (MethodNode method : classNode.methods) {
            methodsByKey.put(new MethodKey(method.name, method.desc), method);
        }

        List<MethodShape> methods = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean truncated = false;
        for (MethodKey key : target.methods()) {
            MethodNode method = methodsByKey.get(key);
            if (method == null) {
                missing.add(key.name() + key.descriptor());
                continue;
            }
            if (methods.size() >= METHOD_LIMIT) {
                truncated = true;
                break;
            }
            methods.add(analyzeMethod(classNode.name, method));
        }
        methods.sort(Comparator.comparing(MethodShape::name).thenComparing(MethodShape::descriptor));

        return new Shape(
                signature.internalName(),
                signature.sha256(),
                signature.majorVersion(),
                source.codeSource(),
                source.normalizedSource(),
                source.sourceKind(),
                source.sourceSha256(),
                source.sourceHashProblem(),
                source.loaderClass(),
                source.loaderName(),
                List.copyOf(methods),
                List.copyOf(missing),
                truncated);
    }

    private static MethodShape analyzeMethod(String owner, MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        Map<AbstractInsnNode, Integer> indexes = new java.util.IdentityHashMap<>();
        int position = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            indexes.put(instruction, position++);
            if (instruction.getOpcode() >= 0) {
                instructions.add(instruction);
            }
        }

        MessageDigest opcodeDigest = newDigest();
        MessageDigest instructionDigest = newDigest();
        TreeMap<String, FieldAccess> fields = new TreeMap<>();
        TreeMap<String, CallEdge> calls = new TreeMap<>();
        TreeMap<String, CallEdge> internalCalls = new TreeMap<>();
        TreeMap<String, ConstantShape> constants = new TreeMap<>();
        boolean fieldsTruncated = false;
        boolean callsTruncated = false;
        boolean internalCallsTruncated = false;
        boolean constantsTruncated = false;

        for (AbstractInsnNode instruction : instructions) {
            updateInt(opcodeDigest, instruction.getOpcode());
            updateInt(instructionDigest, instruction.getOpcode());
            updateInt(instructionDigest, instruction.getType());
            updateOperands(instructionDigest, instruction, indexes);

            if (instruction instanceof FieldInsnNode field) {
                String key = field.owner + "." + field.name + field.desc + "@" + field.getOpcode();
                if (fields.size() < FIELD_LIMIT) {
                    fields.putIfAbsent(key, new FieldAccess(
                            field.getOpcode(), field.owner, field.name, field.desc,
                            field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC));
                } else if (!fields.containsKey(key)) {
                    fieldsTruncated = true;
                }
            } else if (instruction instanceof MethodInsnNode call) {
                String key = call.getOpcode() + "@" + call.owner + "." + call.name + call.desc;
                CallEdge edge = new CallEdge(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
                if (calls.size() < CALL_LIMIT) {
                    calls.putIfAbsent(key, edge);
                } else if (!calls.containsKey(key)) {
                    callsTruncated = true;
                }
                if (owner.equals(call.owner)) {
                    if (internalCalls.size() < CALL_LIMIT) {
                        internalCalls.putIfAbsent(key, edge);
                    } else if (!internalCalls.containsKey(key)) {
                        internalCallsTruncated = true;
                    }
                }
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                String key = "indy@" + dynamic.name + dynamic.desc + "@" + handle(dynamic.bsm);
                CallEdge edge = new CallEdge(
                        Opcodes.INVOKEDYNAMIC,
                        "<dynamic>",
                        dynamic.name,
                        dynamic.desc,
                        false);
                if (calls.size() < CALL_LIMIT) {
                    calls.putIfAbsent(key, edge);
                } else if (!calls.containsKey(key)) {
                    callsTruncated = true;
                }
            } else if (instruction instanceof LdcInsnNode ldc) {
                ConstantShape constant = constant(ldc.cst);
                String key = constant.kind() + "@" + constant.value();
                if (constants.size() < CONSTANT_LIMIT) {
                    constants.putIfAbsent(key, constant);
                } else if (!constants.containsKey(key)) {
                    constantsTruncated = true;
                }
            } else if (instruction instanceof IntInsnNode integer) {
                ConstantShape constant = new ConstantShape("integer-operand", Integer.toString(integer.operand));
                String key = constant.kind() + "@" + constant.value();
                if (constants.size() < CONSTANT_LIMIT) {
                    constants.putIfAbsent(key, constant);
                } else if (!constants.containsKey(key)) {
                    constantsTruncated = true;
                }
            }
        }

        FlowResult flow = analyzeFlow(owner, method, indexes);
        return new MethodShape(
                method.name,
                method.desc,
                method.access,
                method.maxStack,
                method.maxLocals,
                instructions.size(),
                method.tryCatchBlocks == null ? 0 : method.tryCatchBlocks.size(),
                hex(opcodeDigest.digest()),
                hex(instructionDigest.digest()),
                List.copyOf(fields.values()),
                List.copyOf(calls.values()),
                List.copyOf(internalCalls.values()),
                List.copyOf(constants.values()),
                flow.points(),
                flow.error(),
                fieldsTruncated,
                callsTruncated,
                internalCallsTruncated,
                constantsTruncated,
                flow.truncated());
    }

    private static FlowResult analyzeFlow(
            String owner,
            MethodNode method,
            Map<AbstractInsnNode, Integer> indexes) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return new FlowResult(List.of(), "Method has no bytecode body", false);
        }
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = analyzer.analyze(owner, method);
            List<FlowPoint> points = new ArrayList<>();
            boolean truncated = false;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                int opcode = instruction.getOpcode();
                if (opcode < 0 || !flowPoint(instruction)) {
                    continue;
                }
                if (points.size() >= FLOW_POINT_LIMIT) {
                    truncated = true;
                    break;
                }
                Integer index = indexes.get(instruction);
                Frame<BasicValue> frame = index == null || index < 0 || index >= frames.length
                        ? null
                        : frames[index];
                points.add(new FlowPoint(
                        index == null ? -1 : index,
                        opcode,
                        flowKind(instruction),
                        flowTarget(instruction),
                        frameValues(frame, true),
                        frameValues(frame, false)));
            }
            return new FlowResult(List.copyOf(points), "", truncated);
        } catch (AnalyzerException | RuntimeException error) {
            return new FlowResult(List.of(), message(error), false);
        }
    }

    private static boolean flowPoint(AbstractInsnNode instruction) {
        if (instruction instanceof MethodInsnNode
                || instruction instanceof InvokeDynamicInsnNode) {
            return true;
        }
        if (instruction instanceof FieldInsnNode field) {
            return field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC;
        }
        int opcode = instruction.getOpcode();
        return opcode == Opcodes.IRETURN
                || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN
                || opcode == Opcodes.DRETURN
                || opcode == Opcodes.ARETURN
                || opcode == Opcodes.RETURN
                || opcode == Opcodes.ATHROW;
    }

    private static String flowKind(AbstractInsnNode instruction) {
        if (instruction instanceof MethodInsnNode) return "method-call";
        if (instruction instanceof InvokeDynamicInsnNode) return "dynamic-call";
        if (instruction instanceof FieldInsnNode) return "field-write";
        return instruction.getOpcode() == Opcodes.ATHROW ? "throw" : "return";
    }

    private static String flowTarget(AbstractInsnNode instruction) {
        if (instruction instanceof MethodInsnNode method) {
            return method.owner + "." + method.name + method.desc;
        }
        if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            return "<dynamic>." + dynamic.name + dynamic.desc;
        }
        if (instruction instanceof FieldInsnNode field) {
            return field.owner + "." + field.name + field.desc;
        }
        return "";
    }

    private static List<String> frameValues(Frame<BasicValue> frame, boolean locals) {
        if (frame == null) {
            return List.of();
        }
        int count = locals ? frame.getLocals() : frame.getStackSize();
        int retained = Math.min(count, FRAME_VALUE_LIMIT);
        List<String> result = new ArrayList<>(retained + (count > retained ? 1 : 0));
        for (int i = 0; i < retained; i++) {
            BasicValue value = locals ? frame.getLocal(i) : frame.getStack(i);
            result.add(value(value));
        }
        if (count > retained) {
            result.add("<truncated:" + (count - retained) + ">");
        }
        return List.copyOf(result);
    }

    private static String value(BasicValue value) {
        if (value == null) return "<null>";
        if (value == BasicValue.UNINITIALIZED_VALUE) return "<uninitialized>";
        if (value == BasicValue.RETURNADDRESS_VALUE) return "<return-address>";
        Type type = value.getType();
        return type == null ? value.toString() : type.getDescriptor();
    }

    private static void updateOperands(
            MessageDigest digest,
            AbstractInsnNode instruction,
            Map<AbstractInsnNode, Integer> indexes) {
        if (instruction instanceof FieldInsnNode field) {
            updateText(digest, field.owner);
            updateText(digest, field.name);
            updateText(digest, field.desc);
        } else if (instruction instanceof MethodInsnNode method) {
            updateText(digest, method.owner);
            updateText(digest, method.name);
            updateText(digest, method.desc);
            updateInt(digest, method.itf ? 1 : 0);
        } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            updateText(digest, dynamic.name);
            updateText(digest, dynamic.desc);
            updateText(digest, handle(dynamic.bsm));
            updateInt(digest, dynamic.bsmArgs.length);
            for (Object argument : dynamic.bsmArgs) {
                updateText(digest, constant(argument).kind());
                updateText(digest, constant(argument).value());
            }
        } else if (instruction instanceof TypeInsnNode type) {
            updateText(digest, type.desc);
        } else if (instruction instanceof IntInsnNode integer) {
            updateInt(digest, integer.operand);
        } else if (instruction instanceof VarInsnNode variable) {
            updateInt(digest, variable.var);
        } else if (instruction instanceof IincInsnNode increment) {
            updateInt(digest, increment.var);
            updateInt(digest, increment.incr);
        } else if (instruction instanceof LdcInsnNode ldc) {
            ConstantShape shape = constant(ldc.cst);
            updateText(digest, shape.kind());
            updateText(digest, shape.value());
        } else if (instruction instanceof JumpInsnNode jump) {
            updateInt(digest, index(indexes, jump.label));
        } else if (instruction instanceof TableSwitchInsnNode table) {
            updateInt(digest, table.min);
            updateInt(digest, table.max);
            updateInt(digest, index(indexes, table.dflt));
            for (LabelNode label : table.labels) updateInt(digest, index(indexes, label));
        } else if (instruction instanceof LookupSwitchInsnNode lookup) {
            updateInt(digest, index(indexes, lookup.dflt));
            updateInt(digest, lookup.keys.size());
            for (int i = 0; i < lookup.keys.size(); i++) {
                updateInt(digest, lookup.keys.get(i));
                updateInt(digest, index(indexes, lookup.labels.get(i)));
            }
        } else if (instruction instanceof MultiANewArrayInsnNode multi) {
            updateText(digest, multi.desc);
            updateInt(digest, multi.dims);
        }
    }

    private static int index(Map<AbstractInsnNode, Integer> indexes, AbstractInsnNode instruction) {
        Integer value = indexes.get(instruction);
        return value == null ? -1 : value;
    }

    private static ConstantShape constant(Object value) {
        if (value == null) return new ConstantShape("null", "null");
        if (value instanceof String text) {
            return new ConstantShape(
                    "string-redacted",
                    "length=" + text.length() + ",sha256="
                            + Hashes.sha256(text.getBytes(StandardCharsets.UTF_8)));
        }
        if (value instanceof Integer integer) return new ConstantShape("int", integer.toString());
        if (value instanceof Long number) return new ConstantShape("long", number.toString());
        if (value instanceof Float number) {
            return new ConstantShape("float-bits", Integer.toUnsignedString(Float.floatToRawIntBits(number)));
        }
        if (value instanceof Double number) {
            return new ConstantShape("double-bits", Long.toUnsignedString(Double.doubleToRawLongBits(number)));
        }
        if (value instanceof Type type) return new ConstantShape("type", type.getDescriptor());
        if (value instanceof Handle handle) return new ConstantShape("handle", handle(handle));
        if (value instanceof org.objectweb.asm.ConstantDynamic dynamic) {
            return new ConstantShape("constant-dynamic", dynamic.getName() + dynamic.getDescriptor());
        }
        return new ConstantShape("other-redacted", value.getClass().getName());
    }

    private static String handle(Handle handle) {
        return handle.getTag() + "@" + handle.getOwner() + "." + handle.getName() + handle.getDesc()
                + "@" + handle.isInterface();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void updateText(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private synchronized void diagnostic(String detail) {
        if (diagnostics.size() < DIAGNOSTIC_LIMIT) {
            diagnostics.add(detail == null ? "" : detail);
        }
    }

    private static void writeAtomic(Path destination, String content) throws IOException {
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    record CaptureTarget(
            String id,
            String className,
            String classSha256,
            String sourceKind,
            String sourceSuffix,
            String loaderClass,
            String loaderName,
            List<MethodKey> methods) {
        CaptureTarget {
            id = Objects.requireNonNull(id, "id");
            className = Objects.requireNonNull(className, "className").replace('.', '/');
            classSha256 = Objects.requireNonNull(classSha256, "classSha256").toLowerCase(java.util.Locale.ROOT);
            sourceKind = sourceKind == null ? "" : sourceKind;
            sourceSuffix = sourceSuffix == null ? "" : sourceSuffix;
            loaderClass = loaderClass == null ? "" : loaderClass.replace('.', '/');
            loaderName = loaderName == null ? "" : loaderName;
            methods = List.copyOf(methods);
        }

        boolean matches(ClassSignature signature, AdapterSourceIdentity source) {
            return className.equals(signature.internalName())
                    && classSha256.equals(signature.sha256())
                    && (sourceKind.isBlank() || sourceKind.equals(source.sourceKind()))
                    && (sourceSuffix.isBlank() || source.sourceEndsWith(sourceSuffix))
                    && (loaderClass.isBlank() || loaderClass.equals(source.loaderClass()))
                    && (loaderName.isBlank() || loaderName.equals(source.loaderName()));
        }
    }

    record MethodKey(String name, String descriptor) {
        MethodKey {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    private record Shape(
            String className,
            String classSha256,
            int majorVersion,
            String codeSource,
            String normalizedSource,
            String sourceKind,
            String sourceSha256,
            String sourceHashProblem,
            String loaderClass,
            String loaderName,
            List<MethodShape> methods,
            List<String> missingMethods,
            boolean methodsTruncated) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("className", className);
            values.put("classSha256", classSha256);
            values.put("majorVersion", majorVersion);
            values.put("codeSource", codeSource);
            values.put("normalizedSource", normalizedSource);
            values.put("sourceKind", sourceKind);
            values.put("sourceSha256", sourceSha256.isBlank() ? null : sourceSha256);
            values.put("sourceHashProblem", sourceHashProblem.isBlank() ? null : sourceHashProblem);
            values.put("loaderClass", loaderClass);
            values.put("loaderName", loaderName);
            values.put("methods", methods.stream().map(MethodShape::toMap).toList());
            values.put("missingMethods", missingMethods);
            values.put("methodsTruncated", methodsTruncated);
            return values;
        }
    }

    private record MethodShape(
            String name,
            String descriptor,
            int access,
            int maxStack,
            int maxLocals,
            int instructionCount,
            int tryCatchCount,
            String opcodeFingerprintSha256,
            String instructionFingerprintSha256,
            List<FieldAccess> fieldAccesses,
            List<CallEdge> calls,
            List<CallEdge> internalCalls,
            List<ConstantShape> constants,
            List<FlowPoint> flowPoints,
            String flowAnalysisError,
            boolean fieldAccessesTruncated,
            boolean callsTruncated,
            boolean internalCallsTruncated,
            boolean constantsTruncated,
            boolean flowPointsTruncated) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", name);
            values.put("descriptor", descriptor);
            values.put("access", access);
            values.put("maxStack", maxStack);
            values.put("maxLocals", maxLocals);
            values.put("instructionCount", instructionCount);
            values.put("tryCatchCount", tryCatchCount);
            values.put("opcodeFingerprintSha256", opcodeFingerprintSha256);
            values.put("instructionFingerprintSha256", instructionFingerprintSha256);
            values.put("fieldAccesses", fieldAccesses.stream().map(FieldAccess::toMap).toList());
            values.put("calls", calls.stream().map(CallEdge::toMap).toList());
            values.put("internalCalls", internalCalls.stream().map(CallEdge::toMap).toList());
            values.put("constants", constants.stream().map(ConstantShape::toMap).toList());
            values.put("flowPoints", flowPoints.stream().map(FlowPoint::toMap).toList());
            values.put("flowAnalysisError", flowAnalysisError.isBlank() ? null : flowAnalysisError);
            values.put("fieldAccessesTruncated", fieldAccessesTruncated);
            values.put("callsTruncated", callsTruncated);
            values.put("internalCallsTruncated", internalCallsTruncated);
            values.put("constantsTruncated", constantsTruncated);
            values.put("flowPointsTruncated", flowPointsTruncated);
            return values;
        }
    }

    private record FieldAccess(int opcode, String owner, String name, String descriptor, boolean write) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("opcode", opcode);
            values.put("owner", owner);
            values.put("name", name);
            values.put("descriptor", descriptor);
            values.put("write", write);
            return values;
        }
    }

    private record CallEdge(int opcode, String owner, String name, String descriptor, boolean interfaceCall) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("opcode", opcode);
            values.put("owner", owner);
            values.put("name", name);
            values.put("descriptor", descriptor);
            values.put("interfaceCall", interfaceCall);
            return values;
        }
    }

    private record ConstantShape(String kind, String value) {
        Map<String, Object> toMap() {
            return Map.of("kind", kind, "value", value);
        }
    }

    private record FlowPoint(
            int instructionIndex,
            int opcode,
            String kind,
            String target,
            List<String> locals,
            List<String> stack) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("instructionIndex", instructionIndex);
            values.put("opcode", opcode);
            values.put("kind", kind);
            values.put("target", target.isBlank() ? null : target);
            values.put("locals", locals);
            values.put("stack", stack);
            return values;
        }
    }

    private record FlowResult(List<FlowPoint> points, String error, boolean truncated) {
    }
}
