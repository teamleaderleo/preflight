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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Filesystem operations bound to one opened directory generation.
 *
 * <p>Unix operations use *at syscalls against an open directory descriptor. Windows operations use
 * NtCreateFile and NtSetInformationFile with that directory handle as RootDirectory. In either
 * case, replacing the public parent pathname cannot redirect capture, publication, rollback, or
 * cleanup to a different directory generation.
 */
final class ParentBoundDirectory implements AutoCloseable {
    private static final int EEXIST = 17;
    private static final int ENOENT = 2;
    private static final int LINUX_AT_REMOVEDIR = 0x200;
    private static final int MAC_AT_REMOVEDIR = 0x0080;
    private static final int POSIX_O_RDONLY = 0;
    private static final int POSIX_O_WRONLY = 1;
    private static final int LINUX_O_CREAT = 0x40;
    private static final int LINUX_O_EXCL = 0x80;
    private static final int LINUX_O_NOFOLLOW = 0x20000;
    private static final int MAC_O_CREAT = 0x0200;
    private static final int MAC_O_EXCL = 0x0800;
    private static final int MAC_O_NOFOLLOW = 0x0100;
    private static final int F_GETPATH = 50;
    private static final int MAXPATHLEN = 1024;

    private final Path directory;
    private final Backend backend;

    private ParentBoundDirectory(Path directory, Backend backend) {
        this.directory = directory;
        this.backend = backend;
    }

