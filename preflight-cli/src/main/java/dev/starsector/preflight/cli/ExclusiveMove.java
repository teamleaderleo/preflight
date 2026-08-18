package dev.starsector.preflight.cli;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Moves one filesystem generation to an absent sibling name without a check-then-rename gap.
 *
 * <p>The Java 17 Unix provider does not provide the ownership guarantee needed here for a plain
 * no-option {@code Files.move}: its no-replace behavior can be implemented as an existence check
 * followed by a replacing POSIX rename. Launcher ownership mutations therefore use the native
 * exclusive-rename primitive on every supported desktop OS and fail closed when the runtime or
 * filesystem cannot provide it.
 */
final class ExclusiveMove {
    private static final int EEXIST = 17;
    private static final int AT_FDCWD = -100;
    private static final int RENAME_NOREPLACE = 1;
    private static final int RENAME_EXCL = 0x00000004;
    private static final int ERROR_FILE_EXISTS = 80;
    private static final int ERROR_ALREADY_EXISTS = 183;

    private ExclusiveMove() {}

    static void move(Path source, Path target) throws IOException {
        Path from = source.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                moveWindows(from, to);
            } else if (os.contains("mac") || os.contains("darwin")) {
                moveMac(from, to);
            } else if (os.contains("linux")) {
                moveLinux(from, to);
            } else {
                throw unsupported(from, to, "unsupported operating system: " + os);
            }
        } catch (UnsatisfiedLinkError | NoClassDefFoundError nativeUnavailable) {
            throw unsupported(from, to, "exclusive rename primitive is unavailable", nativeUnavailable);
        }
    }

    private static void moveLinux(Path source, Path target) throws IOException {
        int result = LinuxLibC.INSTANCE.renameat2(
                AT_FDCWD,
                source.toString(),
                AT_FDCWD,
                target.toString(),
                RENAME_NOREPLACE);
        if (result == 0) return;
        throw posixFailure("renameat2(RENAME_NOREPLACE)", source, target, Native.getLastError());
    }

    private static void moveMac(Path source, Path target) throws IOException {
        int result = MacLibC.INSTANCE.renamex_np(source.toString(), target.toString(), RENAME_EXCL);
        if (result == 0) return;
        throw posixFailure("renamex_np(RENAME_EXCL)", source, target, Native.getLastError());
    }

    private static void moveWindows(Path source, Path target) throws IOException {
        int result = Kernel32.INSTANCE.MoveFileExW(new WString(source.toString()), new WString(target.toString()), 0);
        if (result != 0) return;
        int error = Native.getLastError();
        if (error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS) {
            throw new FileAlreadyExistsException(target.toString());
        }
        throw new IOException("Native exclusive launcher move failed (MoveFileExW, error " + error + "): "
                + source + " -> " + target);
    }

    private static IOException posixFailure(
            String operation, Path source, Path target, int error) {
        if (error == EEXIST) {
            return new FileAlreadyExistsException(target.toString());
        }
        return new IOException("Native exclusive launcher move failed (" + operation + ", errno " + error + "): "
                + source + " -> " + target);
    }

    private static IOException unsupported(Path source, Path target, String reason) {
        return new IOException("Refusing launcher ownership mutation because " + reason + ": "
                + source + " -> " + target);
    }

    private static IOException unsupported(
            Path source, Path target, String reason, Throwable cause) {
        return new IOException("Refusing launcher ownership mutation because " + reason + ": "
                + source + " -> " + target, cause);
    }

    private interface LinuxLibC extends Library {
        LinuxLibC INSTANCE = Native.load("c", LinuxLibC.class);

        int renameat2(int oldDirFd, String oldPath, int newDirFd, String newPath, int flags);
    }

    private interface MacLibC extends Library {
        MacLibC INSTANCE = Native.load("c", MacLibC.class);

        int renamex_np(String oldPath, String newPath, int flags);
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int MoveFileExW(WString existingFileName, WString newFileName, int flags);
    }
}
