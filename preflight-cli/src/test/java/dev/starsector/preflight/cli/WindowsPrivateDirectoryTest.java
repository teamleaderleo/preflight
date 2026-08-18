package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WindowsPrivateDirectoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void firstObservableDirectoryStateHasOnlyCurrentOwnerFullAccess() throws Exception {
        Assumptions.assumeTrue(WindowsPrivateDirectory.supported());
        AtomicBoolean inspectedAtCreate = new AtomicBoolean();

        Path created = WindowsPrivateDirectory.create(
                temporaryDirectory,
                "preflight-agent-",
                directory -> {
                    inspectedAtCreate.set(true);
                    assertOwnerOnlyAcl(directory);
                });
        try {
            assertTrue(inspectedAtCreate.get());
            assertOwnerOnlyAcl(created);
        } finally {
            Files.deleteIfExists(created);
        }
    }

    private static void assertOwnerOnlyAcl(Path directory) throws Exception {
        assertTrue(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS));
        AclFileAttributeView acl = Files.getFileAttributeView(
                directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> entries = acl.getAcl();
        assertEquals(1, entries.size(), entries.toString());
        AclEntry entry = entries.get(0);
        assertEquals(AclEntryType.ALLOW, entry.type());
        assertEquals(owner, entry.principal());
        assertTrue(entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class)),
                entry.permissions().toString());
    }
}
