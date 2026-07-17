package xyz.yychainsaw.portfolio.media.application;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaTemporaryFilesTest {
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    @TempDir Path temporaryDirectory;

    @Test
    void creationIsAtomicOwnerOnlyOnPosixOrAclFileSystems() throws Exception {
        Path created = MediaTemporaryFiles.create(temporaryDirectory, ".png");
        try {
            assertThat(Files.isRegularFile(created, NOFOLLOW_LINKS)).isTrue();
            if (Files.getFileStore(created)
                    .supportsFileAttributeView(PosixFileAttributeView.class)) {
                assertThat(Files.getPosixFilePermissions(created, NOFOLLOW_LINKS))
                        .isEqualTo(OWNER_ONLY);
                return;
            }

            AclFileAttributeView view = Files.getFileAttributeView(
                    created, AclFileAttributeView.class, NOFOLLOW_LINKS);
            assertThat(view).isNotNull();
            AclEntry expected = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(view.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            assertThat(view.getAcl()).isEqualTo(List.of(expected));
        } finally {
            MediaTemporaryFiles.delete(created);
        }
    }
}
