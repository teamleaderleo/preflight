package dev.starsector.preflight.cli;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Small, dependency-free reader for the fields used by Starsector metadata files. */
final class JsonText {
    private JsonText() {
    }

    static String string(String json, String key) {
        int value = findValue(json, key);
        if (value < 0) {
            return null;
        }
        Cursor cursor = new Cursor(json, value);
        cursor.skipWhitespaceAndComments();
        return cursor.peek() == '"' ? cursor.readString() : null;
    }

    /**
     * Reads Starsector metadata flags, whose ecosystem contains both JSON booleans and the historical
     * string spellings "true" / "false". Returns {@code null} when the field is absent and rejects
     * a present value of any other type/spelling so malformed context never becomes a default false.
     */
    static Boolean booleanLike(String json, String key) {
        int value = findValue(json, key);
        if (value < 0) {
            return null;
        }
        Cursor cursor = new Cursor(json, value);
        cursor.skipWhitespaceAndComments();
        if (cursor.peek() == '"') {
            String text = cursor.readString();
            if ("true".equalsIgnoreCase(text)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(text)) return Boolean.FALSE;
            throw new IllegalArgumentException("Expected true/false for key " + key);
        }
        if (json.startsWith("true", cursor.position()) && cursor.literalBoundary(4)) {
            return Boolean.TRUE;
        }
        if (json.startsWith("false", cursor.position()) && cursor.literalBoundary(5)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Expected true/false for key " + key);
    }

    static List<String> stringArray(String json, String key) {
        int value = findValue(json, key);
        if (value < 0) {
            return List.of();
        }
        Cursor cursor = new Cursor(json, value);
        cursor.skipWhitespaceAndComments();
        if (!cursor.consume('[')) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        while (true) {
            cursor.skipWhitespaceAndComments();
            if (cursor.consume(']')) {
                return List.copyOf(values);
            }
            if (cursor.peek() != '"') {
                throw new IllegalArgumentException("Expected a string in array for key " + key);
            }
            values.add(cursor.readString());
            cursor.skipWhitespaceAndComments();
            if (cursor.consume(']')) {
                return List.copyOf(values);
            }
            if (!cursor.consume(',')) {
                throw new IllegalArgumentException("Expected ',' or ']' in array for key " + key);
            }
            cursor.skipWhitespaceAndComments();
            if (cursor.consume(']')) {
                return List.copyOf(values);
            }
        }
    }

    /**
     * Reads a bounded string-array field while preserving a valid prefix when later elements are
     * malformed. This is intentionally separate from {@link #stringArray(String, String)} so
     * existing metadata consumers keep their established absent/wrong-type behavior.
     */
    static ArrayRead stringArrayStatus(String json, String key, int maximumValues) {
        return arrayStatus(json, key, maximumValues, false);
    }

    /**
     * Reads a bounded object-array field while preserving exact object texts that were valid before
     * a malformed tail. Callers can keep deterministic evidence already observed without treating
     * the remaining array as authoritative.
     */
    static ArrayRead objectArrayStatus(String json, String key, int maximumValues) {
        return arrayStatus(json, key, maximumValues, true);
    }

    private static ArrayRead arrayStatus(
            String json,
            String key,
            int maximumValues,
            boolean objects) {
        if (maximumValues < 0) {
            throw new IllegalArgumentException("maximumValues may not be negative");
        }
        int value = findValue(json, key);
        if (value < 0) {
            return ArrayRead.absent();
        }
        Cursor cursor = new Cursor(json, value);
        cursor.skipWhitespaceAndComments();
        if (!cursor.consume('[')) {
            return ArrayRead.malformed(List.of());
        }

        List<String> values = new ArrayList<>();
        try {
            while (true) {
                cursor.skipWhitespaceAndComments();
                if (cursor.consume(']')) {
                    return ArrayRead.valid(values);
                }
                char expected = objects ? '{' : '"';
                if (cursor.peek() != expected) {
                    return ArrayRead.malformed(values);
                }
                if (values.size() >= maximumValues) {
                    return ArrayRead.tooMany(values);
                }
                if (objects) {
                    int start = cursor.position();
                    cursor.skipValue();
                    if (cursor.finished()) {
                        return ArrayRead.malformed(values);
                    }
                    values.add(json.substring(start, cursor.position()));
                } else {
                    values.add(cursor.readString());
                }
                cursor.skipWhitespaceAndComments();
                if (cursor.consume(']')) {
                    return ArrayRead.valid(values);
                }
                if (!cursor.consume(',')) {
                    return ArrayRead.malformed(values);
                }
                cursor.skipWhitespaceAndComments();
                if (cursor.consume(']')) {
                    return ArrayRead.valid(values);
                }
            }
        } catch (RuntimeException malformed) {
            return ArrayRead.malformed(values);
        }
    }

    /**
     * Returns the exact object texts from an array-valued field in Starsector's loose JSON dialect.
     * Comments and trailing commas are accepted just like the existing string helpers. The objects
     * are not interpreted here; callers reuse {@link #string(String, String)} and the other typed
     * helpers so every metadata consumer keeps one parser boundary.
     *
     * <p>A present field with a non-array value is malformed rather than equivalent to an absent
     * optional field. Callers such as the dependency checker must not silently turn wrong-typed
     * metadata into "no dependencies".</p>
     */
    static List<String> objectArray(String json, String key) {
        int value = findValue(json, key);
        if (value < 0) {
            return List.of();
        }
        Cursor cursor = new Cursor(json, value);
        cursor.skipWhitespaceAndComments();
        if (!cursor.consume('[')) {
            throw new IllegalArgumentException("Expected an object array for key " + key);
        }

        List<String> values = new ArrayList<>();
        while (true) {
            cursor.skipWhitespaceAndComments();
            if (cursor.consume(']')) {
                return List.copyOf(values);
            }
            if (cursor.peek() != '{') {
                throw new IllegalArgumentException("Expected an object in array for key " + key);
            }
            int start = cursor.position();
            cursor.skipValue();
            if (cursor.finished()) {
                throw new IllegalArgumentException("Unterminated object array for key " + key);
            }
            values.add(json.substring(start, cursor.position()));
            cursor.skipWhitespaceAndComments();
            if (cursor.consume(']')) {
                return List.copyOf(values);
            }
            if (!cursor.consume(',')) {
                throw new IllegalArgumentException("Expected ',' or ']' in object array for key " + key);
            }
            cursor.skipWhitespaceAndComments();
            if (cursor.consume(']')) {
                return List.copyOf(values);
            }
        }
    }

    static Long integer(String json, String key) {
        int value = findValue(json, key);
        if (value < 0) return null;
        Cursor cursor = new Cursor(json, value);
        cursor.skipWhitespaceAndComments();
        int start = cursor.position();
        if (cursor.peek() == '-') cursor.advance();
        while (Character.isDigit(cursor.peek())) cursor.advance();
        if (cursor.position() == start || (cursor.position() == start + 1 && json.charAt(start) == '-')) {
            return null;
        }
        try {
            return Long.valueOf(json.substring(start, cursor.position()));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static int findValue(String json, String key) {
        Cursor cursor = new Cursor(json, 0);
        while (!cursor.finished()) {
            cursor.skipWhitespaceAndComments();
            if (cursor.finished()) {
                return -1;
            }
            if (cursor.peek() != '"') {
                cursor.advance();
                continue;
            }

            String candidate = cursor.readString();
            cursor.skipWhitespaceAndComments();
            if (!cursor.consume(':')) {
                continue;
            }
            cursor.skipWhitespaceAndComments();
            if (candidate.equals(key)) {
                return cursor.position();
            }
            cursor.skipValue();
        }
        return -1;
    }

    record ArrayRead(List<String> values, boolean present, boolean malformed, boolean tooMany) {
        ArrayRead {
            values = List.copyOf(values);
            if (!present && (malformed || tooMany || !values.isEmpty())) {
                throw new IllegalArgumentException("Absent array result may not carry values or errors");
            }
            if (tooMany && malformed) {
                throw new IllegalArgumentException("Array result cannot be malformed and over limit");
            }
        }

        private static ArrayRead absent() {
            return new ArrayRead(List.of(), false, false, false);
        }

        private static ArrayRead valid(List<String> values) {
            return new ArrayRead(values, true, false, false);
        }

        private static ArrayRead malformed(List<String> values) {
            return new ArrayRead(values, true, true, false);
        }

        private static ArrayRead tooMany(List<String> values) {
            return new ArrayRead(values, true, false, true);
        }
    }

    private static final class Cursor {
        private final String text;
        private int offset;

        Cursor(String text, int offset) {
            this.text = text;
            this.offset = offset;
        }

        int position() {
            return offset;
        }

        boolean finished() {
            return offset >= text.length();
        }

        char peek() {
            return finished() ? '\0' : text.charAt(offset);
        }

        void advance() {
            if (!finished()) {
                offset++;
            }
        }

        boolean consume(char expected) {
            if (peek() == expected) {
                offset++;
                return true;
            }
            return false;
        }

        boolean literalBoundary(int length) {
            int end = offset + length;
            if (end >= text.length()) return true;
            char next = text.charAt(end);
            return Character.isWhitespace(next)
                    || next == ','
                    || next == '}'
                    || next == ']'
                    || next == '#'
                    || next == '/';
        }

        void skipWhitespaceAndComments() {
            while (true) {
                while (!finished() && Character.isWhitespace(peek())) {
                    offset++;
                }
                if (finished()) {
                    return;
                }
                if (peek() == '#') {
                    skipLineComment(1);
                    continue;
                }
                if (offset + 1 >= text.length() || peek() != '/') {
                    return;
                }
                char next = text.charAt(offset + 1);
                if (next == '/') {
                    skipLineComment(2);
                    continue;
                }
                if (next == '*') {
                    offset += 2;
                    while (offset + 1 < text.length()
                            && !(text.charAt(offset) == '*' && text.charAt(offset + 1) == '/')) {
                        offset++;
                    }
                    offset = Math.min(text.length(), offset + 2);
                    continue;
                }
                return;
            }
        }

        private void skipLineComment(int prefixLength) {
            offset += prefixLength;
            while (!finished() && peek() != '\n' && peek() != '\r') {
                offset++;
            }
        }

        String readString() {
            if (!consume('"')) {
                throw new IllegalArgumentException("Expected JSON string at offset " + offset);
            }
            StringBuilder value = new StringBuilder();
            while (!finished()) {
                char c = text.charAt(offset++);
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                if (finished()) {
                    break;
                }
                char escaped = text.charAt(offset++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(readUnicodeEscape());
                    default -> throw new IllegalArgumentException(
                            "Unsupported JSON escape \\" + escaped + " at offset " + offset);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private char readUnicodeEscape() {
            if (offset + 4 > text.length()) {
                throw new IllegalArgumentException("Incomplete JSON unicode escape");
            }
            int codePoint = Integer.parseInt(text.substring(offset, offset + 4), 16);
            offset += 4;
            return (char) codePoint;
        }

        void skipValue() {
            skipWhitespaceAndComments();
            char start = peek();
            if (start == '"') {
                readString();
                return;
            }
            if (start == '{' || start == '[') {
                Deque<Character> closes = new ArrayDeque<>();
                closes.push(start == '{' ? '}' : ']');
                offset++;
                while (!finished() && !closes.isEmpty()) {
                    skipWhitespaceAndComments();
                    char c = peek();
                    if (c == '"') {
                        readString();
                        continue;
                    }
                    offset++;
                    if (c == '{') {
                        closes.push('}');
                    } else if (c == '[') {
                        closes.push(']');
                    } else if (c == closes.peek()) {
                        closes.pop();
                    }
                }
                return;
            }
            while (!finished() && ",}]".indexOf(peek()) < 0) {
                offset++;
            }
        }
    }
}
