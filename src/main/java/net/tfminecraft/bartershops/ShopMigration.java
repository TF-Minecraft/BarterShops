package net.tfminecraft.bartershops;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;

import com.google.gson.GsonBuilder;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;

/**
 * Gives every shop saved before shops belonged to characters to its owner account's legacy heir.
 * Runs once the heirs are ready. Each file is backed up before it is rewritten, and a shop whose
 * owner has no heir stays as it is: closed, listed in the quarantine report and never deleted.
 * Every start tries the listed shops again, so a shop opens once staff give its owner an heir.
 */
final class ShopMigration {
	static final String REPORT = "quarantine.txt";

	private final File dataFolder;
	private final File migrationFolder;
	private final Logger logger;
	private boolean waitingLogged;

	ShopMigration(File dataFolder, File migrationFolder, Logger logger) {
		this.dataFolder = dataFolder;
		this.migrationFolder = migrationFolder;
		this.logger = logger;
	}

	/** Migrates once the heirs are ready and says whether it ran. Main thread only. */
	boolean runWhenReady() {
		if (!Characters.legacyHeirsReady()) {
			if (!waitingLogged) {
				waitingLogged = true;
				logger.info("Waiting for character heirs before giving older shops to characters; those shops are closed until then.");
			}
			return false;
		}
		migrate();
		return true;
	}

	/** Shops left without an owner character, as report lines. */
	List<String> migrate() {
		int migrated = 0;
		List<String> quarantined = new ArrayList<>();
		File[] files = dataFolder.listFiles();
		for (File file : files == null ? new File[0] : files) {
			if (file.isDirectory()) continue;
			String problem;
			try {
				problem = migrate(file);
			} catch (IOException | RuntimeException ex) {
				problem = "could not be migrated: " + ex;
			}
			if (problem == null) {
				migrated++;
			} else if (!problem.isEmpty()) {
				quarantined.add(file.getName() + ": " + problem);
				logger.warning("Shop " + file.getName() + " stays closed: " + problem);
			}
		}
		writeReport(quarantined);
		if (migrated > 0 || !quarantined.isEmpty()) {
			logger.info("Gave " + migrated + " shops to their owners' heirs; " + quarantined.size()
				+ " stay closed and are listed in " + new File(migrationFolder, REPORT).getPath() + ".");
		}
		return quarantined;
	}

	/** Null once migrated, empty when there was nothing to do, otherwise why the shop stays closed. */
	private String migrate(File file) throws IOException {
		Map<String, Object> shop = read(file);
		if (shop == null) return "unreadable shop file";
		if (shop.containsKey(Database.OWNER_CHARACTER)) {
			return Database.readCharacter(shop.get(Database.OWNER_CHARACTER)) != null ? ""
				: "owner character is not a character ID";
		}
		UUID account;
		try {
			account = UUID.fromString(String.valueOf(shop.get(Database.OWNER)));
		} catch (IllegalArgumentException ex) {
			return "owner " + shop.get(Database.OWNER) + " is not an account ID";
		}
		Optional<CharacterRef> heir = Characters.legacyHeir(account);
		if (heir.isEmpty()) return "owner " + account + " has no heir";
		Path backups = migrationFolder.toPath().resolve("backup");
		Files.createDirectories(backups);
		Files.copy(file.toPath(), backups.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
		shop.put(Database.OWNER, heir.get().ownerId().toString());
		shop.put(Database.OWNER_CHARACTER, heir.get().characterId().toString());
		write(file.toPath(), shop);
		return null;
	}

	private static Map<String, Object> read(File file) {
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			Map<?, ?> json = Database.SHOP_READER.fromJson(reader, Map.class);
			if (json == null) return null;
			Map<String, Object> shop = new LinkedHashMap<>();
			json.forEach((key, value) -> shop.put(String.valueOf(key), value));
			return shop;
		} catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	// Written beside the backups and moved over the shop, so a crash leaves either file whole.
	private void write(Path target, Map<String, Object> shop) throws IOException {
		TreeMap<String, Object> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		sorted.putAll(shop);
		Path temp = Files.createTempFile(migrationFolder.toPath(), "shop", ".tmp");
		try {
			Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(sorted), StandardCharsets.UTF_8);
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private void writeReport(List<String> quarantined) {
		Path report = migrationFolder.toPath().resolve(REPORT);
		try {
			if (quarantined.isEmpty()) {
				Files.deleteIfExists(report);
				return;
			}
			Files.createDirectories(migrationFolder.toPath());
			List<String> lines = new ArrayList<>();
			lines.add("# Shops closed because they have no owner character. Their files are kept in the Data folder.");
			lines.addAll(quarantined);
			Files.write(report, lines, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			logger.warning("Could not write " + report + ": " + ex);
		}
	}
}
