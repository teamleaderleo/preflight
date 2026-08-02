package com.fs.starfarer.api.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture standing in for the game's {@code Misc.tokenize} and {@code Misc$Token}.
 *
 * <p>Written from the disassembled signatures, not copied: Starsector's jars are not redistributable
 * and are not in this repository. Only the properties the tokenizer memo depends on are reproduced,
 * because those are what the tests need to be able to disagree with:
 *
 * <ul>
 *   <li>{@code tokenize} is a pure function of its argument and throws on malformed input;
 *   <li>every token is built by {@code new Token(String, TokenType)} and never mutated afterwards;
 *   <li>{@code Token} carries public non-final fields, and the constructor derives two of them.
 * </ul>
 */
public final class Misc {
    /** Counts real scans so a test can prove a hit did not reach the tokenizer. */
    public static int tokenizeCalls;

    private Misc() {
    }

    public enum TokenType {
        LITERAL,
        VARIABLE,
        OPERATOR
    }

    public static class Token {
        public String string;
        public TokenType type;
        public String varNameWithoutMemoryKeyIfKeyIsValid;
        public String varMemoryKey;

        public Token(String string, TokenType type) {
            this.string = string;
            this.type = type;
            if (type == TokenType.VARIABLE && string != null) {
                int dot = string.indexOf('.');
                if (dot > 0 && dot < string.length() - 1) {
                    varMemoryKey = string.substring(1, dot);
                    varNameWithoutMemoryKeyIfKeyIsValid = "$" + string.substring(dot + 1);
                }
            }
        }
    }

    public static List<Token> tokenize(String expression) {
        tokenizeCalls++;
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("No tokens found in string: [" + expression + "]");
        }
        List<Token> tokens = new ArrayList<>();
        for (String part : expression.trim().split("\\s+")) {
            tokens.add(new Token(part, typeOf(part)));
        }
        return tokens;
    }

    private static TokenType typeOf(String part) {
        if (part.startsWith("$")) {
            return TokenType.VARIABLE;
        }
        return part.equals("==") || part.equals("!=") || part.equals("<") || part.equals(">")
                ? TokenType.OPERATOR
                : TokenType.LITERAL;
    }
}
