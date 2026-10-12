package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;

class ShopMigrationTest {
    @TempDir Path directory;
    private final UUID account = UUID.randomUUID();
    private final CharacterRef heir = new CharacterRef(account, UUID.randomUUID());
    private final Logger logger = mock(Logger.class);

    private Path file() throws Exception {
        Path file = directory.resolve("Data/" + UUID.randomUUID() + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"owner\":\"" + account + "\",\"untouched\":{\"x\":12.3},\"price\":10}");
        return file;
    }
    private ShopMigration migration() {
        return new ShopMigration(directory.resolve("Data").toFile(), directory.resolve("migrations").toFile(), logger);
    }

    @Test
    void waitingDoesNotWriteAnythingOrAskForAnHeir() throws Exception {
        Path file = file();
        String original = Files.readString(file);
        try (var characters = mockStatic(Characters.class)) {
            ShopMigration migration = migration();
            assertFalse(migration.runWhenReady());
            assertFalse(migration.runWhenReady());
            assertEquals(original, Files.readString(file));
            assertFalse(Files.exists(directory.resolve("migrations")));
            characters.verify(() -> Characters.legacyHeir(any()), never());
            verify(logger, times(1)).severe(anyString());
        }
    }

    @Test
    void backsUpExactOriginalAndKeepsShopIdAndUnknownData() throws Exception {
        Path file = file();
        byte[] original = Files.readAllBytes(file);
        try (var characters = mockStatic(Characters.class)) {
            characters.when(Characters::available).thenReturn(true);
            characters.when(Characters::legacyHeirsReady).thenReturn(true);
            characters.when(() -> Characters.legacyHeir(account)).thenReturn(Optional.of(heir));
            assertTrue(migration().runWhenReady());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("migrations/backup").resolve(file.getFileName())));
            String migrated = Files.readString(file);
            assertTrue(migrated.contains(heir.characterId().toString()));
            assertTrue(migrated.contains("untouched"));
            assertTrue(migrated.contains("12.3"));
            assertTrue(Files.readString(directory.resolve("migrations/character-v1").resolve(file.getFileName())).contains("done"));
            assertTrue(migration().runWhenReady());
            assertEquals(migrated, Files.readString(file));
            characters.verify(() -> Characters.legacyHeir(account), times(1));
        }
    }

    @Test
    void unassignedOwnerIsKeptAndQuarantinedWithItsFullOriginal() throws Exception {
        Path file = file();
        String original = Files.readString(file);
        try (var characters = mockStatic(Characters.class)) {
            characters.when(Characters::available).thenReturn(true);
            characters.when(Characters::legacyHeirsReady).thenReturn(true);
            assertTrue(migration().runWhenReady());
            assertEquals(original, Files.readString(file));
            String entry = Files.readString(directory.resolve("migrations/character-v1").resolve(file.getFileName()));
            assertTrue(entry.contains("quarantined"));
            assertTrue(entry.contains("untouched"));
            assertTrue(Files.readString(directory.resolve("migrations/quarantine.txt")).contains(account.toString()));
            characters.when(() -> Characters.legacyHeir(account)).thenReturn(Optional.of(heir));
            assertTrue(migration().runWhenReady());
            assertFalse(Files.exists(directory.resolve("migrations/quarantine.txt")));
            assertTrue(Files.readString(file).contains(heir.characterId().toString()));
            assertEquals(original, Files.readString(directory.resolve("migrations/backup").resolve(file.getFileName())));
        }
    }

    @Test
    void interruptedStoreRestoresTheVerifiedBackupBeforeRedoing() throws Exception {
        Path file = file();
        byte[] original = Files.readAllBytes(file);
        Path backup = directory.resolve("migrations/backup").resolve(file.getFileName());
        DurableFiles.write(backup, original);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        Path manifest = directory.resolve("migrations/character-v1").resolve(file.getFileName());
        DurableFiles.write(manifest, "{\"state\":\"in-progress\",\"sha256\":\"" + sha + "\"}");
        Files.writeString(file, "{\"partial\":true}");
        try (var characters = mockStatic(Characters.class)) {
            characters.when(Characters::available).thenReturn(true);
            characters.when(Characters::legacyHeirsReady).thenReturn(true);
            characters.when(() -> Characters.legacyHeir(account)).thenReturn(Optional.of(heir));
            assertTrue(migration().runWhenReady());
            assertTrue(Files.readString(file).contains(heir.characterId().toString()));
            assertTrue(Files.readString(file).contains("untouched"));
            assertArrayEquals(original, Files.readAllBytes(backup));
        }
    }

    @Test
    void mismatchedBackupFailsClosedAndDoesNotRewriteSource() throws Exception {
        Path file = file();
        String original = Files.readString(file);
        Path backup = directory.resolve("migrations/backup").resolve(file.getFileName());
        DurableFiles.write(backup, "tampered");
        try (var characters = mockStatic(Characters.class)) {
            characters.when(Characters::available).thenReturn(true);
            characters.when(Characters::legacyHeirsReady).thenReturn(true);
            assertFalse(migration().runWhenReady());
            assertEquals(original, Files.readString(file));
            assertEquals("tampered", Files.readString(backup));
            characters.verify(() -> Characters.legacyHeir(any()), never());
        }
    }

    @Test
    void backupWriteFailureCannotRewriteTheLegacyShop() throws Exception {
        Path file = file();
        String original = Files.readString(file);
        Path migrations = directory.resolve("migrations");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("backup"), "occupied");
        try (var characters = mockStatic(Characters.class)) {
            characters.when(Characters::available).thenReturn(true);
            characters.when(Characters::legacyHeirsReady).thenReturn(true);
            assertFalse(migration().runWhenReady());
            assertEquals(original, Files.readString(file));
        }
    }

    @Test
    void invalidCharacterIsFrozenRatherThanAssignedToAnotherCharacter() throws Exception {
        Path file = file();
        String invalid = "{\"owner\":\"" + account + "\",\"owner character\":\"broken\"}";
        Files.writeString(file, invalid);
        try (var characters = mockStatic(Characters.class)) {
            assertEquals(1, migration().migrate().size());
            assertEquals(invalid, Files.readString(file));
            characters.verifyNoInteractions();
        }
    }
}
