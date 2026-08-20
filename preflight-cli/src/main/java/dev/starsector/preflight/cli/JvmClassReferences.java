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
    private static final int MAX_MEMBER_COUNT = 65_535;
    private static final int MAX_ATTRIBUTE_COUNT = 65_535;

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
                    case 9, 10, 11, 17, 18 -> skipFully(input, 4);
                    case 12 -> {
                        input.readUnsignedShort();
                        descriptorIndexes[index] = input.readUnsignedShort();
                    }
                    case 15 -> skipFully(input, 3);
                    case 16 -> descriptorIndexes[index] = input.readUnsignedShort();
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
                if (tags[index] == 7) {
                    String name = className(tags, classNameIndexes, utf8, index);
                    if (name == null) {
                        throw new IOException("invalid CONSTANT_Class entry at " + index);
                    }
                    addClassConstant(references, name);
                } else if (tags[index] == 12 || tags[index] == 16) {
                    addDescriptor(references, utf8At(tags, utf8, descriptorIndexes[index]));
                }
            }

            int interfaces = input.readUnsignedShort();
            skipFully(input, Math.multiplyExact(interfaces, 2));
            readMembers(input, tags, utf8, references);
            readMembers(input, tags, utf8, references);
            skipAttributes(input);
            if (input.available() != 0) {
                throw new IOException("trailing bytes after classfile attributes");
            }

            references.remove(binaryName);
            return new Result(binaryName, Collections.unmodifiableSet(references));
        } catch (EOFException error) {
            throw new IOException("classfile ended before its declared structure", error);
        } catch (ArithmeticException error) {
            throw new IOException("classfile count overflow", error);
        }
    }

    private static void readMembers(
            DataInputStream input, byte[] tags, String[] utf8, Set<String> references) throws IOException {
        int members = input.readUnsignedShort();
        if (members > MAX_MEMBER_COUNT) {
            throw new IOException("member count exceeds parser limit");
        }
        for (int index = 0; index < members; index++) {
            input.readUnsignedShort();
            input.readUnsignedShort();
            int descriptorIndex = input.readUnsignedShort();
            addDescriptor(references, utf8At(tags, utf8, descriptorIndex));
            skipAttributes(input);
        }
    }

    private static void skipAttributes(DataInputStream input) throws IOException {
        int attributes = input.readUnsignedShort();
        if (attributes > MAX_ATTRIBUTE_COUNT) {
            throw new IOException("attribute count exceeds parser limit");
        }
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

    private static void addDescriptor(Set<String> output, String descriptor) {
        output.addAll(JvmTypeDescriptor.classNames(descriptor));
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
        if (internal.indexOf('.') >= 0) {
            return null;
        }
        return internal.replace('/', '.');
    }

    private static void addClassConstant(Set<String> output, String name) {
        if (!name.startsWith("[")) {
            if (name.indexOf('.') >= 0) {
                output.add(name);
            }
            return;
        }
        output.addAll(JvmTypeDescriptor.classNames(name));
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
            references = Set.copyOf(references);
        }
    }
}
