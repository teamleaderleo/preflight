package dev.starsector.preflight.cli;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Exact file-generation evidence bound to the same opened object that supplies content bytes.
 *
 * <p>The public pathname is used only to select the initial object and to prove that the same
 * generation still owns that name before evidence is published. The content read itself stays on
 * one native descriptor/handle. This avoids both pathname A->B->A races and the ctime/USN
 * self-invalidation caused by the older temporary-hard-link anchor.
 */
final class OpenedFileGenerationAuthority {
    static final String LINUX_PROVIDER = "linux-open-ctime-v1";
    static final String MAC_PROVIDER = "macos-open-ctime-v1";
    static final String WINDOWS_PROVIDER = "windows-open-usn-v1";

    private static final Set<String> LINUX_FILESYSTEMS = Set.of(
            "ext4", "xfs", "btrfs", "f2fs", "overlay", "tmpfs");
    private static final Set<String> MAC_FILESYSTEMS = Set.of("apfs");

    private OpenedFileGenerationAuthority() {
    }

    static boolean supportsLinuxFilesystem(String filesystem) {
        return filesystem != null && LINUX_FILESYSTEMS.contains(filesystem.toLowerCase(Locale.ROOT));
    }

    static boolean supportsMacFilesystem(String filesystem) {
        return filesystem != null && MAC_FILESYSTEMS.contains(filesystem.toLowerCase(Locale.ROOT));
    }

    static Generation capture(Path path) throws IOException {
        Path absolute = absolute(path);
        try (OpenedFile opened = open(absolute)) {
            Generation generation = opened.generation();
            Generation after = opened.generation();
            if (!generation.equals(after)) {
                throw new IOException("File generation changed while it was being observed: " + absolute);
            }
            return generation;
        }
    }

    static Map<Path, Generation> captureAll(Collection<Path> paths) throws IOException {
        LinkedHashMap<Path, Generation> generations = new LinkedHashMap<>();
        for (Path path : paths) {
            Path absolute = absolute(path);
            generations.putIfAbsent(absolute, capture(absolute));
        }
        return Map.copyOf(generations);
    }

    static void requireCurrent(Path path, Generation expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        Generation current;
        try {
            current = capture(path);
        } catch (IOException unavailable) {
            throw new StaleGenerationException(
                    "Public pathname no longer names the reviewed file generation: " + absolute(path),
                    unavailable);
        }
        if (!expected.equals(current)) {
            throw new StaleGenerationException(
                    "Public pathname no longer names the reviewed file generation: " + absolute(path));
        }
    }

    static HashEvidence hash(Path path, Generation expected) throws IOException {
        return hash(path, expected, (ignored, input) -> Hashes.sha256(input));
    }

    static HashEvidence hash(Path path, Generation expected, StreamHasher hasher) throws IOException {
        Path absolute = absolute(path);
        Objects.requireNonNull(hasher, "hasher");
        OpenedFile opened;
        try {
            opened = open(absolute);
        } catch (IOException unavailable) {
            if (expected != null) {
                throw new StaleGenerationException(
                        "Reviewed file generation cannot be reopened for an exact content read: " + absolute,
                        unavailable);
            }
            throw unavailable;
        }
        try (opened) {
            Generation before = opened.generation();
            if (expected != null && !expected.equals(before)) {
                throw new StaleGenerationException(
                        "File generation no longer matches reviewed evidence: " + absolute);
            }

            String digest = hasher.sha256(absolute, opened.input());
            Generation after = opened.generation();
            if (!before.equals(after)) {
                throw new StaleGenerationException(
                        "File generation changed during content read: " + absolute);
            }
            if (expected != null && !expected.equals(after)) {
                throw new StaleGenerationException(
                        "File generation changed from reviewed evidence during content read: " + absolute);
            }

            requireCurrent(absolute, after);
            return new HashEvidence(after, digest);
        }
    }

