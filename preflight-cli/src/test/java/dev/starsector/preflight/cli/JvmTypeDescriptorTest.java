package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class JvmTypeDescriptorTest {
    @Test
    void extractsFieldAndMethodReferenceTypes() {
        assertEquals(Set.of("org.magiclib.api.MagicAPI"),
                JvmTypeDescriptor.classNames("Lorg/magiclib/api/MagicAPI;"));
        assertEquals(
                Set.of(
                        "com.fs.starfarer.api.combat.ShipAPI",
                        "org.lazywizard.lazylib.MathUtils",
                        "java.util.List"),
                JvmTypeDescriptor.classNames(
                        "([Lcom/fs/starfarer/api/combat/ShipAPI;Lorg/lazywizard/lazylib/MathUtils;)Ljava/util/List;"));
    }

    @Test
    void acceptsPrimitiveAndNestedArrayDescriptorsWithoutInventingClasses() {
        assertEquals(Set.of(), JvmTypeDescriptor.classNames("I"));
        assertEquals(Set.of(), JvmTypeDescriptor.classNames("([[I[D)Z"));
        assertEquals(Set.of("example.Widget"), JvmTypeDescriptor.classNames("[[[Lexample/Widget;"));
    }

    @Test
    void rejectsMalformedOrSignatureLikeText() {
        assertEquals(Set.of(), JvmTypeDescriptor.classNames(""));
        assertEquals(Set.of(), JvmTypeDescriptor.classNames("Ljava.lang.String;"));
        assertEquals(Set.of(), JvmTypeDescriptor.classNames("Lbad//Name;"));
        assertEquals(Set.of(), JvmTypeDescriptor.classNames("(I"));
        assertEquals(Set.of(), JvmTypeDescriptor.classNames("(V)V"));
        assertEquals(Set.of(), JvmTypeDescriptor.classNames("()Vjunk"));
        assertEquals(Set.of(), JvmTypeDescriptor.classNames("Ljava/util/List<Lfoo/Bar;>;"));
    }
}
