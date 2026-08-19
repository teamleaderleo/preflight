package dev.starsector.preflight.cli;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Launcher-only filesystem authority bound to one opened directory generation.
 *
 * <p>The initial path is traversed one component at a time without following aliases. Every later
 * sibling operation is relative to the retained directory descriptor/handle. The public pathname
 * is used only by {@link #requireCurrent()} to decide whether the reviewed generation still owns
 * that name; a replacement pathname is never used as a mutation root.
 */
final class IntegrationParentDirectory implements AutoCloseable {
    static final int MAX_REVIEW_FILE_BYTES = 256 * 1024;
    static final int MAX_REVIEW_TOTAL_ENTRIES = 64;
    static final int MAX_REVIEW_TOTAL_BYTES = 1024 * 1024;

    private final Path publicPath;
    private final Backend backend;
    private final Identity identity;
    private final ReviewBudget reviewBudget;
    private boolean closed;

    private IntegrationParentDirectory(Path publicPath, Backend backend, ReviewBudget reviewBudget)
            throws IOException {
        this.publicPath = publicPath.toAbsolutePath().normalize();
        this.backend = backend;
        this.identity = backend.identity();
        this.reviewBudget = reviewBudget;
    }

    static IntegrationParentDirectory ensureAndOpen(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        return fromBackend(absolute, openPath(absolute, true), null);
    }

    static IntegrationParentDirectory open(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        return fromBackend(absolute, openPath(absolute, false), null);
    }

    private static IntegrationParentDirectory fromBackend(
            Path publicPath, Backend backend, ReviewBudget reviewBudget) throws IOException {
        try {
            return new IntegrationParentDirectory(publicPath, backend, reviewBudget);
        } catch (IOException failure) {
            try {
                backend.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static Backend openPath(Path directory, boolean createMissing) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) return WindowsBackend.openPath(directory, createMissing);
            if (os.contains("mac") || os.contains("darwin")) return PosixBackend.openPath(directory, createMissing, true);
            if (os.contains("linux")) return PosixBackend.openPath(directory, createMissing, false);
            throw new IOException("Launcher parent authority is unsupported on this operating system: " + os);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError unavailable) {
            throw new IOException("Launcher parent authority native primitive is unavailable for " + directory, unavailable);
        }
    }

    Path publicPath() { return publicPath; }

    Path displayPath(String child) throws IOException { return publicPath.resolve(relative(child)); }

    void requireCurrent() throws IOException {
        ensureOpen();
        try (Backend current = openPath(publicPath, false)) {
            if (!identity.equals(current.identity())) throw changedParent();
        } catch (NoSuchFileException missing) {
            throw changedParent(missing);
        } catch (IOException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("Launcher mutation parent changed")) throw failure;
            throw changedParent(failure);
        }
    }

    Identity identity() throws IOException { ensureOpen(); return backend.identity(); }

    List<String> listNames() throws IOException {
        return listNames(MAX_REVIEW_TOTAL_ENTRIES);
    }

    List<String> listNames(int maxEntries) throws IOException {
        ensureOpen();
        if (maxEntries < 0) throw new IOException("Launcher review entry budget is negative");
        int effectiveLimit = reviewBudget == null
                ? maxEntries
                : Math.min(maxEntries, reviewBudget.remainingEntries());
        List<String> names = new ArrayList<>(backend.listNames(effectiveLimit));
        if (reviewBudget != null) reviewBudget.retainEntries(names.size());
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    IntegrationParentDirectory tryOpenDirectory(String child) throws IOException {
        ensureOpen();
        String name = relative(child);
        Backend opened = backend.tryOpenDirectory(name);
        if (opened == null) return null;
        ReviewBudget childBudget = reviewBudget == null ? ReviewBudget.forRootDirectory() : reviewBudget;
        return fromBackend(publicPath.resolve(name), opened, childBudget);
    }

    IntegrationParentDirectory openDirectory(String child) throws IOException {
        IntegrationParentDirectory opened = tryOpenDirectory(child);
        if (opened == null) throw new IOException("Launcher integration entry is not a directory: " + displayPath(child));
        return opened;
    }

    FileInfo readFile(String child) throws IOException {
        return readFile(child, MAX_REVIEW_FILE_BYTES);
    }

    FileInfo readFile(String child, int maxBytes) throws IOException {
        ensureOpen();
        if (maxBytes < 0) throw new IOException("Launcher review byte budget is negative");
        int effectiveLimit = reviewBudget == null
                ? maxBytes
                : Math.min(maxBytes, reviewBudget.remainingBytes());
        FileInfo file = backend.readFile(relative(child), effectiveLimit);
        if (reviewBudget != null) reviewBudget.retainBytes(file.bytes().length);
        return file;
    }

    boolean exists(String child) throws IOException { ensureOpen(); return backend.exists(relative(child)); }
    void createDirectory(String child) throws IOException { ensureOpen(); backend.createDirectory(relative(child)); }
    void createFile(String child, byte[] bytes, boolean executable) throws IOException { ensureOpen(); backend.createFile(relative(child), bytes, executable); }
    void moveNoReplace(String source, String target) throws IOException { ensureOpen(); backend.moveNoReplace(relative(source), relative(target)); }
    void deleteFile(String child) throws IOException { ensureOpen(); backend.deleteFile(relative(child)); }
    void deleteDirectory(String child) throws IOException { ensureOpen(); backend.deleteDirectory(relative(child)); }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        backend.close();
    }

    private String relative(String value) throws IOException {
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.getNameCount() != 1 || path.startsWith("..") || path.toString().equals(".")) {
            throw new IOException("Unsafe launcher parent-relative name: " + value);
        }
        return path.toString();
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Launcher mutation parent authority is already closed: " + publicPath);
    }

    private IOException changedParent() { return new IOException("Launcher mutation parent changed after review: " + publicPath); }
    private IOException changedParent(Throwable cause) { return new IOException("Launcher mutation parent changed after review: " + publicPath, cause); }

    private static IOException readLimitExceeded(String relative, int maxBytes) {
        return new IOException("Launcher integration file exceeds review byte limit of "
                + maxBytes + " bytes: " + relative);
    }

    private static IOException entryLimitExceeded(int maxEntries) {
        return new IOException("Launcher integration directory exceeds remaining review entry budget of "
                + maxEntries + " entries");
    }

    private static final class ReviewBudget {
        private int entries;
        private int bytes;

        private ReviewBudget(int entries) {
            this.entries = entries;
        }

        static ReviewBudget forRootDirectory() {
            return new ReviewBudget(1);
        }

        int remainingEntries() {
            return MAX_REVIEW_TOTAL_ENTRIES - entries;
        }

        int remainingBytes() {
            return MAX_REVIEW_TOTAL_BYTES - bytes;
        }

        void retainEntries(int count) throws IOException {
            if (count < 0 || count > remainingEntries()) {
                throw new IOException("Launcher integration snapshot exceeds review entry limit of "
                        + MAX_REVIEW_TOTAL_ENTRIES + " entries");
            }
            entries += count;
        }

        void retainBytes(int count) throws IOException {
            if (count < 0 || count > remainingBytes()) {
                throw new IOException("Launcher integration snapshot exceeds retained-byte review limit of "
                        + MAX_REVIEW_TOTAL_BYTES + " bytes");
            }
            bytes += count;
        }
    }

    record Identity(long device, long file) {}
    record FileInfo(Identity identity, byte[] bytes, boolean executable) {
        FileInfo { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private interface Backend extends AutoCloseable {
        Identity identity() throws IOException;
        List<String> listNames(int maxEntries) throws IOException;
        Backend tryOpenDirectory(String relative) throws IOException;
        FileInfo readFile(String relative, int maxBytes) throws IOException;
        boolean exists(String relative) throws IOException;
        void createDirectory(String relative) throws IOException;
        void createFile(String relative, byte[] bytes, boolean executable) throws IOException;
        void moveNoReplace(String source, String target) throws IOException;
        void deleteFile(String relative) throws IOException;
        void deleteDirectory(String relative) throws IOException;
        @Override void close() throws IOException;
    }

    private static final class PosixBackend implements Backend {
        private static final int EEXIST = 17;
        private static final int ENOENT = 2;
        private static final int ENOTDIR = 20;
        private static final int POSIX_O_RDONLY = 0;
        private static final int POSIX_O_WRONLY = 1;
        private static final int LINUX_O_CREAT = 0x40;
        private static final int LINUX_O_EXCL = 0x80;
        private static final int LINUX_O_DIRECTORY = 0x10000;
        private static final int LINUX_O_NOFOLLOW = 0x20000;
        private static final int LINUX_O_CLOEXEC = 0x80000;
        private static final int MAC_O_CREAT = 0x0200;
        private static final int MAC_O_EXCL = 0x0800;
        private static final int MAC_O_NOFOLLOW = 0x0100;
        private static final int MAC_O_DIRECTORY = 0x100000;
        private static final int MAC_O_CLOEXEC = 0x1000000;
        private static final int LINUX_AT_REMOVEDIR = 0x200;
        private static final int MAC_AT_REMOVEDIR = 0x0080;
        private static final int RENAME_NOREPLACE = 1;
        private static final int RENAME_EXCL = 0x00000004;
        private static final int READ_BUFFER_BYTES = 16 * 1024;

        private final boolean mac;
        private final int fd;
        private boolean closed;

        private PosixBackend(boolean mac, int fd) { this.mac = mac; this.fd = fd; }

        static PosixBackend openPath(Path directory, boolean createMissing, boolean mac) throws IOException {
            Path absolute = directory.toAbsolutePath().normalize();
            if (mac) absolute = macKernelPath(absolute);
            if (!absolute.isAbsolute() || absolute.getRoot() == null) throw new IOException("Launcher mutation parent must be absolute: " + absolute);
            int directoryFlags = POSIX_O_RDONLY | (mac ? MAC_O_DIRECTORY | MAC_O_NOFOLLOW | MAC_O_CLOEXEC : LINUX_O_DIRECTORY | LINUX_O_NOFOLLOW | LINUX_O_CLOEXEC);
            int current = PosixLibC.INSTANCE.open(absolute.getRoot().toString(), directoryFlags);
            if (current < 0) throw failure("open filesystem root", absolute, Native.getLastError());
            boolean success = false;
            try {
                Path relative = absolute.getRoot().relativize(absolute);
                for (Path component : relative) {
                    String name = component.toString();
                    int next = PosixLibC.INSTANCE.openat(current, name, directoryFlags, 0);
                    if (next < 0 && createMissing && Native.getLastError() == ENOENT) {
                        if (PosixLibC.INSTANCE.mkdirat(current, name, 0755) != 0) {
                            int error = Native.getLastError();
                            if (error != EEXIST) throw failure("create launcher parent directory", absolute, error);
                        }
                        next = PosixLibC.INSTANCE.openat(current, name, directoryFlags, 0);
                    }
                    if (next < 0) {
                        int error = Native.getLastError();
                        if (error == ENOENT) throw new NoSuchFileException(absolute.toString());
                        throw failure("open launcher parent without following aliases", absolute, error);
                    }
                    if (PosixLibC.INSTANCE.close(current) != 0) {
                        int error = Native.getLastError();
                        PosixLibC.INSTANCE.close(next);
                        throw failure("close launcher ancestor descriptor", absolute, error);
                    }
                    current = next;
                }
                success = true;
                return new PosixBackend(mac, current);
            } finally {
                if (!success) PosixLibC.INSTANCE.close(current);
            }
        }

        private static Path macKernelPath(Path absolute) {
            if (absolute.getRoot() == null || absolute.getNameCount() == 0) return absolute;
            String first = absolute.getName(0).toString();
            if (!first.equals("var") && !first.equals("tmp") && !first.equals("etc")) return absolute;
            return Path.of("/private").resolve(absolute.getRoot().relativize(absolute));
        }

        @Override public Identity identity() throws IOException { ensureOpen(); return identityFor(fd, mac); }

        @Override public List<String> listNames(int maxEntries) throws IOException {
            ensureOpen();
            int duplicated = PosixLibC.INSTANCE.dup(fd);
            if (duplicated < 0) throw failure("duplicate launcher directory descriptor", null, Native.getLastError());
            Pointer directory = PosixLibC.INSTANCE.fdopendir(duplicated);
            if (directory == null || Pointer.nativeValue(directory) == 0L) {
                int error = Native.getLastError();
                PosixLibC.INSTANCE.close(duplicated);
                throw failure("enumerate launcher directory", null, error);
            }
            List<String> result = new ArrayList<>();
            try {
                while (true) {
                    Pointer entry = PosixLibC.INSTANCE.readdir(directory);
                    if (entry == null || Pointer.nativeValue(entry) == 0L) break;
                    String name;
                    if (mac) {
                        int length = entry.getShort(18) & 0xffff;
                        if (length <= 0) continue;
                        name = new String(entry.getByteArray(21, length), StandardCharsets.UTF_8);
                    } else {
                        byte[] bytes = entry.getByteArray(19, 256);
                        int length = 0;
                        while (length < bytes.length && bytes[length] != 0) length++;
                        if (length == 0) continue;
                        name = new String(bytes, 0, length, StandardCharsets.UTF_8);
                    }
                    if (name.equals(".") || name.equals("..")) continue;
                    if (result.size() >= maxEntries) throw entryLimitExceeded(maxEntries);
                    result.add(name);
                }
            } finally {
                if (PosixLibC.INSTANCE.closedir(directory) != 0) throw failure("close launcher directory enumeration", null, Native.getLastError());
            }
            return result;
        }

        @Override public Backend tryOpenDirectory(String relative) throws IOException {
            ensureOpen();
            int flags = POSIX_O_RDONLY | (mac ? MAC_O_DIRECTORY | MAC_O_NOFOLLOW | MAC_O_CLOEXEC : LINUX_O_DIRECTORY | LINUX_O_NOFOLLOW | LINUX_O_CLOEXEC);
            int child = PosixLibC.INSTANCE.openat(fd, relative, flags, 0);
            if (child >= 0) return new PosixBackend(mac, child);
            int error = Native.getLastError();
            if (error == ENOTDIR || error == ENOENT) return null;
            throw failure("open launcher child directory without following aliases", Path.of(relative), error);
        }

        @Override public FileInfo readFile(String relative, int maxBytes) throws IOException {
            ensureOpen();
            int flags = POSIX_O_RDONLY | (mac ? MAC_O_NOFOLLOW | MAC_O_CLOEXEC : LINUX_O_NOFOLLOW | LINUX_O_CLOEXEC);
            int file = PosixLibC.INSTANCE.openat(fd, relative, flags, 0);
            if (file < 0) {
                int error = Native.getLastError();
                if (error == ENOENT) throw new NoSuchFileException(relative);
                throw failure("open launcher file without following aliases", Path.of(relative), error);
            }
            IOException pending = null;
            try {
                Stat stat = stat(file, mac);
                if ((stat.mode() & 0170000) != 0100000) throw new IOException("Launcher integration entry is not a regular file: " + relative);
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, READ_BUFFER_BYTES));
                Memory buffer = new Memory(READ_BUFFER_BYTES);
                while (true) {
                    int remaining = maxBytes - output.size();
                    int requested = Math.min(READ_BUFFER_BYTES, remaining + 1);
                    NativeLong value = PosixLibC.INSTANCE.read(file, buffer, new NativeLong(requested));
                    long count = value.longValue();
                    if (count < 0) throw failure("read launcher file", Path.of(relative), Native.getLastError());
                    if (count == 0) break;
                    if (count > remaining) throw readLimitExceeded(relative, maxBytes);
                    output.write(buffer.getByteArray(0, (int) count));
                }
                return new FileInfo(stat.identity(), output.toByteArray(), (stat.mode() & 0111) != 0);
            } catch (IOException failure) {
                pending = failure;
                throw failure;
            } finally {
                if (PosixLibC.INSTANCE.close(file) != 0 && pending == null) throw failure("close launcher file descriptor", Path.of(relative), Native.getLastError());
            }
        }

        @Override public boolean exists(String relative) throws IOException {
            try {
                Backend directory = tryOpenDirectory(relative);
                if (directory != null) { directory.close(); return true; }
                readFile(relative, MAX_REVIEW_FILE_BYTES);
                return true;
            } catch (NoSuchFileException missing) {
                return false;
            }
        }

        @Override public void createDirectory(String relative) throws IOException {
            ensureOpen();
            if (PosixLibC.INSTANCE.mkdirat(fd, relative, 0755) != 0) {
                int error = Native.getLastError();
                if (error == EEXIST) throw new FileAlreadyExistsException(relative);
                throw failure("create launcher staging directory", Path.of(relative), error);
            }
        }

        @Override public void createFile(String relative, byte[] bytes, boolean executable) throws IOException {
            ensureOpen();
            int flags = POSIX_O_WRONLY | (mac ? MAC_O_CREAT | MAC_O_EXCL | MAC_O_NOFOLLOW | MAC_O_CLOEXEC : LINUX_O_CREAT | LINUX_O_EXCL | LINUX_O_NOFOLLOW | LINUX_O_CLOEXEC);
            int mode = executable ? 0755 : 0644;
            int file = PosixLibC.INSTANCE.openat(fd, relative, flags, mode);
            if (file < 0) {
                int error = Native.getLastError();
                if (error == EEXIST) throw new FileAlreadyExistsException(relative);
                throw failure("create launcher staging file", Path.of(relative), error);
            }
            IOException pending = null;
            try {
                Memory buffer = new Memory(Math.max(1, bytes.length));
                if (bytes.length > 0) buffer.write(0, bytes, 0, bytes.length);
                long written = 0;
                while (written < bytes.length) {
                    NativeLong value = PosixLibC.INSTANCE.write(file, buffer.share(written), new NativeLong(bytes.length - written));
                    long count = value.longValue();
                    if (count <= 0) throw failure("write launcher staging file", Path.of(relative), Native.getLastError());
                    written += count;
                }
                if (PosixLibC.INSTANCE.fchmod(file, mode) != 0) throw failure("set launcher staging permissions", Path.of(relative), Native.getLastError());
                if (PosixLibC.INSTANCE.fsync(file) != 0) throw failure("sync launcher staging file", Path.of(relative), Native.getLastError());
            } catch (IOException failure) {
                pending = failure;
                throw failure;
            } finally {
                if (PosixLibC.INSTANCE.close(file) != 0 && pending == null) throw failure("close launcher staging file", Path.of(relative), Native.getLastError());
            }
        }

        @Override public void moveNoReplace(String source, String target) throws IOException {
            ensureOpen();
            int result = mac ? MacLibC.INSTANCE.renameatx_np(fd, source, fd, target, RENAME_EXCL) : LinuxLibC.INSTANCE.renameat2(fd, source, fd, target, RENAME_NOREPLACE);
            if (result == 0) return;
            int error = Native.getLastError();
            if (error == EEXIST) throw new FileAlreadyExistsException(target);
            if (error == ENOENT) throw new NoSuchFileException(source);
            throw failure(mac ? "renameatx_np(RENAME_EXCL)" : "renameat2(RENAME_NOREPLACE)", Path.of(source + " -> " + target), error);
        }

        @Override public void deleteFile(String relative) throws IOException {
            ensureOpen();
            if (PosixLibC.INSTANCE.unlinkat(fd, relative, 0) == 0) return;
            int error = Native.getLastError();
            if (error == ENOENT) return;
            throw failure("delete launcher file", Path.of(relative), error);
        }

        @Override public void deleteDirectory(String relative) throws IOException {
            ensureOpen();
            int flags = mac ? MAC_AT_REMOVEDIR : LINUX_AT_REMOVEDIR;
            if (PosixLibC.INSTANCE.unlinkat(fd, relative, flags) == 0) return;
            int error = Native.getLastError();
            if (error == ENOENT) return;
            throw failure("delete launcher directory", Path.of(relative), error);
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (PosixLibC.INSTANCE.close(fd) != 0) throw failure("close launcher directory descriptor", null, Native.getLastError());
        }

        private void ensureOpen() throws IOException { if (closed) throw new IOException("Launcher directory descriptor is already closed"); }
        private static Identity identityFor(int descriptor, boolean mac) throws IOException { return stat(descriptor, mac).identity(); }

        private static Stat stat(int descriptor, boolean mac) throws IOException {
            Memory value = new Memory(256);
            value.clear();
            if (PosixLibC.INSTANCE.fstat(descriptor, value) != 0) throw failure("inspect launcher entry identity", null, Native.getLastError());
            if (mac) {
                long device = Integer.toUnsignedLong(value.getInt(0));
                long inode = value.getLong(8);
                int mode = value.getShort(4) & 0xffff;
                return new Stat(new Identity(device, inode), mode);
            }
            long device = value.getLong(0);
            long inode = value.getLong(8);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            int modeOffset = (arch.contains("aarch64") || arch.contains("arm64")) ? 16 : 24;
            int mode = value.getInt(modeOffset);
            return new Stat(new Identity(device, inode), mode);
        }

        private static IOException failure(String operation, Path path, int error) {
            String suffix = path == null ? "" : ": " + path;
            return new IOException(operation + " failed (errno " + error + ")" + suffix);
        }
        private record Stat(Identity identity, int mode) {}
    }

    private static final class WindowsBackend implements Backend {
        private static final int FILE_LIST_DIRECTORY = 0x0001;
        private static final int FILE_READ_DATA = 0x0001;
        private static final int FILE_WRITE_DATA = 0x0002;
        private static final int FILE_TRAVERSE = 0x0020;
        private static final int FILE_READ_ATTRIBUTES = 0x0080;
        private static final int DELETE = 0x00010000;
        private static final int SYNCHRONIZE = 0x00100000;
        private static final int FILE_SHARE_READ = 0x00000001;
        private static final int FILE_SHARE_WRITE = 0x00000002;
        private static final int FILE_SHARE_DELETE = 0x00000004;
        private static final int OPEN_EXISTING = 3;
        private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
        private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;
        private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
        private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
        private static final int FILE_OPEN = 1;
        private static final int FILE_CREATE = 2;
        private static final int FILE_DIRECTORY_FILE = 0x00000001;
        private static final int FILE_SYNCHRONOUS_IO_NONALERT = 0x00000020;
        private static final int FILE_NON_DIRECTORY_FILE = 0x00000040;
        private static final int FILE_OPEN_REPARSE_POINT = 0x00200000;
        private static final int OBJ_CASE_INSENSITIVE = 0x00000040;
        private static final int FILE_RENAME_INFORMATION = 10;
        private static final int FILE_NAMES_INFORMATION = 12;
        private static final int FILE_DISPOSITION_INFORMATION = 13;
        private static final int STATUS_OBJECT_NAME_NOT_FOUND = (int) 0xC0000034L;
        private static final int STATUS_OBJECT_NAME_COLLISION = (int) 0xC0000035L;
        private static final int STATUS_OBJECT_PATH_NOT_FOUND = (int) 0xC000003AL;
        private static final int STATUS_NOT_A_DIRECTORY = (int) 0xC0000103L;
        private static final int STATUS_NO_MORE_FILES = (int) 0x80000006L;
        private static final int READ_BUFFER_BYTES = 16 * 1024;

        private final Pointer handle;
        private boolean closed;
        private WindowsBackend(Pointer handle) { this.handle = handle; }

        static WindowsBackend openPath(Path directory, boolean createMissing) throws IOException {
            Path absolute = directory.toAbsolutePath().normalize();
            Path root = absolute.getRoot();
            if (root == null) throw new IOException("Launcher mutation parent must be absolute: " + absolute);
            Pointer current = openRoot(root);
            boolean success = false;
            try {
                Path relative = root.relativize(absolute);
                for (Path component : relative) {
                    String name = component.toString();
                    Pointer next;
                    try {
                        next = openRelativeDirectory(current, name, FILE_OPEN);
                    } catch (NoSuchFileException missing) {
                        if (!createMissing) throw missing;
                        try { next = openRelativeDirectory(current, name, FILE_CREATE); }
                        catch (FileAlreadyExistsException collision) { next = openRelativeDirectory(current, name, FILE_OPEN); }
                    }
                    if (Kernel32.INSTANCE.CloseHandle(current) == 0) {
                        Kernel32.INSTANCE.CloseHandle(next);
                        throw win32Failure("close launcher ancestor handle");
                    }
                    current = next;
                }
                success = true;
                return new WindowsBackend(current);
            } finally {
                if (!success) Kernel32.INSTANCE.CloseHandle(current);
            }
        }

        @Override public Identity identity() throws IOException {
            ensureOpen();
            ByHandleFileInformation value = information(handle, "launcher directory");
            long device = Integer.toUnsignedLong(value.volumeSerialNumber);
            long file = (Integer.toUnsignedLong(value.fileIndexHigh) << 32) | Integer.toUnsignedLong(value.fileIndexLow);
            return new Identity(device, file);
        }

        @Override public List<String> listNames(int maxEntries) throws IOException {
            ensureOpen();
            List<String> result = new ArrayList<>();
            boolean restart = true;
            while (true) {
                Memory information = new Memory(64 * 1024L);
                information.clear();
                Memory iosb = ioStatusBlock();
                int status = Ntdll.INSTANCE.NtQueryDirectoryFile(handle, Pointer.NULL, Pointer.NULL, Pointer.NULL, iosb, information, (int) information.size(), FILE_NAMES_INFORMATION, (byte) 0, Pointer.NULL, restart ? (byte) 1 : (byte) 0);
                restart = false;
                if (status == STATUS_NO_MORE_FILES) break;
                requireNtSuccess("enumerate launcher directory", status);
                int offset = 0;
                while (offset >= 0 && offset + 12 <= information.size()) {
                    int next = information.getInt(offset);
                    int length = information.getInt(offset + 8L);
                    if (length < 0 || offset + 12L + length > information.size()) throw new IOException("Invalid native launcher directory enumeration result");
                    String name = new String(information.getByteArray(offset + 12L, length), StandardCharsets.UTF_16LE);
                    if (!name.equals(".") && !name.equals("..")) {
                        if (result.size() >= maxEntries) throw entryLimitExceeded(maxEntries);
                        result.add(name);
                    }
                    if (next == 0) break;
                    offset += next;
                }
            }
            return result;
        }

        @Override public Backend tryOpenDirectory(String relative) throws IOException {
            ensureOpen();
            try {
                Pointer child = openRelative(handle, relative, FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE, FILE_OPEN, FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
                return new WindowsBackend(child);
            } catch (NoSuchFileException | NotDirectory failure) { return null; }
        }

        @Override public FileInfo readFile(String relative, int maxBytes) throws IOException {
            ensureOpen();
            Pointer file = openRelative(handle, relative, FILE_READ_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE, FILE_OPEN, FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
            IOException pending = null;
            try {
                ByHandleFileInformation attributes = information(file, relative);
                long device = Integer.toUnsignedLong(attributes.volumeSerialNumber);
                long identity = (Integer.toUnsignedLong(attributes.fileIndexHigh) << 32) | Integer.toUnsignedLong(attributes.fileIndexLow);
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, READ_BUFFER_BYTES));
                Memory buffer = new Memory(READ_BUFFER_BYTES);
                while (true) {
                    int remaining = maxBytes - output.size();
                    int requested = Math.min(READ_BUFFER_BYTES, remaining + 1);
                    IntByReference count = new IntByReference();
                    if (Kernel32.INSTANCE.ReadFile(file, buffer, requested, count, Pointer.NULL) == 0) throw win32Failure("read launcher file");
                    if (count.getValue() == 0) break;
                    if (count.getValue() > remaining) throw readLimitExceeded(relative, maxBytes);
                    output.write(buffer.getByteArray(0, count.getValue()));
                }
                return new FileInfo(new Identity(device, identity), output.toByteArray(), false);
            } catch (IOException failure) {
                pending = failure;
                throw failure;
            } finally {
                if (Kernel32.INSTANCE.CloseHandle(file) == 0 && pending == null) throw win32Failure("close launcher file handle");
            }
        }

        @Override public boolean exists(String relative) throws IOException {
            try {
                Backend directory = tryOpenDirectory(relative);
                if (directory != null) { directory.close(); return true; }
                readFile(relative, MAX_REVIEW_FILE_BYTES);
                return true;
            } catch (NoSuchFileException missing) { return false; }
        }

        @Override public void createDirectory(String relative) throws IOException {
            ensureOpen();
            Pointer created = openRelative(handle, relative, FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE, FILE_CREATE, FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
            if (Kernel32.INSTANCE.CloseHandle(created) == 0) throw win32Failure("close launcher staging directory handle");
        }

        @Override public void createFile(String relative, byte[] bytes, boolean executable) throws IOException {
            ensureOpen();
            Pointer file = openRelative(handle, relative, FILE_WRITE_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE, FILE_CREATE, FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
            IOException pending = null;
            try {
                Memory buffer = new Memory(Math.max(1, bytes.length));
                if (bytes.length > 0) buffer.write(0, bytes, 0, bytes.length);
                int written = 0;
                while (written < bytes.length) {
                    IntByReference count = new IntByReference();
                    if (Kernel32.INSTANCE.WriteFile(file, buffer.share(written), bytes.length - written, count, Pointer.NULL) == 0 || count.getValue() <= 0) throw win32Failure("write launcher staging file");
                    written += count.getValue();
                }
                if (Kernel32.INSTANCE.FlushFileBuffers(file) == 0) throw win32Failure("sync launcher staging file");
            } catch (IOException failure) {
                pending = failure;
                throw failure;
            } finally {
                if (Kernel32.INSTANCE.CloseHandle(file) == 0 && pending == null) throw win32Failure("close launcher staging file handle");
            }
        }

        @Override public void moveNoReplace(String source, String target) throws IOException {
            ensureOpen();
            Pointer file = openRelative(handle, source, DELETE | FILE_READ_ATTRIBUTES | SYNCHRONIZE, FILE_OPEN, FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
            try {
                Memory information = renameInformation(handle, target);
                int status = Ntdll.INSTANCE.NtSetInformationFile(file, ioStatusBlock(), information, (int) information.size(), FILE_RENAME_INFORMATION);
                if (status == STATUS_OBJECT_NAME_COLLISION) throw new FileAlreadyExistsException(target);
                requireNtSuccess("rename launcher entry relative to reviewed parent", status);
            } finally { Kernel32.INSTANCE.CloseHandle(file); }
        }

        @Override public void deleteFile(String relative) throws IOException { deleteRelative(relative, false); }
        @Override public void deleteDirectory(String relative) throws IOException { deleteRelative(relative, true); }

        private void deleteRelative(String relative, boolean directory) throws IOException {
            ensureOpen();
            Pointer file;
            try {
                file = openRelative(handle, relative, DELETE | FILE_READ_ATTRIBUTES | SYNCHRONIZE, FILE_OPEN, (directory ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE) | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
            } catch (NoSuchFileException missing) { return; }
            try {
                Memory disposition = new Memory(1);
                disposition.setByte(0, (byte) 1);
                int status = Ntdll.INSTANCE.NtSetInformationFile(file, ioStatusBlock(), disposition, 1, FILE_DISPOSITION_INFORMATION);
                requireNtSuccess("delete launcher entry relative to reviewed parent", status);
            } finally { Kernel32.INSTANCE.CloseHandle(file); }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (Kernel32.INSTANCE.CloseHandle(handle) == 0) throw win32Failure("close launcher directory handle");
        }

        private void ensureOpen() throws IOException { if (closed) throw new IOException("Launcher directory handle is already closed"); }

        private static Pointer openRoot(Path root) throws IOException {
            Pointer opened = Kernel32.INSTANCE.CreateFileW(new WString(root.toString()), FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, Pointer.NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, Pointer.NULL);
            if (opened == null || Pointer.nativeValue(opened) == -1L) throw win32Failure("open launcher filesystem root");
            try { requireNotReparse(opened, root.toString()); return opened; }
            catch (IOException failure) { Kernel32.INSTANCE.CloseHandle(opened); throw failure; }
        }

        private static Pointer openRelativeDirectory(Pointer root, String relative, int disposition) throws IOException {
            return openRelative(root, relative, FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE, disposition, FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
        }

        private static Pointer openRelative(Pointer root, String relative, int desiredAccess, int disposition, int options) throws IOException {
            NativeUnicodeString name = new NativeUnicodeString(relative);
            ObjectAttributes attributes = new ObjectAttributes(root, name);
            PointerByReference result = new PointerByReference();
            int status = Ntdll.INSTANCE.NtCreateFile(result, desiredAccess, attributes.getPointer(), ioStatusBlock(), Pointer.NULL, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, disposition, options, Pointer.NULL, 0);
            if (status == STATUS_OBJECT_NAME_NOT_FOUND || status == STATUS_OBJECT_PATH_NOT_FOUND) throw new NoSuchFileException(relative);
            if (status == STATUS_OBJECT_NAME_COLLISION) throw new FileAlreadyExistsException(relative);
            if (status == STATUS_NOT_A_DIRECTORY) throw new NotDirectory(relative);
            requireNtSuccess("open launcher entry relative to reviewed parent", status);
            Pointer opened = result.getValue();
            try { requireNotReparse(opened, relative); return opened; }
            catch (IOException failure) { Kernel32.INSTANCE.CloseHandle(opened); throw failure; }
        }

        private static ByHandleFileInformation information(Pointer file, String label) throws IOException {
            ByHandleFileInformation value = new ByHandleFileInformation();
            if (Kernel32.INSTANCE.GetFileInformationByHandle(file, value) == 0) throw win32Failure("inspect launcher entry " + label);
            value.read();
            return value;
        }

        private static void requireNotReparse(Pointer file, String label) throws IOException {
            if ((information(file, label).fileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) throw new IOException("Launcher integration path contains a reparse point: " + label);
        }

        private static Memory renameInformation(Pointer rootDirectory, String name) {
            byte[] encoded = name.getBytes(StandardCharsets.UTF_16LE);
            int rootOffset = Native.POINTER_SIZE == 8 ? 8 : 4;
            int lengthOffset = rootOffset + Native.POINTER_SIZE;
            int nameOffset = lengthOffset + Integer.BYTES;
            Memory information = new Memory(nameOffset + encoded.length);
            information.clear();
            information.setByte(0, (byte) 0);
            information.setPointer(rootOffset, rootDirectory);
            information.setInt(lengthOffset, encoded.length);
            information.write(nameOffset, encoded, 0, encoded.length);
            return information;
        }

        private static Memory ioStatusBlock() { Memory block = new Memory(Native.POINTER_SIZE * 2L); block.clear(); return block; }
        private static void requireNtSuccess(String operation, int status) throws IOException { if (status < 0) throw new IOException(operation + " failed (NTSTATUS 0x" + Integer.toHexString(status) + ")"); }
        private static IOException win32Failure(String operation) { return new IOException(operation + " failed (Win32 error " + Native.getLastError() + ")"); }
        private static final class NotDirectory extends IOException { NotDirectory(String path) { super(path); } }
    }

    private static final class NativeUnicodeString {
        private final Memory buffer;
        private final UnicodeString value;
        NativeUnicodeString(String text) {
            byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
            this.buffer = new Memory(encoded.length + 2L);
            buffer.clear();
            buffer.write(0, encoded, 0, encoded.length);
            this.value = new UnicodeString();
            value.length = (short) encoded.length;
            value.maximumLength = (short) (encoded.length + 2);
            value.buffer = buffer;
            value.write();
        }
        Pointer pointer() { return value.getPointer(); }
    }

    @Structure.FieldOrder({"length", "maximumLength", "buffer"})
    public static final class UnicodeString extends Structure { public short length; public short maximumLength; public Pointer buffer; }

    @Structure.FieldOrder({"length", "rootDirectory", "objectName", "attributes", "securityDescriptor", "securityQualityOfService"})
    public static final class ObjectAttributes extends Structure {
        public int length; public Pointer rootDirectory; public Pointer objectName; public int attributes; public Pointer securityDescriptor; public Pointer securityQualityOfService;
        ObjectAttributes(Pointer rootDirectory, NativeUnicodeString name) {
            this.rootDirectory = rootDirectory; this.objectName = name.pointer(); this.attributes = WindowsBackend.OBJ_CASE_INSENSITIVE; this.securityDescriptor = Pointer.NULL; this.securityQualityOfService = Pointer.NULL; this.length = size(); write();
        }
    }

    @Structure.FieldOrder({"fileAttributes", "creationTimeLow", "creationTimeHigh", "lastAccessTimeLow", "lastAccessTimeHigh", "lastWriteTimeLow", "lastWriteTimeHigh", "volumeSerialNumber", "fileSizeHigh", "fileSizeLow", "numberOfLinks", "fileIndexHigh", "fileIndexLow"})
    public static final class ByHandleFileInformation extends Structure {
        public int fileAttributes; public int creationTimeLow; public int creationTimeHigh; public int lastAccessTimeLow; public int lastAccessTimeHigh; public int lastWriteTimeLow; public int lastWriteTimeHigh; public int volumeSerialNumber; public int fileSizeHigh; public int fileSizeLow; public int numberOfLinks; public int fileIndexHigh; public int fileIndexLow;
    }

    private interface PosixLibC extends Library {
        PosixLibC INSTANCE = Native.load("c", PosixLibC.class);
        int open(String path, int flags); int openat(int dirfd, String path, int flags, int mode); int mkdirat(int dirfd, String path, int mode); int unlinkat(int dirfd, String path, int flags); int fstat(int fd, Pointer stat); int dup(int fd); Pointer fdopendir(int fd); Pointer readdir(Pointer directory); int closedir(Pointer directory); NativeLong read(int fd, Pointer buffer, NativeLong count); NativeLong write(int fd, Pointer buffer, NativeLong count); int fchmod(int fd, int mode); int fsync(int fd); int close(int fd);
    }
    private interface LinuxLibC extends Library { LinuxLibC INSTANCE = Native.load("c", LinuxLibC.class); int renameat2(int oldDirFd, String oldPath, int newDirFd, String newPath, int flags); }
    private interface MacLibC extends Library { MacLibC INSTANCE = Native.load("c", MacLibC.class); int renameatx_np(int oldDirFd, String oldPath, int newDirFd, String newPath, int flags); }
    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        Pointer CreateFileW(WString fileName, int desiredAccess, int shareMode, Pointer securityAttributes, int creationDisposition, int flagsAndAttributes, Pointer templateFile);
        int GetFileInformationByHandle(Pointer file, ByHandleFileInformation information); int ReadFile(Pointer file, Pointer buffer, int bytesToRead, IntByReference bytesRead, Pointer overlapped); int WriteFile(Pointer file, Pointer buffer, int bytesToWrite, IntByReference bytesWritten, Pointer overlapped); int FlushFileBuffers(Pointer file); int CloseHandle(Pointer handle);
    }
    private interface Ntdll extends StdCallLibrary {
        Ntdll INSTANCE = Native.load("ntdll", Ntdll.class);
        int NtCreateFile(PointerByReference fileHandle, int desiredAccess, Pointer objectAttributes, Pointer ioStatusBlock, Pointer allocationSize, int fileAttributes, int shareAccess, int createDisposition, int createOptions, Pointer eaBuffer, int eaLength);
        int NtSetInformationFile(Pointer fileHandle, Pointer ioStatusBlock, Pointer fileInformation, int length, int fileInformationClass);
        int NtQueryDirectoryFile(Pointer fileHandle, Pointer event, Pointer apcRoutine, Pointer apcContext, Pointer ioStatusBlock, Pointer fileInformation, int length, int fileInformationClass, byte returnSingleEntry, Pointer fileName, byte restartScan);
    }
}
