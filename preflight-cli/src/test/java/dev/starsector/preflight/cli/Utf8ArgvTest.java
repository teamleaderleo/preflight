package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Utf8ArgvTest {
    private static final String[] NON_ASCII = {
        "desktop", "snapshot", "--game", "C:\\Games\\Synthetic Game – path Ω", "--json",
    };

    @Test
    void passesAsciiVectorsThroughUntouched() {
        String[] args = {"desktop", "snapshot", "--game", "C:\\Games\\Starsector", "--json"};
        assertTrue(Utf8Argv.isAscii(args));
        assertSame(args, Utf8Argv.encode(args));
        assertSame(args, Utf8Argv.decode(args));
    }

    @Test
    void roundTripsTextOutsideAnyAnsiCodePage() {
        assertFalse(Utf8Argv.isAscii(NON_ASCII));
        String[] encoded = Utf8Argv.encode(NON_ASCII);
        assertEquals(Utf8Argv.SENTINEL, encoded[0]);
        assertTrue(Utf8Argv.isAscii(encoded), "the encoded vector must survive an ANSI code page");
        assertArrayEquals(NON_ASCII, Utf8Argv.decode(encoded));
    }

    @Test
    void roundTripsScriptsAndCharactersOutsideTheBasicMultilingualPlane() {
        String[] args = {"--profile", "Пользователь", "--game", "游戏/日本語", "--note", "🚀"};
        assertArrayEquals(args, Utf8Argv.decode(Utf8Argv.encode(args)));
    }

    @Test
    void encodesWithoutCharactersThatNeedQuoting() {
        String[] encoded = Utf8Argv.encode(NON_ASCII);
        for (int index = 1; index < encoded.length; index++) {
            String argument = encoded[index];
            assertFalse(argument.isEmpty(), "an encoded argument is never empty");
            for (int character = 0; character < argument.length(); character++) {
                char value = argument.charAt(character);
                boolean safe = (value >= 'a' && value <= 'z')
                        || (value >= 'A' && value <= 'Z')
                        || (value >= '0' && value <= '9')
                        || value == '-'
                        || value == '_';
                assertTrue(safe, "unexpected character '" + value + "' in " + argument);
            }
        }
    }

    @Test
    void encodesTheSameBytesAsTheDesktopHostAndPackageScripts() {
        // The desktop host (Rust) and the package scripts (Node) encode independently, so the same
        // vectors are pinned in all three test suites. They must agree byte for byte.
        assertArrayEquals(new String[] {Utf8Argv.SENTINEL, "zqk"}, Utf8Argv.encode(new String[] {"Ω"}));
        assertArrayEquals(
                new String[] {Utf8Argv.SENTINEL, "Y2FjaGU", "zqk"},
                Utf8Argv.encode(new String[] {"cache", "Ω"}));
        assertArrayEquals(
                new String[] {Utf8Argv.SENTINEL, "", "zqk"}, Utf8Argv.encode(new String[] {"", "Ω"}));
    }

    @Test
    void leavesAnEmptyVectorAlone() {
        String[] empty = {};
        assertSame(empty, Utf8Argv.encode(empty));
        assertSame(empty, Utf8Argv.decode(empty));
    }

    @Test
    void preservesEmptyArgumentsInsideAnEncodedVector() {
        String[] args = {"--label", "", "--game", "Ω"};
        assertArrayEquals(args, Utf8Argv.decode(Utf8Argv.encode(args)));
    }

    @Test
    void refusesAnEncodedVectorThatIsNotBase64() {
        String[] corrupt = {Utf8Argv.SENTINEL, "not valid base64!"};
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> Utf8Argv.decode(corrupt));
        assertTrue(error.getMessage().contains("Base64"), error.getMessage());
    }

    @Test
    void treatsTheSentinelAsMeaningfulOnlyInTheFirstPosition() {
        String[] args = {"desktop", Utf8Argv.SENTINEL, "--json"};
        assertSame(args, Utf8Argv.decode(args));
    }
}