    private static OpenedFile open(Path path) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsOpenedFile(path);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new PosixOpenedFile(path, true);
        }
        if (os.contains("linux")) {
            return new PosixOpenedFile(path, false);
        }
        throw new IOException("No reviewed opened-file generation provider for operating system: " + os);
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    static final class StaleGenerationException extends IOException {
        StaleGenerationException(String message) {
            super(message);
        }

        StaleGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record Generation(String provider, String token) {
        Generation {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("generation provider is required");
            }
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("generation token is required");
            }
        }
    }

    record HashEvidence(Generation generation, String sha256) {
        HashEvidence {
            Objects.requireNonNull(generation, "generation");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be lowercase hexadecimal SHA-256");
            }
        }
    }

    @FunctionalInterface
    interface StreamHasher {
        String sha256(Path publicPath, InputStream input) throws IOException;
    }

    private interface OpenedFile extends AutoCloseable {
        Generation generation() throws IOException;
        InputStream input();
        @Override void close() throws IOException;
    }

    private static final class PosixOpenedFile implements OpenedFile {
        private static final int O_RDONLY = 0;
        private static final int LINUX_O_NOFOLLOW = 0x20000;
        private static final int LINUX_O_CLOEXEC = 0x80000;
        private static final int MAC_O_NOFOLLOW = 0x0100;
        private static final int MAC_O_CLOEXEC = 0x01000000;

        private final Path publicPath;
        private final boolean mac;
        private final int fd;
        private final Path descriptorPath;
        private final String filesystem;
        private final NativeInputStream input;
        private boolean closed;

        PosixOpenedFile(Path path, boolean mac) throws IOException {
            this.publicPath = path;
            this.mac = mac;
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Exact-content source is not a real regular file: " + path);
            }
            int flags = O_RDONLY | (mac
                    ? MAC_O_NOFOLLOW | MAC_O_CLOEXEC
                    : LINUX_O_NOFOLLOW | LINUX_O_CLOEXEC);
            this.fd = PosixLibC.INSTANCE.open(path.toString(), flags);
            if (fd < 0) {
                throw posixFailure("open exact-content source", Native.getLastError());
            }
            this.descriptorPath = mac
                    ? Path.of("/dev/fd", Integer.toString(fd))
                    : Path.of("/proc/self/fd", Integer.toString(fd));
            this.input = new NativeInputStream(fd);
            try {
                requireRegularDescriptor();
                String handledFilesystem;
                if (mac) {
                    handledFilesystem = macFileSystemType(fd);
                } else {
                    FileStore store = Files.getFileStore(descriptorPath);
                    handledFilesystem = store.type().toLowerCase(Locale.ROOT);
                }
                boolean reviewed = mac
                        ? supportsMacFilesystem(handledFilesystem)
                        : supportsLinuxFilesystem(handledFilesystem);
                if (!reviewed) {
                    throw new IOException("Opened filesystem has no reviewed ctime authority: "
                            + handledFilesystem + " for " + path);
                }
                this.filesystem = handledFilesystem;
            } catch (IOException failure) {
                PosixLibC.INSTANCE.close(fd);
                closed = true;
                throw failure;
            }
        }

        @Override
        public Generation generation() throws IOException {
            ensureOpen();
            requireRegularDescriptor();
            Map<String, Object> attributes = Files.readAttributes(
                    descriptorPath,
                    "unix:dev,ino,ctime");
            Object dev = attributes.get("dev");
            Object ino = attributes.get("ino");
            Object ctime = attributes.get("ctime");
            if (!(dev instanceof Number device)
                    || !(ino instanceof Number inode)
                    || !(ctime instanceof FileTime changed)) {
                throw new IOException("Opened file omitted dev/ino/ctime generation fields: " + publicPath);
            }
            String provider = mac ? MAC_PROVIDER : LINUX_PROVIDER;
            String token = filesystem
                    + ':' + Long.toUnsignedString(device.longValue())
                    + ':' + Long.toUnsignedString(inode.longValue())
                    + ':' + changed.to(TimeUnit.NANOSECONDS);
            return new Generation(provider, token);
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            input.markClosed();
            if (PosixLibC.INSTANCE.close(fd) != 0) {
                throw posixFailure("close exact-content source", Native.getLastError());
            }
        }

        private void requireRegularDescriptor() throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    descriptorPath, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IOException("Opened exact-content source is not a regular file: " + publicPath);
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Exact-content source handle is already closed: " + publicPath);
            }
        }
    }

    private static final class NativeInputStream extends InputStream {
        private final int fd;
        private boolean closed;

        NativeInputStream(int fd) {
            this.fd = fd;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed) throw new IOException("Exact-content stream is closed");
            if (length == 0) return 0;
            Memory buffer = new Memory(length);
            NativeLong result = PosixLibC.INSTANCE.read(fd, buffer, new NativeLong(length));
            long count = result.longValue();
            if (count < 0) throw posixFailure("read exact-content source", Native.getLastError());
            if (count == 0) return -1;
            int copied = Math.toIntExact(count);
            buffer.read(0, bytes, offset, copied);
            return copied;
        }

        @Override
        public void close() {
            // The owning OpenedFile controls the descriptor lifetime.
        }

        void markClosed() {
            closed = true;
        }
    }

    private static final class WindowsOpenedFile implements OpenedFile {
        private static final int FILE_READ_DATA = 0x0001;
        private static final int FILE_READ_ATTRIBUTES = 0x0080;
        private static final int SYNCHRONIZE = 0x00100000;
        private static final int FILE_SHARE_READ = 0x00000001;
        private static final int FILE_SHARE_DELETE = 0x00000004;
        private static final int OPEN_EXISTING = 3;
        private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;
        private static final int FILE_ATTRIBUTE_DIRECTORY = 0x00000010;
        private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
        private static final int FILE_FLAG_SEQUENTIAL_SCAN = 0x08000000;
        private static final int FSCTL_READ_FILE_USN_DATA = 0x000900EB;

        private final Path publicPath;
        private final Pointer handle;
        private final WindowsInputStream input;
        private boolean closed;

        WindowsOpenedFile(Path path) throws IOException {
            this.publicPath = path;
            this.handle = Kernel32.INSTANCE.CreateFileW(
                    new WString(path.toString()),
                    FILE_READ_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                    FILE_SHARE_READ | FILE_SHARE_DELETE,
                    Pointer.NULL,
                    OPEN_EXISTING,
                    FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_SEQUENTIAL_SCAN,
                    Pointer.NULL);
            if (handle == null || Pointer.nativeValue(handle) == -1L) {
                throw windowsFailure("open exact-content source");
            }
            this.input = new WindowsInputStream(handle);
            try {
                requireRegularNonReparse();
            } catch (IOException failure) {
                Kernel32.INSTANCE.CloseHandle(handle);
                closed = true;
                throw failure;
            }
        }

        @Override
        public Generation generation() throws IOException {
            ensureOpen();
            ByHandleFileInformation information = information();
            long usn = latestUsn();
            String token = Integer.toUnsignedString(information.volumeSerialNumber, 16)
                    + ':' + Integer.toUnsignedString(information.fileIndexHigh, 16)
                    + ':' + Integer.toUnsignedString(information.fileIndexLow, 16)
                    + ':' + Long.toUnsignedString(usn, 16);
            return new Generation(WINDOWS_PROVIDER, token);
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            input.markClosed();
            if (Kernel32.INSTANCE.CloseHandle(handle) == 0) {
                throw windowsFailure("close exact-content source");
            }
        }

        private ByHandleFileInformation information() throws IOException {
            ByHandleFileInformation information = new ByHandleFileInformation();
            if (Kernel32.INSTANCE.GetFileInformationByHandle(handle, information) == 0) {
                throw windowsFailure("inspect exact-content source");
            }
            information.read();
            if ((information.fileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw new IOException("Exact-content source is a reparse point: " + publicPath);
            }
            if ((information.fileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                throw new IOException("Exact-content source is a directory: " + publicPath);
            }
            return information;
        }

        private void requireRegularNonReparse() throws IOException {
            information();
        }

        private long latestUsn() throws IOException {
            Memory request = new Memory(Short.BYTES * 2L);
            request.setShort(0, (short) 2);
            request.setShort(Short.BYTES, (short) 2);
            Memory output = new Memory(512);
            IntByReference returned = new IntByReference();
            int ok = Kernel32.INSTANCE.DeviceIoControl(
                    handle,
                    FSCTL_READ_FILE_USN_DATA,
                    request,
                    (int) request.size(),
                    output,
                    (int) output.size(),
                    returned,
                    Pointer.NULL);
            if (ok == 0) throw windowsFailure("read exact-content USN generation");
            if (returned.getValue() < 32 || Short.toUnsignedInt(output.getShort(4)) != 2) {
                throw new IOException("Exact-content source returned an unsupported USN record: " + publicPath);
            }
            return output.getLong(24);
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Exact-content source handle is already closed: " + publicPath);
            }
        }
    }

    private static final class WindowsInputStream extends InputStream {
        private final Pointer handle;
        private boolean closed;

        WindowsInputStream(Pointer handle) {
            this.handle = handle;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed) throw new IOException("Exact-content stream is closed");
            if (length == 0) return 0;
            Memory buffer = new Memory(length);
            IntByReference count = new IntByReference();
            int ok = Kernel32.INSTANCE.ReadFile(handle, buffer, length, count, Pointer.NULL);
            if (ok == 0) throw windowsFailure("read exact-content source");
            int read = count.getValue();
            if (read == 0) return -1;
            buffer.read(0, bytes, offset, read);
            return read;
        }

        @Override
        public void close() {
            // The owning OpenedFile controls the handle lifetime.
        }

        void markClosed() {
            closed = true;
        }
    }

    private static String macFileSystemType(int fd) throws IOException {
        MacStatFs information = new MacStatFs();
        if (PosixLibC.INSTANCE.fstatfs(fd, information) != 0) {
            throw posixFailure("inspect opened filesystem", Native.getLastError());
        }
        information.read();
        int length = 0;
        while (length < information.fileSystemTypeName.length
                && information.fileSystemTypeName[length] != 0) {
            length++;
        }
        String type = new String(
                information.fileSystemTypeName,
                0,
                length,
                StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        if (type.isBlank()) {
            throw new IOException("Opened filesystem omitted its filesystem type");
        }
        return type;
    }

    @Structure.FieldOrder({
        "blockSize", "ioSize", "blocks", "blocksFree", "blocksAvailable", "files", "filesFree",
        "fileSystemId", "owner", "type", "flags", "subtype", "fileSystemTypeName", "mountPoint",
        "mountedFrom", "extendedFlags", "reserved"
    })
    public static final class MacStatFs extends Structure {
        public int blockSize;
        public int ioSize;
        public long blocks;
        public long blocksFree;
        public long blocksAvailable;
        public long files;
        public long filesFree;
        public int[] fileSystemId = new int[2];
        public int owner;
        public int type;
        public int flags;
        public int subtype;
        public byte[] fileSystemTypeName = new byte[16];
        public byte[] mountPoint = new byte[1024];
        public byte[] mountedFrom = new byte[1024];
        public int extendedFlags;
        public int[] reserved = new int[7];
    }

    @Structure.FieldOrder({
        "fileAttributes", "creationTimeLow", "creationTimeHigh", "lastAccessTimeLow", "lastAccessTimeHigh",
        "lastWriteTimeLow", "lastWriteTimeHigh", "volumeSerialNumber", "fileSizeHigh", "fileSizeLow",
        "numberOfLinks", "fileIndexHigh", "fileIndexLow"
    })
    public static final class ByHandleFileInformation extends Structure {
        public int fileAttributes;
        public int creationTimeLow;
        public int creationTimeHigh;
        public int lastAccessTimeLow;
        public int lastAccessTimeHigh;
        public int lastWriteTimeLow;
        public int lastWriteTimeHigh;
        public int volumeSerialNumber;
        public int fileSizeHigh;
        public int fileSizeLow;
        public int numberOfLinks;
        public int fileIndexHigh;
        public int fileIndexLow;
    }

    private interface PosixLibC extends Library {
        PosixLibC INSTANCE = Native.load("c", PosixLibC.class);
        int open(String path, int flags);
        NativeLong read(int fd, Pointer buffer, NativeLong count);
        int fstatfs(int fd, MacStatFs information);
        int close(int fd);
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        Pointer CreateFileW(
                WString fileName,
                int desiredAccess,
                int shareMode,
                Pointer securityAttributes,
                int creationDisposition,
                int flagsAndAttributes,
                Pointer templateFile);
        int GetFileInformationByHandle(Pointer file, ByHandleFileInformation information);
        int ReadFile(Pointer file, Pointer buffer, int bytesToRead, IntByReference bytesRead, Pointer overlapped);
        int DeviceIoControl(
                Pointer device,
                int controlCode,
                Pointer input,
                int inputSize,
                Pointer output,
                int outputSize,
                IntByReference bytesReturned,
                Pointer overlapped);
        int CloseHandle(Pointer handle);
    }

    private static IOException posixFailure(String operation, int error) {
        return new IOException(operation + " failed (errno " + error + ")");
    }

    private static IOException windowsFailure(String operation) {
        return new IOException(operation + " failed (Win32 error " + Native.getLastError() + ")");
    }
}
