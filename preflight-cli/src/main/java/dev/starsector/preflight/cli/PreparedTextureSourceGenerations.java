package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.TextureSourceGenerationProof;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Cheap content-generation authority for prepared texture sources.
 *
 * <p>Preparation already reads every source byte to build the content-addressed texture cache.
 * This class captures a kernel/filesystem generation token around that work. Launch compares the
 * saved token immediately before starting the game and can then skip source-byte hashing in the
 * shipped JVM. Unsupported filesystems simply have no proof and keep the exact hash fallback.
 */
final class PreparedTextureSourceGenerations {
    static final String MAC_PROVIDER = "macos-nsurl-generation-v1";
    static final String LINUX_PROVIDER = "linux-unix-ctime-v1";
    static final String WINDOWS_PROVIDER = "windows-ntfs-usn-v1";
    private static final int MAX_TOOL_OUTPUT_BYTES = 64 * 1024 * 1024;
    private static final Duration TOOL_TIMEOUT = Duration.ofMinutes(2);
    private static final Set<String> LINUX_CTIME_FILESYSTEMS = Set.of(
            "ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "overlay", "tmpfs");

    private PreparedTextureSourceGenerations() {
    }

    static Snapshot capture(Map<String, Path> logicalSources) {
        Provider provider = provider(Platform.current());
        if (provider == null) {
            return Snapshot.unsupported("No exact prepared-texture source generation provider for "
                    + Platform.current());
        }
        try {
            TreeMap<String, Path> realByLogical = canonicalSources(logicalSources);
            List<Path> unique = realByLogical.values().stream().distinct().sorted().toList();
            long started = System.nanoTime();
            Map<Path, String> tokens = provider.capture(unique);
            Map<String, String> logicalTokens = new TreeMap<>();
            for (Map.Entry<String, Path> source : realByLogical.entrySet()) {
                String token = tokens.get(source.getValue());
                if (token == null || token.isBlank()) {
                    throw new IOException("generation provider returned no token for " + source.getValue());
                }
                logicalTokens.put(source.getKey(), token);
            }
            return Snapshot.supported(
                    provider,
                    realByLogical,
                    logicalTokens,
                    System.nanoTime() - started);
        } catch (Exception error) {
            return Snapshot.unsupported("Prepared texture generation capture unavailable: " + message(error));
        }
    }

    static Validation validate(TextureSourceGenerationProof proof, ResourceIndex index) {
        Provider provider = provider(Platform.current());
        if (provider == null) {
            return Validation.invalid("No exact source generation provider for " + Platform.current(), 0);
        }
        if (!provider.id().equals(proof.provider())) {
            return Validation.invalid(
                    "Prepared texture generation provider changed: " + proof.provider()
                            + " -> " + provider.id(),
                    0);
        }
        long started = System.nanoTime();
        try {
            Map<Path, String> expected = expectedByRealPath(proof.entries(), index);
            provider.requireMatches(expected);
            return Validation.valid(proof.entryCount(), System.nanoTime() - started);
        } catch (Exception error) {
            return Validation.invalid(message(error), System.nanoTime() - started);
        }
    }

    private static TreeMap<String, Path> canonicalSources(Map<String, Path> logicalSources)
            throws IOException {
        TreeMap<String, Path> realByLogical = new TreeMap<>();
        for (Map.Entry<String, Path> source : logicalSources.entrySet()) {
            String logical = ResourceIndex.normalizeLogicalPath(source.getKey());
            Path real = source.getValue().toRealPath();
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Prepared texture source is not a regular file: " + real);
            }
            realByLogical.put(logical, real);
        }
        return realByLogical;
    }

