package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JvmTypeDescriptorTest {
    @Test
    void extractsFieldAndMethodReferenceTypes() throws Exception {
        assertEquals(
                Set.of("org.magiclib.api.MagicAPI"),
                JvmTypeDescriptor.parseField("Lorg/magiclib/api/MagicAPI;").classNames());
        assertEquals(
                Set.of(
                        "com.fs.starfarer.api.combat.ShipAPI",
                        "org.lazywizard.lazylib.MathUtils",
                        "java.util.List"),
                JvmTypeDescriptor.parseMethod(
                        "([Lcom/fs/starfarer/api/combat/ShipAPI;Lorg/lazywizard/lazylib/MathUtils;)Ljava/util/List;")
                        .classNames());
    }

    @Test
    void acceptsPrimitiveAndNestedArrayDescriptorsWithoutInventingClasses() throws Exception {
        assertEquals(Set.of(), JvmTypeDescriptor.parseField("I").classNames());
        assertEquals(Set.of(), JvmTypeDescriptor.parseMethod("([[I[D)Z").classNames());
        assertEquals(
                Set.of("example.Widget"),
                JvmTypeDescriptor.parseField("[[[Lexample/Widget;").classNames());
    }

    @Test
    void descriptorCategoryAndMalformedGrammarAreRejected() {
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseField(""));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseField("Ljava.lang.String;"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseField("Lbad//Name;"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseField("(I)V"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseMethod("I"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseMethod("(I"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseMethod("(V)V"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseMethod("()Vjunk"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseField("Ljava/util/List<Lfoo/Bar;>;"));
    }

    @Test
    void arrayDimensionBoundaryIsExact() throws Exception {
        String valid = "[".repeat(255) + "Lexample/Widget;";
        String oversized = "[".repeat(256) + "Lexample/Widget;";

        assertEquals(Set.of("example.Widget"), JvmTypeDescriptor.parseField(valid).classNames());
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseField(oversized));
    }

    @Test
    void methodParameterUnitBoundaryAccountsForWideTypesAndInstanceReceiver() throws Exception {
        String staticBoundary = "(" + "J".repeat(127) + "I)V";
        String instanceBoundary = "(" + "J".repeat(126) + "II)V";
        String instanceTooLarge = "(" + "J".repeat(127) + "I)V";
        String genericTooLarge = "(" + "J".repeat(128) + ")V";

        assertEquals(Set.of(), JvmTypeDescriptor.parseMethod(staticBoundary).classNames());
        assertEquals(Set.of(), JvmTypeDescriptor.parseMethod(instanceBoundary, true).classNames());
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseMethod(instanceTooLarge, true));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.parseMethod(genericTooLarge));
    }

    @Test
    void internalClassNamesFollowJvmUnqualifiedNameRules() throws Exception {
        assertEquals("DefaultPackage", JvmTypeDescriptor.binaryClassName("DefaultPackage"));
        assertEquals("odd.Name(withParens)", JvmTypeDescriptor.binaryClassName("odd/Name(withParens)"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.binaryClassName("bad//Name"));
        assertThrows(IOException.class, () -> JvmTypeDescriptor.binaryClassName("bad.Name"));
    }
}
