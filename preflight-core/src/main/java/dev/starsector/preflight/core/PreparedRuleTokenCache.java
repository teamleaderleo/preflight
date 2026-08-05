package dev.starsector.preflight.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Profile-bound token shapes learned from vanilla's campaign-rule tokenizer. */
public record PreparedRuleTokenCache(
        String profileIdentitySha256, Map<String, List<Token>> entries) {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENTRIES = 131_072;
    public static final int MAX_TOKENS_PER_EXPRESSION = 4_096;
    public static final int MAX_TOTAL_TOKENS = 4_000_000;
    public static final int MAX_STRING_BYTES = 1024 * 1024;
    public static final int MAX_TYPE_BYTES = 256;

    public PreparedRuleTokenCache {
        if (profileIdentitySha256 == null
                || !profileIdentitySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Rule-token profile identity must be lowercase SHA-256");
        }
        if (entries == null || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Rule-token entry count exceeds the safety limit");
        }
        Map<String, List<Token>> copy = new LinkedHashMap<>();
        long totalTokens = 0L;
        for (Map.Entry<String, List<Token>> entry : entries.entrySet()) {
            String expression = entry.getKey();
            List<Token> tokens = entry.getValue();
            if (expression == null || tokens == null
                    || tokens.size() > MAX_TOKENS_PER_EXPRESSION) {
                throw new IllegalArgumentException("Rule-token entry is invalid");
            }
            totalTokens = Math.addExact(totalTokens, tokens.size());
            if (totalTokens > MAX_TOTAL_TOKENS) {
                throw new IllegalArgumentException("Rule-token total exceeds the safety limit");
            }
            copy.put(expression, List.copyOf(tokens));
        }
        entries = Map.copyOf(copy);
    }

    /** The two source fields needed to reconstruct one fresh mutable {@code Misc.Token}. */
    public record Token(String string, String type) {
        public Token {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("Rule token type is missing");
            }
        }
    }
}
