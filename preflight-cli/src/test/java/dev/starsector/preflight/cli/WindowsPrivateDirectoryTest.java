package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.security.auth.module.NTSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
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
        UserPrincipal currentUser = currentUserPrincipal(temporaryDirectory);
        AtomicBoolean inspectedAtCreate = new AtomicBoolean();

        Path created = WindowsPrivateDirectory.create(
                temporaryDirectory,
                "preflight-agent-",
                directory -> {
                    inspectedAtCreate.set(true);
                    assertOwnerOnlyAcl(directory, currentUser);
                });
        try {
            assertTrue(inspectedAtCreate.get());
            assertOwnerOnlyAcl(created, currentUser);
        } finally {
            Files.deleteIfExists(created);
        }
    }

    @Test
    void stagingAclRewriteRetainsCurrentUserOwnerAndFullAccess() throws Exception {
        Assumptions.assumeTrue(WindowsPrivateDirectory.supported());
        UserPrincipal currentUser = currentUserPrincipal(temporaryDirectory);
        Path source = Files.writeString(temporaryDirectory.resolve("agent-Ω.jar"), "staged test bytes");
        Path root = Files.createDirectory(temporaryDirectory.resolve("staging"));

        Path staged = AgentJarStaging.readableByTheChildJvm(
                source, StandardCharsets.US_ASCII, List.of(root));
        try {
            assertNotEquals(source, staged);
            assertEquals(-1L, Files.mismatch(source, staged));
            assertOwnerOnlyAcl(staged.getParent(), currentUser);
            AclFileAttributeView acl = Files.getFileAttributeView(
                    staged.getParent(), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertEquals(EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT),
                    acl.getAcl().get(0).flags());
        } finally {
            Files.deleteIfExists(staged);
            Files.deleteIfExists(staged.getParent());
        }
    }

    private static UserPrincipal currentUserPrincipal(Path directory) throws IOException {
        // Independently resolve the native Windows identity, not the created object's owner
        // or the production helper's SID lookup. Compare canonical NIO principals.
        NTSystem identity = new NTSystem();
        String domain = identity.getDomain();
        String account = domain == null || domain.isBlank()
                ? identity.getName() : domain + "\\" + identity.getName();
        return directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account);
    }

    private static void assertOwnerOnlyAcl(Path directory, UserPrincipal currentUser) throws IOException {
        assertTrue(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS));
        AclFileAttributeView acl = Files.getFileAttributeView(
                directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        assertEquals(currentUser, owner, "directory owner must be the current user, not the default token owner");
        List<AclEntry> entries = acl.getAcl();
        assertEquals(1, entries.size(), entries.toString());
        AclEntry entry = entries.get(0);
        assertEquals(AclEntryType.ALLOW, entry.type());
        assertEquals(owner, entry.principal());
        assertTrue(entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class)),
                entry.permissions().toString());
    }
}
