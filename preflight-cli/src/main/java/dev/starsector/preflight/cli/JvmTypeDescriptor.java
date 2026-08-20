package dev.starsector.preflight.cli;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Strict parser for JVM field and method descriptors used as bounded static type evidence. */
final class JvmTypeDescriptor {
    private static final int MAX_DESCRIPTOR_CHARS = 65_535;
    private static final int MAX_TYPES = 4_096;

    private JvmTypeDescriptor() {
    }

    static Set<String> classNames(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.length() > MAX_DESCRIPTOR_CHARS) {
            return Set.of();
        }
        Parser parser = new Parser(descriptor);
        try {
            if (descriptor.charAt(0) == '(') {
                parser.index++;
                while (parser.peek() != ')') {
                    if (!parser.type(false)) {
                        return Set.of();
                    }
                }
                parser.index++;
                if (!parser.type(true)) {
                    return Set.of();
                }
            } else if (!parser.type(false)) {
                return Set.of();
            }
            if (parser.index != descriptor.length()) {
                return Set.of();
            }
            return Collections.unmodifiableSet(parser.classes);
        } catch (IllegalArgumentException error) {
            return Set.of();
        }
    }

    private static final class Parser {
        private final String text;
        private final LinkedHashSet<String> classes = new LinkedHashSet<>();
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private char peek() {
            if (index >= text.length()) {
                throw new IllegalArgumentException("descriptor ended early");
            }
            return text.charAt(index);
        }

        private boolean type(boolean allowVoid) {
            char marker = peek();
            if (marker == 'V') {
                if (!allowVoid) {
                    return false;
                }
                index++;
                return true;
            }
            if (marker == '[') {
                do {
                    index++;
                    marker = peek();
                } while (marker == '[');
                return type(false);
            }
            if (marker == 'L') {
                return objectType();
            }
            if ("BCDFIJSZ".indexOf(marker) >= 0) {
                index++;
                return true;
            }
            return false;
        }

        private boolean objectType() {
            int start = ++index;
            int end = text.indexOf(';', start);
            if (end <= start) {
                return false;
            }
            String internal = text.substring(start, end);
            if (!validInternalName(internal)) {
                return false;
            }
            if (classes.size() >= MAX_TYPES && !classes.contains(internal)) {
                return false;
            }
            classes.add(internal.replace('/', '.'));
            index = end + 1;
            return true;
        }

        private static boolean validInternalName(String value) {
            if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '.' || ch == '[' || ch == ';' || ch == '(' || ch == ')') {
                    return false;
                }
            }
            return true;
        }
    }
}
