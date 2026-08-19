package dev.starsector.preflight.cli;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/** Creates an owner-private Windows directory without an inherited-ACL observation window. */
final class WindowsPrivateDirectory {
    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_USER = 1;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int ERROR_FILE_EXISTS = 80;
    private static final int ERROR_ALREADY_EXISTS = 183;
    private static final int SDDL_REVISION_1 = 1;
    private static final CreationHook NOOP = directory -> {};

    private WindowsPrivateDirectory() {}

    static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static Path create(Path root, String prefix) throws IOException {
        return create(root, prefix, NOOP);
    }

    static Path create(Path root, String prefix, CreationHook hook) throws IOException {
        if (!supported()) throw new IOException("Private Win32 directory creation is only available on Windows.");
        if (hook == null) throw new IllegalArgumentException("Creation hook is required");
        Path parent = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("staging root is not a real directory: " + parent);
        }

        String sid = currentUserSid();
        Pointer securityDescriptor = securityDescriptor("D:P(A;;FA;;;" + sid + ")");
        try {
            SecurityAttributes security = new SecurityAttributes(securityDescriptor);
            for (int attempt = 0; attempt < 32; attempt++) {
                Path candidate = parent.resolve(prefix + UUID.randomUUID()).toAbsolutePath().normalize();
                if (Kernel32.INSTANCE.CreateDirectoryW(new WString(candidate.toString()), security.getPointer()) != 0) {
                    if (Files.isSymbolicLink(candidate)
                            || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        Files.deleteIfExists(candidate);
                        throw new IOException("private staging path is not a real directory: " + candidate);
                    }
                    hook.afterCreate(candidate);
                    return candidate;
                }
                int error = Native.getLastError();
                if (error != ERROR_FILE_EXISTS && error != ERROR_ALREADY_EXISTS) {
                    throw new IOException("Could not create owner-private staging directory (Win32 error "
                            + error + "): " + candidate);
                }
            }
            throw new IOException("Could not allocate a unique owner-private staging directory under " + parent);
        } finally {
            Kernel32.INSTANCE.LocalFree(securityDescriptor);
        }
    }

    private static String currentUserSid() throws IOException {
        PointerByReference token = new PointerByReference();
        if (Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), TOKEN_QUERY, token) == 0) {
            throw win32Failure("open current process token");
        }
        try {
            IntByReference required = new IntByReference();
            Advapi32.INSTANCE.GetTokenInformation(token.getValue(), TOKEN_USER, Pointer.NULL, 0, required);
            int sizingError = Native.getLastError();
            if (required.getValue() <= 0 || sizingError != ERROR_INSUFFICIENT_BUFFER) {
                throw win32Failure("size current user token information");
            }
            Memory tokenUser = new Memory(required.getValue());
            if (Advapi32.INSTANCE.GetTokenInformation(
                    token.getValue(), TOKEN_USER, tokenUser, required.getValue(), required) == 0) {
                throw win32Failure("read current user token information");
            }
            Pointer sid = tokenUser.getPointer(0);
            PointerByReference text = new PointerByReference();
            if (Advapi32.INSTANCE.ConvertSidToStringSidW(sid, text) == 0) {
                throw win32Failure("format current user SID");
            }
            try {
                return text.getValue().getWideString(0);
            } finally {
                Kernel32.INSTANCE.LocalFree(text.getValue());
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.getValue());
        }
    }

    private static Pointer securityDescriptor(String sddl) throws IOException {
        PointerByReference descriptor = new PointerByReference();
        if (Advapi32.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                new WString(sddl), SDDL_REVISION_1, descriptor, Pointer.NULL) == 0) {
            throw win32Failure("build owner-private staging security descriptor");
        }
        return descriptor.getValue();
    }

    private static IOException win32Failure(String operation) {
        return new IOException(operation + " failed (Win32 error " + Native.getLastError() + ")");
    }

    @FunctionalInterface
    interface CreationHook {
        void afterCreate(Path directory) throws IOException;
    }

    @Structure.FieldOrder({"nLength", "lpSecurityDescriptor", "bInheritHandle"})
    public static final class SecurityAttributes extends Structure {
        public int nLength;
        public Pointer lpSecurityDescriptor;
        public int bInheritHandle;

        SecurityAttributes(Pointer securityDescriptor) {
            this.lpSecurityDescriptor = securityDescriptor;
            this.bInheritHandle = 0;
            this.nLength = size();
            write();
        }
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        Pointer GetCurrentProcess();
        int CreateDirectoryW(WString path, Pointer securityAttributes);
        Pointer LocalFree(Pointer memory);
        int CloseHandle(Pointer handle);
    }

    private interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("advapi32", Advapi32.class);
        int OpenProcessToken(Pointer process, int desiredAccess, PointerByReference tokenHandle);
        int GetTokenInformation(
                Pointer tokenHandle,
                int tokenInformationClass,
                Pointer tokenInformation,
                int tokenInformationLength,
                IntByReference returnLength);
        int ConvertSidToStringSidW(Pointer sid, PointerByReference stringSid);
        int ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString stringSecurityDescriptor,
                int stringSdRevision,
                PointerByReference securityDescriptor,
                Pointer securityDescriptorSize);
    }
}
