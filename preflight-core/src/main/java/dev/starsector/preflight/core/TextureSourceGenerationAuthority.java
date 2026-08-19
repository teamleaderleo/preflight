package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Exact source-generation proof for prepared textures without launch-time source-byte hashing.
 *
 * <p>Preparation first creates content-addressed blobs whose encoded snapshots are already checked
 * against each manifest source SHA-256. After the manifest is published, this authority rechecks
 * the current source SHA-256 outside the game JVM while holding a filesystem generation token
 * stable around that read, then persists the token beside the manifest. Launch compares only those
 * cheap generation tokens before enabling prepared textures.
 *
 * <p>Supported authorities are deliberately narrow: Foundation's persistent generation identifier
 * on macOS, dev/inode/ctime on reviewed Linux filesystems, and file-id/USN on NTFS Windows. A
 * missing provider, unsupported volume, stale token, or helper failure yields no authority. Callers
 * then keep the prepared texture path disabled instead of treating size/mtime as content proof.
 */
public final class TextureSourceGenerationAuthority {
    public static final String MAC_PROVIDER = "macos-nsurl-generation-v1";
    public static final String LINUX_PROVIDER = "linux-unix-ctime-v1";
    public static final String WINDOWS_PROVIDER = "windows-ntfs-usn-v1";

    private static final int MAX_TOOL_OUTPUT_BYTES = 64 * 1024 * 1024;
    private static final Duration TOOL_TIMEOUT = Duration.ofMinutes(2);
    private static final Set<String> LINUX_CTIME_FILESYSTEMS = Set.of(
            "ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "overlay", "tmpfs");

    private TextureSourceGenerationAuthority() {
    }

    /**
     * Seals a manifest when it lives in the ordinary cache layout and its matching resource index
     * is available. Failure leaves the manifest usable only through the untrusted/original path.
     */
    public static SealResult sealIfPossible(Path manifestPath, TextureManifest manifest) {
        long started = System.nanoTime();
        Provider provider = provider();
        if (provider == null) {
            return SealResult.unavailable("No reviewed source generation provider for " + osName(), started);
        }
        try {
            Path cacheRoot = cacheRoot(manifestPath);
            if (cacheRoot == null) {
                return SealResult.unavailable("Manifest is outside the standard cache layout", started);
            }
            Path indexPath = ResourceIndexIO.directory(cacheRoot)
                    .resolve(manifest.profileFingerprint() + ".spfi");
            if (!Files.isRegularFile(indexPath)) {
                return SealResult.unavailable("Matching resource index is unavailable", started);
            }
            ResourceIndex index = ResourceIndexIO.read(indexPath);
            if (!manifest.profileFingerprint().equals(index.profileFingerprint())) {
                return SealResult.unavailable("Resource index profile differs from texture manifest", started);
            }

            SourceSet sources = sources(manifest.entries().keySet(), index);
            long generationStarted = System.nanoTime();
            Map<Path, String> before = provider.capture(sources.uniquePaths());
            long generationCaptureNanos = System.nanoTime() - generationStarted;

            long hashStarted = System.nanoTime();
            Map<Path, String> hashes = hashAll(sources.uniquePaths());
            long hashNanos = System.nanoTime() - hashStarted;
            for (Map.Entry<String, TextureManifest.Entry> entry : manifest.entries().entrySet()) {
                Path source = sources.realByLogical().get(entry.getKey());
                String actual = hashes.get(source);
                if (!entry.getValue().sourceSha256().equals(actual)) {
                    return SealResult.unavailable(
                            "Prepared texture source changed before generation seal: " + entry.getKey(),
                            started);
                }
            }

            generationStarted = System.nanoTime();
            provider.requireMatches(before);
            long generationValidationNanos = System.nanoTime() - generationStarted;

            TreeMap<String, String> tokens = new TreeMap<>();
            for (Map.Entry<String, Path> source : sources.realByLogical().entrySet()) {
                String token = before.get(source.getValue());
                if (token == null || token.isBlank()) {
                    throw new IOException("Generation provider omitted " + source.getValue());
                }
                tokens.put(source.getKey(), token);
            }
            String manifestSha256 = Hashes.sha256(manifestPath);
            TextureSourceGenerationProof proof = new TextureSourceGenerationProof(
                    manifest.profileFingerprint(), manifestSha256, provider.id(), tokens);
            Path proofPath = TextureSourceGenerationProofIO.path(cacheRoot, manifest.profileFingerprint());
            TextureSourceGenerationProofIO.write(proofPath, proof);
            return SealResult.available(
                    proofPath,
                    provider.id(),
                    proof.entryCount(),
                    sources.sourceBytes(),
                    hashNanos,
                    generationCaptureNanos + generationValidationNanos,
                    System.nanoTime() - started);
        } catch (Exception error) {
            return SealResult.unavailable(message(error), started);
        }
    }

    /** Validates a sealed source generation without reading source contents. */
    public static Validation validate(
            Path cacheRoot,
            Path manifestPath,
            TextureManifest manifest,
            ResourceIndex index) {
        long started = System.nanoTime();
        Provider provider = provider();
        if (provider == null) {
            return Validation.invalid("No reviewed source generation provider for " + osName(), started);
        }
        try {
            Path proofPath = TextureSourceGenerationProofIO.path(
                    cacheRoot.toAbsolutePath().normalize(), manifest.profileFingerprint());
            if (!Files.isRegularFile(proofPath, LinkOption.NOFOLLOW_LINKS)) {
                return Validation.invalid("Prepared texture source generation proof is missing", started);
            }
            TextureSourceGenerationProof proof = TextureSourceGenerationProofIO.read(proofPath);
            if (!manifest.profileFingerprint().equals(proof.profileFingerprint())) {
                return Validation.invalid("Prepared texture generation proof profile differs", started);
            }
            if (!provider.id().equals(proof.provider())) {
                return Validation.invalid(
                        "Prepared texture generation provider differs: " + proof.provider()
                                + " -> " + provider.id(),
                        started);
            }
            String manifestSha256 = Hashes.sha256(manifestPath);
            if (!manifestSha256.equals(proof.manifestSha256())) {
                return Validation.invalid("Prepared texture generation proof belongs to another manifest", started);
            }
            if (!manifest.entries().navigableKeySet().equals(proof.entries().navigableKeySet())) {
                return Validation.invalid("Prepared texture generation proof source set differs", started);
            }
            SourceSet sources = sources(proof.entries().keySet(), index);
            Map<Path, String> expected = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : proof.entries().entrySet()) {
                Path real = sources.realByLogical().get(entry.getKey());
                String prior = expected.putIfAbsent(real, entry.getValue());
                if (prior != null && !prior.equals(entry.getValue())) {
                    return Validation.invalid("One source has conflicting generation tokens: " + real, started);
                }
            }
            provider.requireMatches(expected);
            return Validation.valid(
                    provider.id(),
                    proof.entryCount(),
                    sources.sourceBytes(),
                    System.nanoTime() - started);
        } catch (Exception error) {
            return Validation.invalid(message(error), started);
        }
    }

