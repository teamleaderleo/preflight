package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Offline exact contract check for the installed prepared-pixel texture target. */
public final class PreparedPixelContractCheck {
    public static final String SCHEMA = "starsector-preflight-prepared-pixel-contract";
    public static final int VERSION = 1;
    public static final String DEFAULT_ARCHIVE_ENTRY = TexturePreparedPixelPlan.TARGET_CLASS + ".class";

    private static final AdapterTarget MAC_TARGET = AdapterTargetRegistry.texturePreparedPixelTarget();
    private static final AdapterTarget LINUX_TARGET = AdapterTargetRegistry.linuxTexturePreparedPixelTarget();
    private static final long MAX_CLASS_BYTES = 32L * 1024 * 1024;
    private static final int MAX_PROBLEMS = 8;
    private static final int MAX_REASON_CHARS = 240;

    private PreparedPixelContractCheck() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run(args, System.out, System.err);
        } catch (Exception error) {
            System.err.println("prepared-pixel-contract: " + boundedText(message(error)));
            status = 1;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream output, PrintStream error) throws IOException {
        Options options = parse(args);
        Result result = inspect(options.input(), options.entry());
        output.println(result.toJson());
        if (!result.eligible()) {
            error.println("prepared-pixel-contract: exact prepared-pixel transformation declined");
            return 6;
        }
        return 0;
    }

    public static Result inspect(Path input, String archiveEntry) throws IOException {
        Snapshot snapshot = readSnapshot(input, archiveEntry);
        AdapterTarget target = targetFor(snapshot.classBytes());
        List<String> problems = new ArrayList<>();
        ClassSignature signature;
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        try {
            signature = ClassSignature.parse(snapshot.classBytes());
            new ClassReader(snapshot.classBytes()).accept(owner, ClassReader.EXPAND_FRAMES);
        } catch (IOException | RuntimeException parseError) {
            problems.add("class parsing failed: " + message(parseError));
            return result(snapshot, null, false, requiredMethods(null, target), null, null, problems);
        }

        boolean classMatches = target.internalClassName().equals(signature.internalName());
        if (!classMatches) {
            problems.add("class name is " + signature.internalName()
                    + ", expected " + target.internalClassName());
        }

        List<Map<String, Object>> requiredMethods = requiredMethods(signature, target);
        boolean requiredMethodsPresent = requiredMethods.stream()
                .allMatch(method -> Boolean.TRUE.equals(method.get("present")));
        if (!requiredMethodsPresent) {
            problems.add("one or more exact prepared-pixel target methods are absent");
        }

        MethodNode convert = uniqueSupportedMethod(
                owner,
                TexturePreparedPixelPlan.CONVERT_METHOD,
                TexturePreparedPixelPlan.LINUX_CONVERT_METHOD,
                TexturePreparedPixelPlan.CONVERT_DESCRIPTOR);
        TexturePreparedPixelColorSink.Review sinkReview = convert == null
                ? null
                : TexturePreparedPixelColorSink.inspect(owner, convert);
        if (convert == null) {
            problems.add("exact converter method is absent or duplicated");
        } else if (!sinkReview.eligible()) {
            problems.addAll(sinkReview.problems());
        }

        byte[] transformed = null;
        if (classMatches && requiredMethodsPresent && sinkReview != null && sinkReview.eligible()) {
            try {
                transformed = TexturePreparedPixelPlan.transform(signature, snapshot.classBytes());
            } catch (RuntimeException transformError) {
                problems.add("transformation failed: " + message(transformError));
            }
        }
        if (classMatches
                && requiredMethodsPresent
                && sinkReview != null
                && sinkReview.eligible()
                && transformed == null
                && problems.stream().noneMatch(problem -> problem.startsWith("transformation failed:"))) {
            problems.add("exact transformation declined after contract review");
        }

        return result(
                snapshot,
                signature.internalName(),
                classMatches,
                requiredMethods,
                sinkReview,
                transformed,
                problems);
    }

    private static Result result(
            Snapshot snapshot,
            String internalClassName,
            boolean classMatches,
            List<Map<String, Object>> requiredMethods,
            TexturePreparedPixelColorSink.Review sinkReview,
            byte[] transformed,
            List<String> problems) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("path", snapshot.path());
        source.put("archiveEntry", snapshot.archiveEntry());
        source.put("inputBytes", snapshot.inputBytes());
        source.put("inputSha256", snapshot.inputSha256());
        source.put("classBytes", snapshot.classBytes().length);
        source.put("classSha256", Hashes.sha256(snapshot.classBytes()));

        Map<String, Object> transformedIdentity = null;
        if (transformed != null) {
            transformedIdentity = new LinkedHashMap<>();
            transformedIdentity.put("bytes", transformed.length);
            transformedIdentity.put("sha256", Hashes.sha256(transformed));
            transformedIdentity = Collections.unmodifiableMap(transformedIdentity);
        }

        List<String> boundedProblems = boundedProblems(problems);
        boolean requiredMethodsPresent = requiredMethods.stream()
                .allMatch(method -> Boolean.TRUE.equals(method.get("present")));
        boolean eligible = classMatches
                && requiredMethodsPresent
                && sinkReview != null
                && sinkReview.eligible()
                && transformed != null
                && boundedProblems.isEmpty();
        return new Result(
                eligible,
                Collections.unmodifiableMap(source),
                internalClassName,
                classMatches,
                List.copyOf(requiredMethods),
                sinkReview == null ? null : sinkReview.toMap(),
                transformed != null,
                transformedIdentity,
                boundedProblems);
    }

    private static List<Map<String, Object>> requiredMethods(
            ClassSignature signature, AdapterTarget target) {
        return target.requiredMethods().stream()
                .map(required -> method(signature, required.name(), required.descriptor()))
                .toList();
    }

    private static AdapterTarget targetFor(byte[] classBytes) {
        return LINUX_TARGET.sha256().equals(Hashes.sha256(classBytes)) ? LINUX_TARGET : MAC_TARGET;
    }

    private static Map<String, Object> method(
            ClassSignature signature,
            String name,
            String descriptor) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("name", name);
        method.put("descriptor", descriptor);
        method.put("present", signature != null && signature.hasMethod(name, descriptor));
        return Collections.unmodifiableMap(method);
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    private static MethodNode uniqueSupportedMethod(
            ClassNode owner, String firstName, String secondName, String descriptor) {
        MethodNode first = uniqueMethod(owner, firstName, descriptor);
        MethodNode second = uniqueMethod(owner, secondName, descriptor);
        return first == null ? second : second == null ? first : null;
    }

    private static Snapshot readSnapshot(Path input, String archiveEntry) throws IOException {
        Path path = input.toAbsolutePath().normalize().toRealPath();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Expected a class file or archive: " + path);
        }
        if (archiveEntry == null) {
            byte[] classBytes = readBounded(Files.newInputStream(path), path.toString());
            return new Snapshot(path, null, classBytes.length, Hashes.sha256(classBytes), classBytes);
        }

        String normalizedEntry = normalizeArchiveEntry(archiveEntry);
        long inputBytes = Files.size(path);
        FileTime modified = Files.getLastModifiedTime(path);
        String inputSha256 = Hashes.sha256(path);
        byte[] classBytes;
        try (ZipFile archive = new ZipFile(path.toFile())) {
            ZipEntry entry = archive.getEntry(normalizedEntry);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Archive entry does not exist: " + normalizedEntry);
            }
            classBytes = readBounded(archive.getInputStream(entry), path + "!/" + normalizedEntry);
        }
        if (Files.size(path) != inputBytes || !Files.getLastModifiedTime(path).equals(modified)) {
            throw new IOException("Input changed while it was inspected: " + path);
        }
        return new Snapshot(path, normalizedEntry, inputBytes, inputSha256, classBytes);
    }

    private static String normalizeArchiveEntry(String archiveEntry) {
        String normalized = archiveEntry.replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/")) {
            throw new IllegalArgumentException("Invalid archive entry: " + archiveEntry);
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Invalid archive entry: " + archiveEntry);
            }
        }
        return normalized;
    }

    private static byte[] readBounded(InputStream source, String description) throws IOException {
        try (InputStream stream = source) {
            byte[] bytes = stream.readNBytes(Math.toIntExact(MAX_CLASS_BYTES + 1));
            if (bytes.length > MAX_CLASS_BYTES) {
                throw new IOException("Class input exceeds " + MAX_CLASS_BYTES + " bytes: " + description);
            }
            return bytes;
        }
    }

    private static Options parse(String[] args) {
        if (args.length == 1) {
            Path input = Path.of(args[0]);
            return new Options(input, archiveLike(input) ? DEFAULT_ARCHIVE_ENTRY : null);
        }
        if (args.length == 3 && "--entry".equals(args[1])) {
            return new Options(Path.of(args[0]), args[2]);
        }
        throw new IllegalArgumentException(
                "Expected: PreparedPixelContractCheck <class-or-jar> [--entry <class-entry>]");
    }

    private static boolean archiveLike(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static List<String> boundedProblems(List<String> problems) {
        Set<String> unique = new LinkedHashSet<>();
        for (String problem : problems) {
            if (problem != null && !problem.isBlank()) {
                unique.add(boundedText(problem));
            }
        }
        List<String> values = new ArrayList<>(unique);
        if (values.size() <= MAX_PROBLEMS) {
            return List.copyOf(values);
        }
        int omitted = values.size() - (MAX_PROBLEMS - 1);
        List<String> bounded = new ArrayList<>(values.subList(0, MAX_PROBLEMS - 1));
        bounded.add("additional problems omitted: " + omitted);
        return List.copyOf(bounded);
    }

    private static String boundedText(String text) {
        if (text.length() <= MAX_REASON_CHARS) {
            return text;
        }
        return text.substring(0, MAX_REASON_CHARS - 3) + "...";
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getName() : text;
    }

    public record Result(
            boolean eligible,
            Map<String, Object> source,
            String internalClassName,
            boolean classMatches,
            List<Map<String, Object>> requiredMethods,
            Map<String, Object> preparedPixelColorSink,
            boolean transformationSucceeded,
            Map<String, Object> transformed,
            List<String> problems) {
        public Result {
            source = Collections.unmodifiableMap(new LinkedHashMap<>(source));
            requiredMethods = List.copyOf(requiredMethods);
            preparedPixelColorSink = preparedPixelColorSink == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(preparedPixelColorSink));
            transformed = transformed == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(transformed));
            problems = List.copyOf(problems);
        }

        public String toJson() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("schema", SCHEMA);
            values.put("version", VERSION);
            values.put("eligible", eligible);
            values.put("source", source);
            values.put("internalClassName", internalClassName);
            values.put("classMatches", classMatches);
            values.put("requiredMethods", requiredMethods);
            values.put("preparedPixelColorSink", preparedPixelColorSink);
            values.put("transformationSucceeded", transformationSucceeded);
            values.put("transformed", transformed);
            values.put("problems", problems);
            return Json.object(values);
        }
    }

    private record Snapshot(
            Path path,
            String archiveEntry,
            long inputBytes,
            String inputSha256,
            byte[] classBytes) {
    }

    private record Options(Path input, String entry) {
    }
}
