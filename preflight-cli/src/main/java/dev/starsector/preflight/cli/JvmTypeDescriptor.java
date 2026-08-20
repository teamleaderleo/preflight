package dev.starsector.preflight.cli;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Strict parser for JVM field and method descriptors used as bounded static type evidence. */
final class JvmTypeDescriptor {
    private static final int MAX_DESCRIPTOR_CHARS = 65_535;
    private static final int MAX_TYPES = 4_096;
    private static final int MAX_ARRAY_DIMENSIONS = 255;
    private static final int MAX_METHOD_PARAMETER_UNITS = 255;

    private JvmTypeDescriptor() {
    }

    static Parsed parseField(String descriptor) throws IOException {
        Parser parser = parser(descriptor);
        parser.fieldType();
        parser.requireEnd();
        return parser.result();
    }

    static Parsed parseMethod(String descriptor) throws IOException {
        return parseMethod(descriptor, false);
    }

    static Parsed parseMethod(String descriptor, boolean instanceReceiver) throws IOException {
        Parser parser = parser(descriptor);
        parser.expect('(');
        int parameterUnits = instanceReceiver ? 1 : 0;
        while (parser.peek() != ')') {
            parameterUnits = Math.addExact(parameterUnits, parser.fieldType());
            if (parameterUnits > MAX_METHOD_PARAMETER_UNITS) {
                throw invalid("method descriptor exceeds the 255-unit parameter limit");
            }
        }
        parser.expect(')');
        parser.returnType();
        parser.requireEnd();
        return parser.result();
    }

    static String binaryClassName(String internalName) throws IOException {
        if (internalName == null || internalName.isEmpty()
                || internalName.startsWith("/") || internalName.endsWith("/")
                || internalName.contains("//")) {
            throw invalid("class name is invalid");
        }
        for (int index = 0; index < internalName.length(); index++) {
            char character = internalName.charAt(index);
            if (character == '.' || character == ';' || character == '[') {
                throw invalid("class name contains a forbidden character");
            }
        }
        return internalName.replace('/', '.');
    }

    private static Parser parser(String descriptor) throws IOException {
        if (descriptor == null || descriptor.isEmpty() || descriptor.length() > MAX_DESCRIPTOR_CHARS) {
            throw invalid("descriptor length is invalid");
        }
        return new Parser(descriptor);
    }

    private static IOException invalid(String message) {
        return new IOException("invalid JVM descriptor: " + message);
    }

    record Parsed(Set<String> classNames) {
        Parsed {
            classNames = Collections.unmodifiableSet(new LinkedHashSet<>(classNames));
        }
    }

    private static final class Parser {
        private final String text;
        private final LinkedHashSet<String> classes = new LinkedHashSet<>();
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Parsed result() {
            return new Parsed(classes);
        }

        private char peek() throws IOException {
            if (index >= text.length()) {
                throw invalid("descriptor ended early");
            }
            return text.charAt(index);
        }

        private void expect(char expected) throws IOException {
            if (peek() != expected) {
                throw invalid("expected '" + expected + "'");
            }
            index++;
        }

        private void requireEnd() throws IOException {
            if (index != text.length()) {
                throw invalid("descriptor contains trailing text");
            }
        }

        /** Returns the JVM method-parameter unit contribution of this field type. */
        private int fieldType() throws IOException {
            char marker = peek();
            if (marker == '[') {
                int dimensions = 0;
                do {
                    index++;
                    dimensions++;
                    if (dimensions > MAX_ARRAY_DIMENSIONS) {
                        throw invalid("array descriptor exceeds 255 dimensions");
                    }
                    marker = peek();
                } while (marker == '[');
                fieldType();
                return 1;
            }
            if (marker == 'L') {
                objectType();
                return 1;
            }
            if (marker == 'J' || marker == 'D') {
                index++;
                return 2;
            }
            if ("BCFISZ".indexOf(marker) >= 0) {
                index++;
                return 1;
            }
            throw invalid("expected a field type");
        }

        private void returnType() throws IOException {
            if (peek() == 'V') {
                index++;
                return;
            }
            fieldType();
        }

        private void objectType() throws IOException {
            int start = ++index;
            int end = text.indexOf(';', start);
            if (end <= start) {
                throw invalid("object type is unterminated or empty");
            }
            String binaryName = binaryClassName(text.substring(start, end));
            if (classes.size() >= MAX_TYPES && !classes.contains(binaryName)) {
                throw invalid("descriptor exceeds the referenced-type limit");
            }
            classes.add(binaryName);
            index = end + 1;
        }
    }
}
