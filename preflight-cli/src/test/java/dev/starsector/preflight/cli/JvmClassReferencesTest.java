package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JvmClassReferencesTest {
    @Test
    void readsClassConstantsReferencedDescriptorsAndDeclaredMemberTypes() throws Exception {
        JvmClassReferences.Result result = JvmClassReferences.parse(classFileWithDescriptorEvidence());

        assertEquals("example.Consumer", result.binaryName());
        assertEquals(
                Set.of(
                        "java.lang.Object",
                        "library.Api",
                        "descriptor.Only",
                        "method.Param",
                        "method.Return",
                        "member.FieldType",
                        "member.Arg",
                        "member.Result"),
                result.references());
        assertFalse(result.references().contains("unused.ShouldNotAppear"));
    }

    @Test
    void rejectsTrailingOrTruncatedClassfileBytes() throws Exception {
        byte[] valid = classFileWithDescriptorEvidence();
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> JvmClassReferences.parse(trailing));
        assertThrows(IOException.class, () -> JvmClassReferences.parse(java.util.Arrays.copyOf(valid, valid.length - 1)));
    }

    private static byte[] classFileWithDescriptorEvidence() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0xcafebabe);
            output.writeShort(0);
            output.writeShort(52);

            // 1  Utf8 example/Consumer
            // 2  Class #1
            // 3  Utf8 java/lang/Object
            // 4  Class #3
            // 5  Utf8 library/Api
            // 6  Class #5
            // 7  Utf8 called
            // 8  Utf8 (Ldescriptor/Only;)V
            // 9  NameAndType #7:#8
            // 10 Methodref #6.#9
            // 11 Utf8 (Lmethod/Param;)Lmethod/Return;
            // 12 MethodType #11
            // 13 Utf8 unused
            // 14 Utf8 Lunused/ShouldNotAppear;
            // 15 NameAndType #13:#14 -- intentionally unreferenced
            // 16 Utf8 ownedField
            // 17 Utf8 Lmember/FieldType;
            // 18 Utf8 ownedMethod
            // 19 Utf8 (Lmember/Arg;)Lmember/Result;
            output.writeShort(20);
            utf8(output, "example/Consumer");
            clazz(output, 1);
            utf8(output, "java/lang/Object");
            clazz(output, 3);
            utf8(output, "library/Api");
            clazz(output, 5);
            utf8(output, "called");
            utf8(output, "(Ldescriptor/Only;)V");
            nameAndType(output, 7, 8);
            methodRef(output, 6, 9);
            utf8(output, "(Lmethod/Param;)Lmethod/Return;");
            methodType(output, 11);
            utf8(output, "unused");
            utf8(output, "Lunused/ShouldNotAppear;");
            nameAndType(output, 13, 14);
            utf8(output, "ownedField");
            utf8(output, "Lmember/FieldType;");
            utf8(output, "ownedMethod");
            utf8(output, "(Lmember/Arg;)Lmember/Result;");

            output.writeShort(0x0421); // public abstract super
            output.writeShort(2); // this_class
            output.writeShort(4); // super_class
            output.writeShort(0); // interfaces

            output.writeShort(1); // fields
            output.writeShort(0x0002); // private
            output.writeShort(16); // name
            output.writeShort(17); // descriptor
            output.writeShort(0); // attributes

            output.writeShort(1); // methods
            output.writeShort(0x0401); // public abstract
            output.writeShort(18); // name
            output.writeShort(19); // descriptor
            output.writeShort(0); // attributes

            output.writeShort(0); // class attributes
        }
        return bytes.toByteArray();
    }

    private static void utf8(DataOutputStream output, String value) throws IOException {
        output.writeByte(1);
        output.writeUTF(value);
    }

    private static void clazz(DataOutputStream output, int nameIndex) throws IOException {
        output.writeByte(7);
        output.writeShort(nameIndex);
    }

    private static void nameAndType(DataOutputStream output, int nameIndex, int descriptorIndex) throws IOException {
        output.writeByte(12);
        output.writeShort(nameIndex);
        output.writeShort(descriptorIndex);
    }

    private static void methodRef(DataOutputStream output, int classIndex, int nameAndTypeIndex) throws IOException {
        output.writeByte(10);
        output.writeShort(classIndex);
        output.writeShort(nameAndTypeIndex);
    }

    private static void methodType(DataOutputStream output, int descriptorIndex) throws IOException {
        output.writeByte(16);
        output.writeShort(descriptorIndex);
    }
}
