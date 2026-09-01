package dev.starsector.preflight.core.checkpoints;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, zero-dependency, memory-bounded JSON parser for checkpoints.
 */
public final class JsonParser {
    private static final int MAX_INPUT_CHARS = 32 * 1024 * 1024;
    private static final int MAX_DEPTH = 128;

    private JsonParser() {}

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON text is null");
        }
        if (text.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException("JSON input exceeds " + MAX_INPUT_CHARS + " characters");
        }
        Parser parser = new Parser(text);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.isEof()) {
            throw parser.error("Trailing content after JSON");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object val = parse(text);
        if (!(val instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
            this.pos = 0;
        }

        boolean isEof() {
            return pos >= text.length();
        }

        char peek() {
            return text.charAt(pos);
        }

        char next() {
            return text.charAt(pos++);
        }

        void skipWhitespace() {
            while (!isEof()) {
                char c = peek();
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '/' && pos + 1 < text.length()) {
                    char nextC = text.charAt(pos + 1);
                    if (nextC == '/') {
                        pos += 2;
                        while (!isEof() && peek() != '\n' && peek() != '\r') {
                            pos++;
                        }
                    } else if (nextC == '*') {
                        pos += 2;
                        while (!isEof()) {
                            if (peek() == '*' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                                pos += 2;
                                break;
                            }
                            pos++;
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("JSON nesting depth exceeded limit");
            }
            skipWhitespace();
            if (isEof()) {
                throw error("Unexpected end of JSON input");
            }
            char c = peek();
            return switch (c) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject(int depth) {
            match('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!isEof() && peek() == '}') {
                next();
                return map;
            }
            while (!isEof()) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected string key in object");
                }
                String key = parseString();
                skipWhitespace();
                match(':');
                Object val = parseValue(depth);
                map.put(key, val);
                skipWhitespace();
                if (isEof()) {
                    throw error("Unclosed object");
                }
                if (peek() == '}') {
                    next();
                    break;
                } else if (peek() == ',') {
                    next();
                    skipWhitespace();
                    if (peek() == '}') { // allow trailing comma
                        next();
                        break;
                    }
                } else {
                    throw error("Expected ',' or '}' in object");
                }
            }
            return map;
        }

        List<Object> parseArray(int depth) {
            match('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!isEof() && peek() == ']') {
                next();
                return list;
            }
            while (!isEof()) {
                Object val = parseValue(depth);
                list.add(val);
                skipWhitespace();
                if (isEof()) {
                    throw error("Unclosed array");
                }
                if (peek() == ']') {
                    next();
                    break;
                } else if (peek() == ',') {
                    next();
                    skipWhitespace();
                    if (peek() == ']') { // allow trailing comma
                        next();
                        break;
                    }
                } else {
                    throw error("Expected ',' or ']' in array");
                }
            }
            return list;
        }

        String parseString() {
            match('"');
            StringBuilder sb = new StringBuilder();
            while (!isEof()) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    if (isEof()) throw error("Unterminated escape sequence");
                    char esc = next();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw error("Invalid unicode escape");
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw error("Invalid unicode hex: " + hex);
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unclosed string literal");
        }

        Boolean parseBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("Expected boolean");
        }

        Object parseNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("Expected null");
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!isEof() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e' || peek() == 'E' || peek() == '+' || peek() == '-')) {
                pos++;
            }
            String numStr = text.substring(start, pos);
            try {
                if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                    return Double.parseDouble(numStr);
                }
                long l = Long.parseLong(numStr);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            } catch (NumberFormatException e) {
                throw error("Invalid number literal: " + numStr);
            }
        }

        void match(char expected) {
            if (isEof() || next() != expected) {
                throw error("Expected '" + expected + "'");
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + pos);
        }
    }
}