    static ParentBoundDirectory open(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Mutation parent is not a real directory: " + absolute);
        }
        Backend backend = isWindows()
                ? new WindowsBackend(absolute)
                : new PosixBackend(absolute);
        ParentBoundDirectory opened = new ParentBoundDirectory(absolute, backend);
        try {
            opened.requireCurrent();
            return opened;
        } catch (IOException failure) {
            opened.close();
            throw failure;
        }
    }

    void requireCurrent() throws IOException {
        backend.requireCurrent();
    }

    void createDirectory(String relative) throws IOException {
        backend.createDirectory(relative(relative));
    }

    void createFile(String relative, byte[] bytes, int mode) throws IOException {
        backend.createFile(relative(relative), bytes, mode);
    }

    byte[] readFile(String relative) throws IOException {
        return backend.readFile(relative(relative));
    }

    void link(String existing, String link) throws IOException {
        backend.link(relative(existing), relative(link));
    }

    void capture(String source, String captured) throws IOException {
        backend.capture(relative(source), relative(captured));
    }

    boolean sameFile(String left, String right) throws IOException {
        return backend.sameFile(relative(left), relative(right));
    }

    boolean existsFile(String relative) throws IOException {
        return backend.existsFile(relative(relative));
    }

    void deleteFile(String relative) throws IOException {
        backend.deleteFile(relative(relative));
    }

    void deleteDirectory(String relative) throws IOException {
        backend.deleteDirectory(relative(relative));
    }

    @Override
    public void close() throws IOException {
        backend.close();
    }

    private String relative(String relative) throws IOException {
        Path value = Path.of(relative).normalize();
        if (value.isAbsolute() || value.getNameCount() != 1 || value.startsWith("..")) {
            throw new IOException("Unsafe parent-bound relative path: " + relative);
        }
        Path resolved = directory.resolve(value).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IOException("Parent-bound path escapes its directory: " + relative);
        }
        return value.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private interface Backend extends AutoCloseable {
        void requireCurrent() throws IOException;
        void createDirectory(String relative) throws IOException;
        void createFile(String relative, byte[] bytes, int mode) throws IOException;
        byte[] readFile(String relative) throws IOException;
        void link(String existing, String link) throws IOException;
        void capture(String source, String captured) throws IOException;
        boolean sameFile(String left, String right) throws IOException;
        boolean existsFile(String relative) throws IOException;
        void deleteFile(String relative) throws IOException;
        void deleteDirectory(String relative) throws IOException;
        @Override void close() throws IOException;
    }

    private static final class PosixBackend implements Backend {
        private final Path directory;
        private final boolean mac;
        private final int fd;
        private boolean closed;

        PosixBackend(Path directory) throws IOException {
            this.directory = directory;
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            this.mac = os.contains("mac") || os.contains("darwin");
            if (!mac && !os.contains("linux")) {
                throw new IOException("Parent-bound profile activation is unsupported on this operating system: " + os);
            }
            int flags = POSIX_O_RDONLY | (mac ? MAC_O_NOFOLLOW : LINUX_O_NOFOLLOW);
            this.fd = PosixLibC.INSTANCE.open(directory.toString(), flags);
            if (fd < 0) throw failure("open reviewed mutation parent", Native.getLastError());
        }

        @Override
        public void requireCurrent() throws IOException {
            ensureOpen();
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Mutation parent changed while profile activation was in progress.");
            }
            try {
                if (!Files.isSameFile(directory, descriptorPath(fd))) {
                    throw new IOException("Mutation parent changed while profile activation was in progress.");
                }
            } catch (NoSuchFileException missing) {
                throw new IOException("Mutation parent changed while profile activation was in progress.", missing);
            }
        }

        @Override
        public void createDirectory(String relative) throws IOException {
            ensureOpen();
            if (PosixLibC.INSTANCE.mkdirat(fd, relative, 0700) != 0) {
                throw failure("create activation transaction directory", Native.getLastError());
            }
        }

        @Override
        public void createFile(String relative, byte[] bytes, int mode) throws IOException {
            ensureOpen();
            int flags = POSIX_O_WRONLY
                    | (mac ? MAC_O_CREAT | MAC_O_EXCL | MAC_O_NOFOLLOW
                            : LINUX_O_CREAT | LINUX_O_EXCL | LINUX_O_NOFOLLOW);
            int file = PosixLibC.INSTANCE.openat(fd, relative, flags, mode);
            if (file < 0) {
                int error = Native.getLastError();
                if (error == EEXIST) throw new FileAlreadyExistsException(relative);
                throw failure("create activation transaction file", error);
            }
            IOException pending = null;
            try {
                Memory buffer = new Memory(Math.max(1, bytes.length));
                if (bytes.length > 0) buffer.write(0, bytes, 0, bytes.length);
                long written = 0;
                while (written < bytes.length) {
                    NativeLong result = PosixLibC.INSTANCE.write(
                            file, buffer.share(written), new NativeLong(bytes.length - written));
                    long count = result.longValue();
                    if (count <= 0) throw failure("write activation transaction file", Native.getLastError());
                    written += count;
                }
                if (PosixLibC.INSTANCE.fchmod(file, mode) != 0) {
                    throw failure("set activation transaction permissions", Native.getLastError());
                }
                if (PosixLibC.INSTANCE.fsync(file) != 0) {
                    throw failure("sync activation transaction file", Native.getLastError());
                }
            } catch (IOException failure) {
                pending = failure;
                throw failure;
            } finally {
                int close = PosixLibC.INSTANCE.close(file);
                if (close != 0 && pending == null) throw failure("close activation transaction file", Native.getLastError());
            }
        }

        @Override
        public byte[] readFile(String relative) throws IOException {
            ensureOpen();
            int flags = POSIX_O_RDONLY | (mac ? MAC_O_NOFOLLOW : LINUX_O_NOFOLLOW);
            int file = PosixLibC.INSTANCE.openat(fd, relative, flags, 0);
            if (file < 0) throw failure("open activation transaction file", Native.getLastError());
            IOException pending = null;
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                Memory buffer = new Memory(8192);
                while (true) {
                    NativeLong result = PosixLibC.INSTANCE.read(file, buffer, new NativeLong(8192));
                    long count = result.longValue();
                    if (count < 0) throw failure("read activation transaction file", Native.getLastError());
                    if (count == 0) break;
                    bytes.write(buffer.getByteArray(0, (int) count));
                }
                return bytes.toByteArray();
            } catch (IOException failure) {
                pending = failure;
                throw failure;
            } finally {
                int close = PosixLibC.INSTANCE.close(file);
                if (close != 0 && pending == null) throw failure("close activation transaction file", Native.getLastError());
            }
        }

        @Override
        public void link(String existing, String link) throws IOException {
            ensureOpen();
            if (PosixLibC.INSTANCE.linkat(fd, existing, fd, link, 0) != 0) {
                int error = Native.getLastError();
                if (error == EEXIST) throw new FileAlreadyExistsException(link);
                throw failure("publish activation hard link", error);
            }
        }

        @Override
        public void capture(String source, String captured) throws IOException {
            ensureOpen();
            if (PosixLibC.INSTANCE.renameat(fd, source, fd, captured) != 0) {
                int error = Native.getLastError();
                if (error == ENOENT) throw new NoSuchFileException(source);
                throw failure("capture reviewed mod selection", error);
            }
        }

        @Override
        public boolean sameFile(String left, String right) throws IOException {
            ensureOpen();
            int flags = POSIX_O_RDONLY | (mac ? MAC_O_NOFOLLOW : LINUX_O_NOFOLLOW);
            int leftFd = PosixLibC.INSTANCE.openat(fd, left, flags, 0);
            if (leftFd < 0) throw failure("open first activation identity", Native.getLastError());
            int rightFd = PosixLibC.INSTANCE.openat(fd, right, flags, 0);
            if (rightFd < 0) {
                PosixLibC.INSTANCE.close(leftFd);
                throw failure("open second activation identity", Native.getLastError());
            }
            try {
                return Files.isSameFile(descriptorPath(leftFd), descriptorPath(rightFd));
            } finally {
                PosixLibC.INSTANCE.close(rightFd);
                PosixLibC.INSTANCE.close(leftFd);
            }
        }

        @Override
        public boolean existsFile(String relative) throws IOException {
            ensureOpen();
            int flags = POSIX_O_RDONLY | (mac ? MAC_O_NOFOLLOW : LINUX_O_NOFOLLOW);
            int file = PosixLibC.INSTANCE.openat(fd, relative, flags, 0);
            if (file >= 0) {
                PosixLibC.INSTANCE.close(file);
                return true;
            }
            int error = Native.getLastError();
            if (error == ENOENT) return false;
            throw failure("inspect activation transaction file", error);
        }

        @Override
        public void deleteFile(String relative) throws IOException {
            ensureOpen();
            if (PosixLibC.INSTANCE.unlinkat(fd, relative, 0) != 0) {
                int error = Native.getLastError();
                if (error == ENOENT) return;
                throw failure("remove activation transaction file", error);
            }
        }

        @Override
        public void deleteDirectory(String relative) throws IOException {
            ensureOpen();
            int flags = mac ? MAC_AT_REMOVEDIR : LINUX_AT_REMOVEDIR;
            if (PosixLibC.INSTANCE.unlinkat(fd, relative, flags) != 0) {
                int error = Native.getLastError();
                if (error == ENOENT) return;
                throw failure("remove activation transaction directory", error);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (PosixLibC.INSTANCE.close(fd) != 0) throw failure("close reviewed mutation parent", Native.getLastError());
        }

        private Path descriptorPath(int descriptor) throws IOException {
            if (!mac) return Path.of("/proc/self/fd", Integer.toString(descriptor));
            Memory buffer = new Memory(MAXPATHLEN);
            buffer.clear();
            if (PosixLibC.INSTANCE.fcntl(descriptor, F_GETPATH, buffer) != 0) {
                throw failure("resolve reviewed mutation descriptor", Native.getLastError());
            }
            byte[] bytes = buffer.getByteArray(0, MAXPATHLEN);
            int length = 0;
            while (length < bytes.length && bytes[length] != 0) length++;
            if (length == 0) throw new IOException("Reviewed mutation descriptor has no filesystem path.");
            return Path.of(new String(bytes, 0, length, StandardCharsets.UTF_8));
        }

        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("Reviewed mutation parent is already closed.");
        }

        private static IOException failure(String operation, int error) {
            return new IOException(operation + " failed (errno " + error + ")");
        }
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
        private static final int FILE_LINK_INFORMATION = 11;
        private static final int FILE_DISPOSITION_INFORMATION = 13;
        private static final int STATUS_OBJECT_NAME_NOT_FOUND = (int) 0xC0000034L;
        private static final int STATUS_OBJECT_NAME_COLLISION = (int) 0xC0000035L;
        private static final int STATUS_OBJECT_PATH_NOT_FOUND = (int) 0xC000003AL;

        private final Path directory;
        private final Pointer handle;
        private final FileIdentity identity;
        private boolean closed;

        WindowsBackend(Path directory) throws IOException {
            this.directory = directory;
            this.handle = openDirectoryByPath(directory);
            try {
                this.identity = identity(handle, directory.toString());
            } catch (IOException failure) {
                Kernel32.INSTANCE.CloseHandle(handle);
                throw failure;
            }
        }

        @Override
        public void requireCurrent() throws IOException {
            ensureOpen();
            Pointer current = openDirectoryByPath(directory);
            try {
                if (!identity.equals(identity(current, directory.toString()))) {
                    throw new IOException("Mutation parent changed while profile activation was in progress.");
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(current);
            }
        }

        @Override
        public void createDirectory(String relative) throws IOException {
            Pointer created = openRelative(
                    relative,
                    FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                    FILE_CREATE,
                    FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
            Kernel32.INSTANCE.CloseHandle(created);
        }

        @Override
        public void createFile(String relative, byte[] bytes, int mode) throws IOException {
            Pointer file = openRelative(
                    relative,
                    FILE_WRITE_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                    FILE_CREATE,
                    FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
            IOException pending = null;
            try {
                Memory buffer = new Memory(Math.max(1, bytes.length));
                if (bytes.length > 0) buffer.write(0, bytes, 0, bytes.length);
                int written = 0;
                while (written < bytes.length) {
                    IntByReference count = new IntByReference();
                    int result = Kernel32.INSTANCE.WriteFile(
                            file, buffer.share(written), bytes.length - written, count, Pointer.NULL);
                    if (result == 0 || count.getValue() <= 0) throw win32Failure("write activation transaction file");
                    written += count.getValue();
                }
                if (Kernel32.INSTANCE.FlushFileBuffers(file) == 0) throw win32Failure("sync activation transaction file");
            } catch (IOException failure) {
                pending = failure;
                throw failure;
            } finally {
                if (Kernel32.INSTANCE.CloseHandle(file) == 0 && pending == null) {
                    throw win32Failure("close activation transaction file");
                }
            }
        }

        @Override
        public byte[] readFile(String relative) throws IOException {
            Pointer file = openExistingFile(relative, FILE_READ_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE);
            IOException pending = null;
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                Memory buffer = new Memory(8192);
                while (true) {
                    IntByReference count = new IntByReference();
                    int result = Kernel32.INSTANCE.ReadFile(file, buffer, 8192, count, Pointer.NULL);
                    if (result == 0) throw win32Failure("read activation transaction file");
                    if (count.getValue() == 0) break;
                    output.write(buffer.getByteArray(0, count.getValue()));
                }
                return output.toByteArray();
            } catch (IOException failure) {
                pending = failure;
                throw failure;
            } finally {
                if (Kernel32.INSTANCE.CloseHandle(file) == 0 && pending == null) {
                    throw win32Failure("close activation transaction file");
                }
            }
        }

        @Override
        public void link(String existing, String link) throws IOException {
            Pointer file = openExistingFile(existing, FILE_READ_ATTRIBUTES | SYNCHRONIZE);
            try {
                Memory information = renameOrLinkInformation(handle, link);
                int status = Ntdll.INSTANCE.NtSetInformationFile(
                        file, ioStatusBlock(), information, (int) information.size(), FILE_LINK_INFORMATION);
                if (status == STATUS_OBJECT_NAME_COLLISION) throw new FileAlreadyExistsException(link);
                requireNtSuccess("publish activation hard link", status);
            } finally {
                Kernel32.INSTANCE.CloseHandle(file);
            }
        }

        @Override
        public void capture(String source, String captured) throws IOException {
            Pointer file = openExistingFile(source, DELETE | FILE_READ_ATTRIBUTES | SYNCHRONIZE);
            try {
                Memory information = renameOrLinkInformation(handle, captured);
                int status = Ntdll.INSTANCE.NtSetInformationFile(
                        file, ioStatusBlock(), information, (int) information.size(), FILE_RENAME_INFORMATION);
                if (status == STATUS_OBJECT_NAME_COLLISION) throw new FileAlreadyExistsException(captured);
                requireNtSuccess("capture reviewed mod selection", status);
            } finally {
                Kernel32.INSTANCE.CloseHandle(file);
            }
        }

        @Override
        public boolean sameFile(String left, String right) throws IOException {
            Pointer leftHandle = openExistingFile(left, FILE_READ_ATTRIBUTES | SYNCHRONIZE);
            Pointer rightHandle = null;
            try {
                rightHandle = openExistingFile(right, FILE_READ_ATTRIBUTES | SYNCHRONIZE);
                return identity(leftHandle, left).equals(identity(rightHandle, right));
            } finally {
                if (rightHandle != null) Kernel32.INSTANCE.CloseHandle(rightHandle);
                Kernel32.INSTANCE.CloseHandle(leftHandle);
            }
        }

        @Override
        public boolean existsFile(String relative) throws IOException {
            try {
                Pointer file = openExistingFile(relative, FILE_READ_ATTRIBUTES | SYNCHRONIZE);
                Kernel32.INSTANCE.CloseHandle(file);
                return true;
            } catch (NoSuchFileException missing) {
                return false;
            }
        }

        @Override
        public void deleteFile(String relative) throws IOException {
            deleteRelative(relative, false);
        }

        @Override
        public void deleteDirectory(String relative) throws IOException {
            deleteRelative(relative, true);
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (Kernel32.INSTANCE.CloseHandle(handle) == 0) throw win32Failure("close reviewed mutation parent");
        }

        private void deleteRelative(String relative, boolean directoryEntry) throws IOException {
            Pointer file;
            try {
                file = openRelative(
                        relative,
                        DELETE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                        FILE_OPEN,
                        (directoryEntry ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE)
                                | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
            } catch (NoSuchFileException missing) {
                return;
            }
            try {
                Memory disposition = new Memory(1);
                disposition.setByte(0, (byte) 1);
                int status = Ntdll.INSTANCE.NtSetInformationFile(
                        file, ioStatusBlock(), disposition, 1, FILE_DISPOSITION_INFORMATION);
                requireNtSuccess("remove activation transaction entry", status);
            } finally {
                Kernel32.INSTANCE.CloseHandle(file);
            }
        }

        private Pointer openExistingFile(String relative, int desiredAccess) throws IOException {
            return openRelative(
                    relative,
                    desiredAccess,
                    FILE_OPEN,
                    FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT);
        }

        private Pointer openRelative(
                String relative, int desiredAccess, int disposition, int options) throws IOException {
            ensureOpen();
            NativeUnicodeString name = new NativeUnicodeString(relative);
            ObjectAttributes attributes = new ObjectAttributes(handle, name);
            PointerByReference file = new PointerByReference();
            int status = Ntdll.INSTANCE.NtCreateFile(
                    file,
                    desiredAccess,
                    attributes.getPointer(),
                    ioStatusBlock(),
                    Pointer.NULL,
                    FILE_ATTRIBUTE_NORMAL,
                    FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                    disposition,
                    options,
                    Pointer.NULL,
                    0);
            if (status == STATUS_OBJECT_NAME_NOT_FOUND || status == STATUS_OBJECT_PATH_NOT_FOUND) {
                throw new NoSuchFileException(relative);
            }
            if (status == STATUS_OBJECT_NAME_COLLISION) throw new FileAlreadyExistsException(relative);
            requireNtSuccess("open parent-bound activation entry", status);
            Pointer opened = file.getValue();
            try {
                requireNotReparse(opened, relative);
                return opened;
            } catch (IOException failure) {
                Kernel32.INSTANCE.CloseHandle(opened);
                throw failure;
            }
        }

        private static Pointer openDirectoryByPath(Path path) throws IOException {
            Pointer opened = Kernel32.INSTANCE.CreateFileW(
                    new WString(path.toString()),
                    FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                    FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                    Pointer.NULL,
                    OPEN_EXISTING,
                    FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                    Pointer.NULL);
            if (opened == null || Pointer.nativeValue(opened) == -1L) {
                throw win32Failure("open reviewed mutation parent");
            }
            try {
                requireNotReparse(opened, path.toString());
                return opened;
            } catch (IOException failure) {
                Kernel32.INSTANCE.CloseHandle(opened);
                throw failure;
            }
        }

        private static void requireNotReparse(Pointer file, String label) throws IOException {
            if ((information(file, label).fileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw new IOException("Parent-bound activation entry is a reparse point: " + label);
            }
        }

        private static FileIdentity identity(Pointer file, String label) throws IOException {
            ByHandleFileInformation information = information(file, label);
            return new FileIdentity(information.volumeSerialNumber, information.fileIndexHigh, information.fileIndexLow);
        }

        private static ByHandleFileInformation information(Pointer file, String label) throws IOException {
            ByHandleFileInformation information = new ByHandleFileInformation();
            if (Kernel32.INSTANCE.GetFileInformationByHandle(file, information) == 0) {
                throw win32Failure("inspect parent-bound activation entry " + label);
            }
            information.read();
            return information;
        }

        private static Memory renameOrLinkInformation(Pointer rootDirectory, String name) {
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

        private static Memory ioStatusBlock() {
            Memory block = new Memory(Native.POINTER_SIZE * 2L);
            block.clear();
            return block;
        }

        private static void requireNtSuccess(String operation, int status) throws IOException {
            if (status >= 0) return;
            throw new IOException(operation + " failed (NTSTATUS 0x" + Integer.toHexString(status) + ")");
        }

        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("Reviewed mutation parent is already closed.");
        }

        private static IOException win32Failure(String operation) {
            return new IOException(operation + " failed (Win32 error " + Native.getLastError() + ")");
        }
    }

    private record FileIdentity(int volumeSerialNumber, int fileIndexHigh, int fileIndexLow) {}

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

        Pointer pointer() {
            return value.getPointer();
        }
    }

    @Structure.FieldOrder({"length", "maximumLength", "buffer"})
    public static final class UnicodeString extends Structure {
        public short length;
        public short maximumLength;
        public Pointer buffer;
    }

    @Structure.FieldOrder({
        "length", "rootDirectory", "objectName", "attributes", "securityDescriptor", "securityQualityOfService"
    })
    public static final class ObjectAttributes extends Structure {
        public int length;
        public Pointer rootDirectory;
        public Pointer objectName;
        public int attributes;
        public Pointer securityDescriptor;
        public Pointer securityQualityOfService;

        ObjectAttributes(Pointer rootDirectory, NativeUnicodeString name) {
            this.rootDirectory = rootDirectory;
            this.objectName = name.pointer();
            this.attributes = WindowsBackend.OBJ_CASE_INSENSITIVE;
            this.securityDescriptor = Pointer.NULL;
            this.securityQualityOfService = Pointer.NULL;
            this.length = size();
            write();
        }
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
        int openat(int dirfd, String path, int flags, int mode);
        int mkdirat(int dirfd, String path, int mode);
        int linkat(int olddirfd, String oldpath, int newdirfd, String newpath, int flags);
        int renameat(int olddirfd, String oldpath, int newdirfd, String newpath);
        int unlinkat(int dirfd, String path, int flags);
        NativeLong read(int fd, Pointer buffer, NativeLong count);
        NativeLong write(int fd, Pointer buffer, NativeLong count);
        int fchmod(int fd, int mode);
        int fsync(int fd);
        int close(int fd);
        int fcntl(int fd, int command, Object... arguments);
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
        int WriteFile(Pointer file, Pointer buffer, int bytesToWrite, IntByReference bytesWritten, Pointer overlapped);
        int FlushFileBuffers(Pointer file);
        int CloseHandle(Pointer handle);
    }

    private interface Ntdll extends StdCallLibrary {
        Ntdll INSTANCE = Native.load("ntdll", Ntdll.class);
        int NtCreateFile(
                PointerByReference fileHandle,
                int desiredAccess,
                Pointer objectAttributes,
                Pointer ioStatusBlock,
                Pointer allocationSize,
                int fileAttributes,
                int shareAccess,
                int createDisposition,
                int createOptions,
                Pointer eaBuffer,
                int eaLength);
        int NtSetInformationFile(
                Pointer fileHandle,
                Pointer ioStatusBlock,
                Pointer fileInformation,
                int length,
                int fileInformationClass);
    }
}
