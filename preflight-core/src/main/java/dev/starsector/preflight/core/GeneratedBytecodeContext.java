package dev.starsector.preflight.core;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Exact, explicit invalidation inputs for one generated-bytecode compilation context. */
public record GeneratedBytecodeContext(
        String starsectorBuildSha256,
        String janinoImplementationSha256,
        String orderedClasspathSha256,
        String orderedSourceGraphSha256,
        String compilerOptionsSha256,
        String parentLoaderIdentitySha256,
        String protectionDomainPolicySha256) {
    private static final String SCHEMA = "starsector-preflight-generated-bytecode-context-v1";
    private static final int COMPONENTS = 7;

    public GeneratedBytecodeContext {
        starsectorBuildSha256 = hash(starsectorBuildSha256, "starsectorBuildSha256");
        janinoImplementationSha256 = hash(janinoImplementationSha256, "janinoImplementationSha256");
        orderedClasspathSha256 = hash(orderedClasspathSha256, "orderedClasspathSha256");
        orderedSourceGraphSha256 = hash(orderedSourceGraphSha256, "orderedSourceGraphSha256");
        compilerOptionsSha256 = hash(compilerOptionsSha256, "compilerOptionsSha256");
        parentLoaderIdentitySha256 = hash(parentLoaderIdentitySha256, "parentLoaderIdentitySha256");
        protectionDomainPolicySha256 = hash(protectionDomainPolicySha256, "protectionDomainPolicySha256");
    }

    public String keySha256() {
        String canonical = SCHEMA + "\n"
                + "starsector-build=" + starsectorBuildSha256 + "\n"
                + "janino-implementation=" + janinoImplementationSha256 + "\n"
                + "ordered-classpath=" + orderedClasspathSha256 + "\n"
                + "ordered-source-graph=" + orderedSourceGraphSha256 + "\n"
                + "compiler-options=" + compilerOptionsSha256 + "\n"
                + "parent-loader=" + parentLoaderIdentitySha256 + "\n"
                + "protection-domain-policy=" + protectionDomainPolicySha256 + "\n";
        return Hashes.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, String> components() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("schema", SCHEMA);
        values.put("starsectorBuildSha256", starsectorBuildSha256);
        values.put("janinoImplementationSha256", janinoImplementationSha256);
        values.put("orderedClasspathSha256", orderedClasspathSha256);
        values.put("orderedSourceGraphSha256", orderedSourceGraphSha256);
        values.put("compilerOptionsSha256", compilerOptionsSha256);
        values.put("parentLoaderIdentitySha256", parentLoaderIdentitySha256);
        values.put("protectionDomainPolicySha256", protectionDomainPolicySha256);
        values.put("keySha256", keySha256());
        return Map.copyOf(values);
    }

    /** Compact agent-option form containing only the seven already-validated SHA-256 components. */
    public String portableToken() {
        return String.join(".",
                starsectorBuildSha256,
                janinoImplementationSha256,
                orderedClasspathSha256,
                orderedSourceGraphSha256,
                compilerOptionsSha256,
                parentLoaderIdentitySha256,
                protectionDomainPolicySha256);
    }

    public static GeneratedBytecodeContext fromPortableToken(String token) {
        Objects.requireNonNull(token, "token");
        String[] values = token.split("\\.", -1);
        if (values.length != COMPONENTS) {
            throw new IllegalArgumentException(
                    "Generated-bytecode context token requires " + COMPONENTS + " SHA-256 components");
        }
        return new GeneratedBytecodeContext(
                values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
    }

    private static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        Hashes.decodeSha256(value);
        return value.toLowerCase(Locale.ROOT);
    }
}
