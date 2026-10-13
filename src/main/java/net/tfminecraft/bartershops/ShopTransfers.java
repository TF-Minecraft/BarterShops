package net.tfminecraft.bartershops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * Gives a dead character's shops to the estate's recipient. Each death keeps a journal in
 * {@code estates/<deathId>.json}, written before the first shop file changes, so a crash between
 * shops is finished at the next settle and every moved shop is still reported.
 */
final class ShopTransfers {
    static final String PLANNED = "planned";
    static final String MOVED = "moved";
    // The shop was removed or given to someone else before this move reached it.
    static final String SUPERSEDED = "superseded";

    record Entry(String account, String character, String state) {
        CharacterRef recipient() {
            return new CharacterRef(UUID.fromString(account), UUID.fromString(character));
        }
    }

    record Journal(String deathId, String testator, Map<String, Entry> shops) {}

    /**
     * @param moved   every shop this estate has moved so far, in journal order
     * @param blocked shops left with the testator because a trade there is unfinished
     */
    record Result(List<String> moved, List<String> blocked) {}

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Database db;
    private final ShopTrades trades;
    private final Path directory;

    ShopTransfers(Database db, ShopTrades trades, Path directory) {
        this.db = db;
        this.trades = trades;
        this.directory = directory;
    }

    /**
     * Moves every shop in {@code owned} (the testator's today) and any shop planned before a crash.
     * Shops with an unfinished trade stay where they are and are returned as blocked.
     */
    Result move(UUID deathId, UUID testator, CharacterRef to, Collection<String> owned) throws IOException {
        Journal journal = read(deathId);
        if (journal.testator() == null) journal = new Journal(deathId.toString(), testator.toString(), journal.shops());
        Map<String, Entry> shops = journal.shops();
        List<String> blocked = new ArrayList<>();
        Entry planned = new Entry(to.ownerId().toString(), to.characterId().toString(), PLANNED);
        boolean changed = false;
        for (String shopId : owned) {
            if (blockedByTrade(shopId)) {
                blocked.add(shopId);
                continue;
            }
            shops.put(shopId, planned);
            changed = true;
        }
        if (changed) write(deathId, journal);
        changed = false;
        try {
            for (Map.Entry<String, Entry> shop : shops.entrySet()) {
                Entry entry = shop.getValue();
                if (!PLANNED.equals(entry.state())) continue;
                Optional<String> owner = db.ownerCharacter(shop.getKey());
                String state = SUPERSEDED;
                if (owner.equals(Optional.of(testator.toString()))) {
                    state = db.setOwner(shop.getKey(), entry.recipient()) ? MOVED : SUPERSEDED;
                } else if (owner.equals(Optional.of(entry.character()))) {
                    state = MOVED; // written before a crash cut the journal update short
                }
                shop.setValue(new Entry(entry.account(), entry.character(), state));
                changed = true;
            }
        } finally {
            if (changed) write(deathId, journal);
        }
        List<String> moved = shops.entrySet().stream().filter(shop -> MOVED.equals(shop.getValue().state()))
                .map(Map.Entry::getKey).toList();
        return new Result(moved, blocked);
    }

    // A shop file whose name is not a shop ID cannot trade or be rewritten; staff handle it.
    private boolean blockedByTrade(String shopId) {
        try {
            return trades.hasIntent(shopId);
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    private Journal read(UUID deathId) throws IOException {
        Path file = file(deathId);
        if (!Files.exists(file)) return new Journal(deathId.toString(), null, new LinkedHashMap<>());
        try {
            Journal journal = JSON.fromJson(Files.readString(file), Journal.class);
            if (journal == null || journal.shops() == null) throw new IOException("Incomplete estate journal " + file);
            return new Journal(journal.deathId(), journal.testator(), new LinkedHashMap<>(journal.shops()));
        } catch (JsonParseException ex) {
            throw new IOException("Unreadable estate journal " + file, ex);
        }
    }

    private void write(UUID deathId, Journal journal) throws IOException {
        DurableFiles.write(file(deathId), JSON.toJson(journal));
    }

    private Path file(UUID deathId) {
        return directory.resolve(deathId + ".json");
    }
}