    private static SourceSet sources(Collection<String> logicalPaths, ResourceIndex index) throws IOException {
        TreeMap<String, Path> realByLogical = new TreeMap<>();
        long sourceBytes = 0;
        Map<Path, Long> sizes = new HashMap<>();
        for (String logicalPath : logicalPaths) {
            ResourceIndex.Provider winner = index.winner(logicalPath)
                    .orElseThrow(() -> new IOException("Prepared texture source is missing: " + logicalPath));
            Path real = index.resolveExisting(winner).toRealPath();
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Prepared texture source is not a regular file: " + real);
            }
            realByLogical.put(ResourceIndex.normalizeLogicalPath(logicalPath), real);
            sizes.putIfAbsent(real, winner.size());
        }
        for (long size : sizes.values()) {
            sourceBytes = Math.addExact(sourceBytes, size);
        }
        return new SourceSet(
                Map.copyOf(realByLogical),
                realByLogical.values().stream().distinct().sorted().toList(),
                sourceBytes);
    }

    private static Map<Path, String> hashAll(List<Path> sources) throws IOException {
        String[] hashes = new String[sources.size()];
        int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        if (sources.size() <= 1 || workers == 1) {
            for (int index = 0; index < sources.size(); index++) {
                hashes[index] = Hashes.sha256(sources.get(index));
            }
        } else {
            ForkJoinPool pool = new ForkJoinPool(workers);
            try {
                pool.submit(() -> IntStream.range(0, sources.size()).parallel().forEach(index -> {
                    try {
                        hashes[index] = Hashes.sha256(sources.get(index));
                    } catch (IOException error) {
                        throw new HashFailure(error);
                    }
                })).join();
            } catch (CompletionException | HashFailure failure) {
                Throwable cause = failure instanceof HashFailure direct
                        ? direct.getCause()
                        : failure.getCause();
                while (cause instanceof CompletionException completion && completion.getCause() != null) {
                    cause = completion.getCause();
                }
                if (cause instanceof HashFailure wrapped && wrapped.getCause() instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw failure;
            } finally {
                pool.shutdown();
            }
        }
        Map<Path, String> result = new LinkedHashMap<>();
        for (int index = 0; index < sources.size(); index++) {
            result.put(sources.get(index), hashes[index]);
        }
        return Map.copyOf(result);
    }

    private static Path cacheRoot(Path manifestPath) {
        Path absolute = manifestPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || parent.getParent() == null) {
            return null;
        }
        Path marker = TextureManifestIO.directory(Path.of("cache-marker")).getFileName();
        if (marker == null || !marker.equals(parent.getFileName())) {
            return null;
        }
        return parent.getParent();
    }

    private static Provider provider() {
        String os = osName().toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return new MacProvider();
        }
        if (os.contains("win")) {
            return new WindowsProvider();
        }
        if (os.contains("linux") || os.contains("unix")) {
            return new LinuxProvider();
        }
        return null;
    }

    private interface Provider {
        String id();

        Map<Path, String> capture(List<Path> sources) throws IOException;

        default void requireMatches(Map<Path, String> expected) throws IOException {
            Map<Path, String> current = capture(expected.keySet().stream().sorted().toList());
            for (Map.Entry<Path, String> entry : expected.entrySet()) {
                if (!Objects.equals(entry.getValue(), current.get(entry.getKey()))) {
                    throw new IOException("Prepared texture source generation changed: " + entry.getKey());
                }
            }
        }
    }

    private static final class LinuxProvider implements Provider {
        @Override
        public String id() {
            return LINUX_PROVIDER;
        }

        @Override
        public Map<Path, String> capture(List<Path> sources) throws IOException {
            Map<Path, String> tokens = new HashMap<>();
            for (Path source : sources) {
                FileStore store = Files.getFileStore(source);
                String type = store.type().toLowerCase(Locale.ROOT);
                if (!LINUX_CTIME_FILESYSTEMS.contains(type)) {
                    throw new IOException("Linux filesystem does not have a reviewed ctime authority: "
                            + type + " for " + source);
                }
                Map<String, Object> attributes = Files.readAttributes(
                        source,
                        "unix:dev,ino,ctime",
                        LinkOption.NOFOLLOW_LINKS);
                Object dev = attributes.get("dev");
                Object ino = attributes.get("ino");
                Object ctime = attributes.get("ctime");
                if (!(dev instanceof Number device)
                        || !(ino instanceof Number inode)
                        || !(ctime instanceof FileTime changed)) {
                    throw new IOException("Linux filesystem omitted dev/ino/ctime for " + source);
                }
                String token = type
                        + ':' + Long.toUnsignedString(device.longValue())
                        + ':' + Long.toUnsignedString(inode.longValue())
                        + ':' + changed.to(TimeUnit.NANOSECONDS);
                tokens.put(source, token);
            }
            return Map.copyOf(tokens);
        }
    }

    private static final class MacProvider implements Provider {
        @Override
        public String id() {
            return MAC_PROVIDER;
        }

        @Override
        public Map<Path, String> capture(List<Path> sources) throws IOException {
            List<String> output = runTool(
                    List.of("/usr/bin/osascript", "-l", "JavaScript", "-e", MAC_CAPTURE_SCRIPT),
                    sources.stream().map(TextureSourceGenerationAuthority::encodePath).toList());
            if (output.size() != sources.size()) {
                throw new IOException("macOS generation provider returned " + output.size()
                        + " tokens for " + sources.size() + " sources");
            }
            Map<Path, String> tokens = new HashMap<>();
            for (int index = 0; index < sources.size(); index++) {
                String token = output.get(index);
                if (token.isBlank() || token.startsWith("!")) {
                    throw new IOException("macOS generation identifier unavailable for "
                            + sources.get(index) + (token.isBlank() ? "" : ": " + token.substring(1)));
                }
                tokens.put(sources.get(index), token);
            }
            return Map.copyOf(tokens);
        }

        @Override
        public void requireMatches(Map<Path, String> expected) throws IOException {
            List<Path> sources = expected.keySet().stream().sorted().toList();
            List<String> input = new ArrayList<>(sources.size());
            for (Path source : sources) {
                input.add(encodePath(source) + "\t" + expected.get(source));
            }
            List<String> output = runTool(
                    List.of("/usr/bin/osascript", "-l", "JavaScript", "-e", MAC_VALIDATE_SCRIPT),
                    input);
            if (output.size() != sources.size()) {
                throw new IOException("macOS generation validator returned " + output.size()
                        + " answers for " + sources.size() + " sources");
            }
            for (int index = 0; index < sources.size(); index++) {
                if (!"1".equals(output.get(index))) {
                    throw new IOException("Prepared texture source generation changed: " + sources.get(index));
                }
            }
        }
    }

    private static final class WindowsProvider implements Provider {
        @Override
        public String id() {
            return WINDOWS_PROVIDER;
        }

        @Override
        public Map<Path, String> capture(List<Path> sources) throws IOException {
            Path script = Files.createTempFile("preflight-usn-", ".ps1");
            try {
                Files.writeString(script, WINDOWS_USN_SCRIPT, StandardCharsets.UTF_8);
                List<String> output = runTool(
                        List.of(
                                windowsPowerShell(),
                                "-NoLogo",
                                "-NoProfile",
                                "-NonInteractive",
                                "-ExecutionPolicy",
                                "Bypass",
                                "-File",
                                script.toString()),
                        sources.stream().map(TextureSourceGenerationAuthority::encodePath).toList());
                if (output.size() != sources.size()) {
                    throw new IOException("Windows USN provider returned " + output.size()
                            + " tokens for " + sources.size() + " sources");
                }
                Map<Path, String> tokens = new HashMap<>();
                for (int index = 0; index < sources.size(); index++) {
                    String token = output.get(index);
                    if (token.isBlank() || token.startsWith("!")) {
                        throw new IOException("Windows USN unavailable for " + sources.get(index)
                                + (token.isBlank() ? "" : ": " + token.substring(1)));
                    }
                    tokens.put(sources.get(index), token);
                }
                return Map.copyOf(tokens);
            } finally {
                Files.deleteIfExists(script);
            }
        }
    }

    public record SealResult(
            boolean available,
            Path proofPath,
            String provider,
            int entryCount,
            long sourceBytes,
            long hashNanos,
            long generationNanos,
            long durationNanos,
            String problem) {
        static SealResult available(
                Path proofPath,
                String provider,
                int entryCount,
                long sourceBytes,
                long hashNanos,
                long generationNanos,
                long durationNanos) {
            return new SealResult(
                    true,
                    proofPath,
                    provider,
                    entryCount,
                    sourceBytes,
                    hashNanos,
                    generationNanos,
                    durationNanos,
                    "");
        }

        static SealResult unavailable(String problem, long started) {
            return new SealResult(false, null, "", 0, 0, 0, 0, System.nanoTime() - started, problem);
        }

        public double durationMillis() {
            return durationNanos / 1_000_000.0;
        }
    }

    public record Validation(
            boolean valid,
            String provider,
            int checkedEntries,
            long sourceBytes,
            long durationNanos,
            String problem) {
        static Validation valid(
                String provider,
                int checkedEntries,
                long sourceBytes,
                long durationNanos) {
            return new Validation(true, provider, checkedEntries, sourceBytes, durationNanos, "");
        }

        static Validation invalid(String problem, long started) {
            return new Validation(false, "", 0, 0, System.nanoTime() - started, problem);
        }

        public double durationMillis() {
            return durationNanos / 1_000_000.0;
        }
    }

    private record SourceSet(
            Map<String, Path> realByLogical,
            List<Path> uniquePaths,
            long sourceBytes) {
    }

    private static final class HashFailure extends RuntimeException {
        private HashFailure(IOException cause) {
            super(cause);
        }
    }

    private static String encodePath(Path path) {
        return Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String windowsPowerShell() {
        String root = System.getenv("SystemRoot");
        if (root != null && !root.isBlank()) {
            Path candidate = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "powershell.exe";
    }

    private static List<String> runTool(List<String> baseCommand, List<String> inputLines)
            throws IOException {
        Path input = Files.createTempFile("preflight-generation-", ".txt");
        try {
            Files.write(input, inputLines, StandardCharsets.US_ASCII);
            List<String> command = new ArrayList<>(baseCommand);
            command.add(input.toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            FutureTask<byte[]> outputTask = new FutureTask<>(() ->
                    process.getInputStream().readNBytes(MAX_TOOL_OUTPUT_BYTES + 1));
            Thread reader = new Thread(outputTask, "preflight-generation-tool-output");
            reader.setDaemon(true);
            reader.start();
            boolean exited;
            try {
                exited = process.waitFor(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Interrupted while reading source generations", interrupted);
            }
            if (!exited) {
                process.destroyForcibly();
                throw new IOException("Source generation provider exceeded "
                        + TOOL_TIMEOUT.toSeconds() + " seconds");
            }
            byte[] output;
            try {
                output = outputTask.get(10, TimeUnit.SECONDS);
            } catch (Exception error) {
                process.destroyForcibly();
                throw new IOException("Could not collect source generation provider output", error);
            }
            if (output.length > MAX_TOOL_OUTPUT_BYTES) {
                throw new IOException("Source generation provider output exceeded the safety limit");
            }
            String text = new String(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("Source generation provider failed: " + bounded(text));
            }
            if (text.isEmpty()) {
                return List.of();
            }
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
            if (normalized.endsWith("\n")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized.isEmpty() ? List.of() : List.of(normalized.split("\n", -1));
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static String bounded(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= 2_000 ? value : value.substring(0, 2_000) + "…";
    }

    private static String osName() {
        return System.getProperty("os.name", "");
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static final String MAC_CAPTURE_SCRIPT = """
            ObjC.import('Foundation');
            function textFile(path) {
              var value = $.NSString.stringWithContentsOfFileEncodingError($(path), $.NSUTF8StringEncoding, null);
              if (!value) throw new Error('could not read generation input');
              return ObjC.unwrap(value);
            }
            function decodePath(encoded) {
              var data = $.NSData.alloc.initWithBase64EncodedStringOptions($(encoded), 0);
              if (!data) throw new Error('invalid path encoding');
              return $.NSString.alloc.initWithDataEncoding(data, $.NSUTF8StringEncoding);
            }
            function generation(path) {
              var url = $.NSURL.fileURLWithPath(path);
              var keys = $.NSArray.arrayWithObject($.NSURLGenerationIdentifierKey);
              var values = url.resourceValuesForKeysError(keys, null);
              if (!values) return null;
              return values.objectForKey($.NSURLGenerationIdentifierKey);
            }
            function archive(value) {
              var data = $.NSKeyedArchiver.archivedDataWithRootObject(value);
              if (!data) return null;
              return ObjC.unwrap(data.base64EncodedStringWithOptions(0));
            }
            function run(argv) {
              var text = textFile(argv[0]);
              var lines = text.length === 0 ? [] : text.split(/\\r?\\n/);
              if (lines.length && lines[lines.length - 1] === '') lines.pop();
              var output = [];
              for (var i = 0; i < lines.length; i++) {
                var value = generation(decodePath(lines[i]));
                var token = value ? archive(value) : null;
                output.push(token || '!unsupported-volume');
              }
              return output.join('\\n');
            }
            """;

    private static final String MAC_VALIDATE_SCRIPT = """
            ObjC.import('Foundation');
            function textFile(path) {
              var value = $.NSString.stringWithContentsOfFileEncodingError($(path), $.NSUTF8StringEncoding, null);
              if (!value) throw new Error('could not read generation input');
              return ObjC.unwrap(value);
            }
            function decodePath(encoded) {
              var data = $.NSData.alloc.initWithBase64EncodedStringOptions($(encoded), 0);
              if (!data) throw new Error('invalid path encoding');
              return $.NSString.alloc.initWithDataEncoding(data, $.NSUTF8StringEncoding);
            }
            function generation(path) {
              var url = $.NSURL.fileURLWithPath(path);
              var keys = $.NSArray.arrayWithObject($.NSURLGenerationIdentifierKey);
              var values = url.resourceValuesForKeysError(keys, null);
              if (!values) return null;
              return values.objectForKey($.NSURLGenerationIdentifierKey);
            }
            function oldGeneration(encoded) {
              var data = $.NSData.alloc.initWithBase64EncodedStringOptions($(encoded), 0);
              if (!data) return null;
              return $.NSKeyedUnarchiver.unarchiveObjectWithData(data);
            }
            function run(argv) {
              var text = textFile(argv[0]);
              var lines = text.length === 0 ? [] : text.split(/\\r?\\n/);
              if (lines.length && lines[lines.length - 1] === '') lines.pop();
              var output = [];
              for (var i = 0; i < lines.length; i++) {
                var fields = lines[i].split('\\t');
                if (fields.length !== 2) throw new Error('invalid validation record');
                var current = generation(decodePath(fields[0]));
                var previous = oldGeneration(fields[1]);
                output.push(current && previous && current.isEqual(previous) ? '1' : '0');
              }
              return output.join('\\n');
            }
            """;

    private static final String WINDOWS_USN_SCRIPT = """
            param([Parameter(Mandatory=$true)][string]$InputFile)
            $ErrorActionPreference = 'Stop'
            Add-Type -TypeDefinition @'
            using System;
            using System.ComponentModel;
            using System.Runtime.InteropServices;
            using Microsoft.Win32.SafeHandles;

            public static class PreflightUsnGeneration {
                const uint FILE_READ_ATTRIBUTES = 0x80;
                const uint FILE_SHARE_READ = 0x1;
                const uint FILE_SHARE_WRITE = 0x2;
                const uint FILE_SHARE_DELETE = 0x4;
                const uint OPEN_EXISTING = 3;
                const uint FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
                const uint FSCTL_READ_FILE_USN_DATA = 0x000900EB;

                [StructLayout(LayoutKind.Sequential)]
                struct FILETIME { public uint Low; public uint High; }

                [StructLayout(LayoutKind.Sequential)]
                struct BY_HANDLE_FILE_INFORMATION {
                    public uint FileAttributes;
                    public FILETIME CreationTime;
                    public FILETIME LastAccessTime;
                    public FILETIME LastWriteTime;
                    public uint VolumeSerialNumber;
                    public uint FileSizeHigh;
                    public uint FileSizeLow;
                    public uint NumberOfLinks;
                    public uint FileIndexHigh;
                    public uint FileIndexLow;
                }

                [StructLayout(LayoutKind.Sequential, Pack = 1)]
                struct READ_FILE_USN_DATA {
                    public ushort MinMajorVersion;
                    public ushort MaxMajorVersion;
                }

                [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                static extern SafeFileHandle CreateFileW(
                    string fileName, uint desiredAccess, uint shareMode, IntPtr securityAttributes,
                    uint creationDisposition, uint flagsAndAttributes, IntPtr templateFile);

                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool GetFileInformationByHandle(
                    SafeFileHandle file, out BY_HANDLE_FILE_INFORMATION information);

                [DllImport("kernel32.dll", SetLastError = true)]
                static extern bool DeviceIoControl(
                    SafeFileHandle device, uint controlCode, ref READ_FILE_USN_DATA input,
                    uint inputSize, [Out] byte[] output, uint outputSize,
                    out uint bytesReturned, IntPtr overlapped);

                public static string Token(string path) {
                    using (SafeFileHandle handle = CreateFileW(
                        path, FILE_READ_ATTRIBUTES,
                        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                        IntPtr.Zero, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, IntPtr.Zero)) {
                        if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                        BY_HANDLE_FILE_INFORMATION info;
                        if (!GetFileInformationByHandle(handle, out info))
                            throw new Win32Exception(Marshal.GetLastWin32Error());

                        READ_FILE_USN_DATA request = new READ_FILE_USN_DATA {
                            MinMajorVersion = 2,
                            MaxMajorVersion = 2
                        };
                        byte[] output = new byte[512];
                        uint bytes;
                        if (!DeviceIoControl(
                            handle, FSCTL_READ_FILE_USN_DATA, ref request,
                            (uint)Marshal.SizeOf(typeof(READ_FILE_USN_DATA)),
                            output, (uint)output.Length, out bytes, IntPtr.Zero))
                            throw new Win32Exception(Marshal.GetLastWin32Error());
                        if (bytes < 32 || BitConverter.ToUInt16(output, 4) != 2)
                            throw new InvalidOperationException("unsupported USN record version");
                        long usn = BitConverter.ToInt64(output, 24);
                        return info.VolumeSerialNumber.ToString("X8") + ":"
                            + info.FileIndexHigh.ToString("X8") + info.FileIndexLow.ToString("X8") + ":"
                            + unchecked((ulong)usn).ToString("X16");
                    }
                }
            }
            '@ | Out-Null

            $utf8 = [System.Text.Encoding]::UTF8
            foreach ($line in [System.IO.File]::ReadAllLines($InputFile, [System.Text.Encoding]::ASCII)) {
                if ([string]::IsNullOrEmpty($line)) { continue }
                try {
                    $path = $utf8.GetString([Convert]::FromBase64String($line))
                    [PreflightUsnGeneration]::Token($path)
                } catch {
                    '!'+$_.Exception.Message.Replace("`r", ' ').Replace("`n", ' ')
                }
            }
            """;
}
