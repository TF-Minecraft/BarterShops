package net.tfminecraft.bartershops;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;

/** Back up, journal and atomically migrate each shop; unresolved shops retain their original data. */
final class ShopMigration {
    static final String REPORT = "quarantine.txt";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path data;
    private final Path migrations;
    private final Logger logger;
    private boolean waitingLogged;
    private boolean failed;
    private List<String> reportedQuarantine;
    private boolean reportFailureLogged;

    ShopMigration(File dataFolder, File migrationFolder, Logger logger) {
        data = dataFolder.toPath();
        migrations = migrationFolder.toPath();
        this.logger = logger;
    }

    boolean runWhenReady() {
        if (!Characters.available() || !Characters.legacyHeirsReady()) {
            if (!waitingLogged) {
                waitingLogged = true;
                logger.severe("Waiting for character heirs; shop creation, trades and owner removal are closed.");
            }
            return false;
        }
        migrate();
        return !failed;
    }

    List<String> migrate() {
        failed = false;
        List<String> quarantined = new ArrayList<>();
        File[] files = data.toFile().listFiles((folder, name) -> name.endsWith(".json"));
        for (File file : files == null ? new File[0] : files) {
            try {
                String problem = migrate(file.toPath());
                if (problem != null) quarantined.add(file.getName() + ": " + problem);
            } catch (IOException | RuntimeException ex) {
                failed = true;
                quarantined.add(file.getName() + ": migration failed: " + ex);
            }
        }
        try {
            Path report = migrations.resolve(REPORT);
            if (!quarantined.isEmpty()) {
                // Retries while not ready rerun migration; only a changed list is news.
                boolean changed = !quarantined.equals(reportedQuarantine);
                if (changed || !Files.exists(report))
                    DurableFiles.write(report, String.join("\n", quarantined) + "\n");
                if (changed) {
                    reportedQuarantine = List.copyOf(quarantined);
                    quarantined.forEach(message -> logger.warning("Shop remains closed: " + message));
                }
            } else {
                if (Files.exists(report)) DurableFiles.delete(report);
                reportedQuarantine = List.of();
            }
            reportFailureLogged = false;
        } catch (IOException ex) {
            failed = true;
            if (!reportFailureLogged) {
                reportFailureLogged = true;
                logger.severe("Cannot write shop quarantine report: " + ex);
            }
        }
        return quarantined;
    }

    @SuppressWarnings("unchecked")
    private String migrate(Path file) throws IOException {
        Path manifest = migrations.resolve("character-v1").resolve(file.getFileName());
        Path backup = migrations.resolve("backup").resolve(file.getFileName());
        if (Files.exists(manifest)) {
            Map<String, Object> previous;
            try {
                previous = read(manifest);
            } catch (IOException | RuntimeException ex) {
                // Only this shop is affected: without its record it stays unmigrated and closed.
                return "unreadable migration record; retained for staff: " + ex;
            }
            if ("in-progress".equals(previous.get("state"))) {
                byte[] original = Files.readAllBytes(backup);
                if (!hash(original).equals(previous.get("sha256"))) throw new IOException("Backup hash mismatch");
                DurableFiles.write(file, original);
            }
        }
        Map<String, Object> shop;
        try {
            shop = read(file);
        } catch (IOException | RuntimeException ex) {
            // Only this shop is affected: Database cannot load it, so it stays closed on its own.
            return "unreadable shop; retained for staff: " + ex;
        }
        if (shop.containsKey(Database.OWNER_CHARACTER)) {
            if (Database.readCharacter(shop.get(Database.OWNER_CHARACTER)) != null
                    && Database.readCharacter(shop.get(Database.OWNER)) != null) return null;
            return "invalid owner character/account; retained for staff";
        }
        byte[] original = Files.readAllBytes(file);
        if (!Files.exists(backup)) DurableFiles.write(backup, original);
        // The first backup is immutable. Staff resolve quarantines through the heir mapping.
        if (!hash(original).equals(hash(Files.readAllBytes(backup))))
            throw new IOException("Shop changed since its migration backup; staff review required");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("shopId", Database.shopId(file.toFile()));
        entry.put("backup", backup.toString());
        entry.put("sha256", hash(original));
        entry.put("original", shop);
        Optional<CharacterRef> heir = Optional.empty();
        String accountText = Database.readCharacter(shop.get(Database.OWNER));
        if (accountText != null) {
            UUID account = UUID.fromString(accountText);
            heir = Characters.legacyHeir(account).filter(ref -> account.equals(ref.ownerId()));
        }
        if (heir.isEmpty()) {
            String reason = "owner " + shop.get(Database.OWNER) + " has no reviewed heir";
            entry.put("state", "quarantined");
            entry.put("reason", reason);
            DurableFiles.write(manifest, JSON.toJson(entry));
            return reason;
        }
        entry.put("state", "in-progress");
        DurableFiles.write(manifest, JSON.toJson(entry));
        Map<String, Object> migrated = new LinkedHashMap<>(shop);
        migrated.put(Database.OWNER_CHARACTER, heir.get().characterId().toString());
        DurableFiles.write(file, JSON.toJson(migrated));
        entry.put("state", "done");
        DurableFiles.write(manifest, JSON.toJson(entry));
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            Map<String, Object> value = Database.SHOP_READER.fromJson(reader, Map.class);
            if (value == null) throw new IOException("Unreadable shop: " + path);
            return value;
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
