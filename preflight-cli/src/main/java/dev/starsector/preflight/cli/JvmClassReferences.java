package dev.starsector.preflight.cli;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extracts exact classfile identity and statically encoded JVM type references without class loading.
 * Reflective strings and generic-signature-only types are intentionally outside this evidence kind.
 */
final class JvmClassReferences {
    private static final int CLASSFILE_MAGIC = 0xcafebabe;
    private static final int MAX_CONSTANT_POOL_ENTRIES = 65_535;
    private static final int ACC_STATIC = 0x0008;

    private JvmClassReferences() {
    }

    static Result parse(byte[] classfile) throws IOException {
        if (classfile == null || classfile.length < 10) {
            throw new IOException("classfile is too small");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classfile))) {
            if (input.readInt() != CLASSFILE_MAGIC) {
                throw new IOException("invalid classfile magic");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            int count = input.readUnsignedShort();
            if (count < 2 || count > MAX_CONSTANT_POOL_ENTRIES) {
                throw new IOException("invalid constant-pool count: " + count);
            }

            byte[] tags = new byte[count];
            int[] classNameIndexes = new int[count];
            int[] descriptorIndexes = new int[count];
            int[] nameAndTypeIndexes = new int[count];
            String[] utf8 = new String[count];
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                tags[index] = (byte) tag;
                switch (tag) {
                    case 1 -> utf8[index] = input.readUTF();
                    case 3, 4 -> skipFully(input, 4);
                    case 5, 6 -> {
                        skipFully(input, 8);
                        index++;
                    }
                    case 7 -> classNameIndexes[index] = input.readUnsignedShort();
                    case 8, 19, 20 -> skipFully(input, 2);
                    case 9, 10, 11 -> {
                        input.readUnsignedShort();
                        nameAndTypeIndexes[index] = input.readUnsignedShort();
                    }
                    case 12 -> {
                        input.readUnsignedShort();
                        descriptorIndexes[index] = input.readUnsignedShort();
                    }
                    case 15 -> skipFully(input, 3);
                    case 16 -> descriptorIndexes[index] = input.readUnsignedShort();
                    case 17, 18 -> {
                        input.readUnsignedShort();
                        nameAndTypeIndexes[index] = input.readUnsignedShort();
                    }
                    default -> throw new IOException("unsupported constant-pool tag: " + tag);
                }
            }

            input.readUnsignedShort();
            int thisClass = input.readUnsignedShort();
            input.readUnsignedShort();
            String binaryName = className(tags, classNameIndexes, utf8, thisClass);
            if (binaryName == null || binaryName.startsWith("[")) {
                throw new IOException("invalid this_class identity");
            }

            LinkedHashSet<String> references = new LinkedHashSet<>();
            for (int index = 1; index < count; index++) {
                switch (tags[index]) {
                    case 7 -> {
                        String name = className(tags, classNameIndexes, utf8, index);
                        if (name == null) {
                            throw new IOException("invalid CONSTANT_Class entry at " + index);
                        }
                        addClassConstant(references, name);
                    }
                    case 9, 17 -> addFieldDescriptor(
                            references, referencedDescriptor(tags, descriptorIndexes, nameAndTypeIndexes, utf8, index));
                    case 10, 11, 18 -> addMethodDescriptor(
                            references, referencedDescriptor(tags, descriptorIndexes, nameAndTypeIndexes, utf8, index), false);
                    case 16 -> addMethodDescriptor(
                            references, utf8At(tags, utf8, descriptorIndexes[index]), false);
                    default -> {
                        // Other constant kinds either carry no descriptor or are reached through the
                        // field/method/dynamic reference entries above.
                    }
                }
            }

            int interfaces = input.readUnsignedShort();
            skipFully(input, Math.multiplyExact(interfaces, 2));
            readFields(input, tags, utf8, references);
            readMethods(input, tags, utf8, references);
            skipAttributes(input);
            if (input.available() != 0) {
                throw new IOException("trailing bytes after classfile attributes");
            }

            references.remove(binaryName);
            return new Result(binaryName, Collections.unmodifiableSet(new LinkedHashSet<>(references)));
        } catch (EOFException error) {
            throw new IOException("classfile ended before its declared structure", error);
        } catch (ArithmeticException error) {
            throw new IOException("classfile count overflow", error);
        }
    }

    private static String referencedDescriptor(
            byte[] tags,
            int[] descriptorIndexes,
            int[] nameAndTypeIndexes,
            String[] utf8,
            int referenceIndex) throws IOException {
        int nameAndType = nameAndTypeIndexes[referenceIndex];
        if (nameAndType <= 0 || nameAndType >= tags.length || tags[nameAndType] != 12) {
            throw new IOException("member reference has invalid NameAndType index");
        }
        return utf8At(tags, utf8, descriptorIndexes[nameAndType]);
    }

    private static void readFields(
            DataInputStream input, byte[] tags, String[] utf8, Set<String> references) throws IOException {
        int fields = input.readUnsignedShort();
        for (int index = 0; index < fields; index++) {
            input.readUnsignedShort();
            input.readUnsignedShort();
            int descriptorIndex = input.readUnsignedShort();
            addFieldDescriptor(references, utf8At(tags, utf8, descriptorIndex));
            skipAttributes(input);
        }
    }

    private static void readMethods(
            DataInputStream input, byte[] tags, String[] utf8, Set<String> references) throws IOException {
        int methods = input.readUnsignedShort();
        for (int index = 0; index < methods; index++) {
            int accessFlags = input.readUnsignedShort();
            input.readUnsignedShort();
            int descriptorIndex = input.readUnsignedShort();
            addMethodDescriptor(
                    references,
                    utf8At(tags, utf8, descriptorIndex),
                    (accessFlags & ACC_STATIC) == 0);
            skipAttributes(input);
        }
    }

    private static void skipAttributes(DataInputStream input) throws IOException {
        int attributes = input.readUnsignedShort();
        for (int index = 0; index < attributes; index++) {
            input.readUnsignedShort();
            long length = Integer.toUnsignedLong(input.readInt());
            if (length > input.available()) {
                throw new EOFException("attribute exceeds remaining classfile bytes");
            }
            skipFully(input, length);
        }
    }

    private static String utf8At(byte[] tags, String[] utf8, int index) throws IOException {
        if (index <= 0 || index >= tags.length || tags[index] != 1 || utf8[index] == null) {
            throw new IOException("descriptor index does not reference CONSTANT_Utf8");
        }
        return utf8[index];
    }

    private static void addFieldDescriptor(Set<String> output, String descriptor) throws IOException {
        output.addAll(JvmTypeDescriptor.parseField(descriptor).classNames());
    }

    private static void addMethodDescriptor(
            Set<String> output, String descriptor, boolean instanceReceiver) throws IOException {
        output.addAll(JvmTypeDescriptor.parseMethod(descriptor, instanceReceiver).classNames());
    }

    private static String className(byte[] tags, int[] classNameIndexes, String[] utf8, int classIndex) {
        if (classIndex <= 0 || classIndex >= tags.length || tags[classIndex] != 7) {
            return null;
        }
        int nameIndex = classNameIndexes[classIndex];
        if (nameIndex <= 0 || nameIndex >= tags.length || tags[nameIndex] != 1 || utf8[nameIndex] == null) {
            return null;
        }
        String internal = utf8[nameIndex];
        if (internal.isBlank()) {
            return null;
        }
        if (internal.startsWith("[")) {
            return internal;
        }
        try {
            return JvmTypeDescriptor.binaryClassName(internal);
        } catch (IOException invalid) {
            return null;
        }
    }

    private static void addClassConstant(Set<String> output, String name) throws IOException {
        if (!name.startsWith("[")) {
            output.add(name);
            return;
        }
        output.addAll(JvmTypeDescriptor.parseField(name).classNames());
    }

    private static void skipFully(DataInputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) {
                    throw new EOFException("classfile ended while skipping declared bytes");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    record Result(String binaryName, Set<String> references) {
        Result {
            references = Collections.unmodifiableSet(new LinkedHashSet<>(references));
        }
    }
}