    private static Map<Path, String> expectedByRealPath(
            Map<String, String> entries, ResourceIndex index) throws IOException {
        Map<Path, String> expected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            ResourceIndex.Provider winner = index.winner(entry.getKey())
                    .orElseThrow(() -> new IOException(
                            "Prepared texture source disappeared: " + entry.getKey()));
            Path real = index.resolveExisting(winner).toRealPath();
            String prior = expected.putIfAbsent(real, entry.getValue());
            if (prior != null && !prior.equals(entry.getValue())) {
                throw new IOException("One source path has conflicting generation tokens: " + real);
            }
        }
        return expected;
    }

    private static Provider provider(Platform platform) {
        return switch (platform) {
            case MAC -> new MacProvider();
            case LINUX -> new LinuxProvider();
            case WINDOWS -> new WindowsProvider();
            case OTHER -> null;
        };
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
            List<String> input = sources.stream().map(PreparedTextureSourceGenerations::encodePath).toList();
            List<String> output = runTool(
                    List.of("/usr/bin/osascript", "-l", "JavaScript", "-e", MAC_CAPTURE_SCRIPT),
                    input);
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
                List<String> command = List.of(
                        windowsPowerShell(),
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        script.toString());
                List<String> input = sources.stream().map(PreparedTextureSourceGenerations::encodePath).toList();
                List<String> output = runTool(command, input);
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

    static final class Snapshot {
        private final Provider provider;
        private final Map<String, Path> realByLogical;
        private final Map<String, String> tokens;
        private final String problem;
        private final long captureNanos;

        private Snapshot(
                Provider provider,
                Map<String, Path> realByLogical,
                Map<String, String> tokens,
                String problem,
                long captureNanos) {
            this.provider = provider;
            this.realByLogical = Map.copyOf(realByLogical);
            this.tokens = Map.copyOf(tokens);
            this.problem = problem;
            this.captureNanos = captureNanos;
        }

        static Snapshot supported(
                Provider provider,
                Map<String, Path> realByLogical,
                Map<String, String> tokens,
                long captureNanos) {
            return new Snapshot(provider, realByLogical, tokens, "", captureNanos);
        }

        static Snapshot unsupported(String problem) {
            return new Snapshot(null, Map.of(), Map.of(), problem, 0);
        }

        boolean supported() {
            return provider != null;
        }

        String providerId() {
            return supported() ? provider.id() : "";
        }

        String problem() {
            return problem;
        }

        long captureNanos() {
            return captureNanos;
        }

        ProofResult finish(
                String profileFingerprint,
                String manifestSha256,
                Collection<String> manifestPaths) {
            if (!supported()) {
                return ProofResult.unavailable(problem);
            }
            try {
                Map<Path, String> expected = new LinkedHashMap<>();
                Map<String, String> selected = new TreeMap<>();
                for (String raw : manifestPaths) {
                    String logical = ResourceIndex.normalizeLogicalPath(raw);
                    Path real = realByLogical.get(logical);
                    String token = tokens.get(logical);
                    if (real == null || token == null) {
                        throw new IOException("generation capture omitted prepared source " + logical);
                    }
                    String prior = expected.putIfAbsent(real, token);
                    if (prior != null && !prior.equals(token)) {
                        throw new IOException("one prepared source has conflicting generation tokens: " + real);
                    }
                    selected.put(logical, token);
                }
                long started = System.nanoTime();
                provider.requireMatches(expected);
                long validationNanos = System.nanoTime() - started;
                return ProofResult.available(
                        new TextureSourceGenerationProof(
                                profileFingerprint,
                                manifestSha256,
                                provider.id(),
                                selected),
                        validationNanos);
            } catch (Exception error) {
                return ProofResult.unavailable("Prepared texture source generation changed or became unavailable: "
                        + message(error));
            }
        }
    }

    record ProofResult(
            TextureSourceGenerationProof proof,
            String problem,
            long validationNanos) {
        static ProofResult available(TextureSourceGenerationProof proof, long validationNanos) {
            return new ProofResult(proof, "", validationNanos);
        }

        static ProofResult unavailable(String problem) {
            return new ProofResult(null, problem, 0);
        }

        boolean available() {
            return proof != null;
        }
    }

    record Validation(boolean valid, String problem, int checkedEntries, long durationNanos) {
        static Validation valid(int checkedEntries, long durationNanos) {
            return new Validation(true, "", checkedEntries, durationNanos);
        }

        static Validation invalid(String problem, long durationNanos) {
            return new Validation(false, problem, 0, durationNanos);
        }

        double durationMillis() {
            return durationNanos / 1_000_000.0;
        }
    }

    private static String encodePath(Path path) {
        return Base64.getEncoder().encodeToString(
                path.toString().getBytes(StandardCharsets.UTF_8));
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
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
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
                throw new IOException("Source generation provider exceeded " + TOOL_TIMEOUT.toSeconds() + " seconds");
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
