package net.tfminecraft.bartershops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.tlibs.character.CharacterRef;

/** Retain each click's ID until delivery completes. Interrupted delivery requires staff review. */
final class ShopTrades {
    enum Outcome { APPLIED, REFUSED, WAITING, REVIEW }
    record Request(String shopId, CharacterRef customer, CharacterRef owner, String direction,
                   int quantity, long cents, String item) {}
    record Intent(String operationId, Request request, String state) {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    private final Logger logger;
    private final Map<String, Intent> active = new HashMap<>();

    ShopTrades(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    /** Whether a trade at this shop is unfinished, in this process or from an earlier one. */
    boolean hasIntent(String shopId) {
        return active.containsKey(shopId) || Files.exists(intentFile(shopId));
    }

    /** The unfinished trade at this shop, as this process last left it. Unreadable records throw. */
    Optional<Intent> inspect(String shopId) throws IOException {
        Intent intent = active.get(shopId);
        if (intent != null) return Optional.of(intent);
        Path file = intentFile(shopId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            intent = JSON.fromJson(Files.readString(file), Intent.class);
        } catch (JsonParseException ex) {
            throw new IOException("Unreadable trade record " + file, ex);
        }
        if (intent == null || intent.operationId() == null || intent.state() == null || intent.request() == null)
            throw new IOException("Incomplete trade record " + file);
        return Optional.of(intent);
    }

    /** Removes the record and this process's copy, so the shop trades again. Staff only. */
    void clear(String shopId) throws IOException {
        DurableFiles.delete(intentFile(shopId));
        active.remove(shopId);
    }

    private Path intentFile(String shopId) {
        return directory.resolve(UUID.fromString(shopId) + ".json");
    }

    Outcome trade(Request request, Runnable deliver) {
        Path file;
        try {
            file = directory.resolve(UUID.fromString(request.shopId()) + ".json");
        } catch (RuntimeException ex) {
            return Outcome.REVIEW;
        }
        Intent intent = active.get(request.shopId());
        // Files from an earlier process may describe an already delivered item. Never replay them.
        if (intent == null && Files.exists(file)) return Outcome.REVIEW;
        if (intent != null && (!intent.request().equals(request) || !"prepared".equals(intent.state())))
            return Outcome.REVIEW;
        try {
            if (intent == null) {
                intent = new Intent("shop:" + request.shopId() + ":" + UUID.randomUUID(), request, "prepared");
                DurableFiles.write(file, JSON.toJson(intent));
                active.put(request.shopId(), intent);
            }
            Result result = Denars.status(intent.operationId()).orElse(null);
            if (result == null) {
                boolean buy = "buy".equals(request.direction());
                CharacterRef seller = buy ? request.owner() : request.customer();
                String from = buy ? Denars.pouch(request.customer()) : Denars.bank(request.owner());
                String to = buy ? Denars.bank(request.owner()) : Denars.pouch(request.customer());
                String reason = "shop:" + request.direction();
                result = request.cents() == 0
                    ? Denars.post(intent.operationId(), reason, List.of(new Leg(from, 0), new Leg(to, 0)))
                    : Denars.trade(intent.operationId(), reason, from, seller, to, request.cents(), true);
            }
            if (result == Result.REFUSED) {
                DurableFiles.delete(file);
                active.remove(request.shopId());
                return Outcome.REFUSED;
            }
            if (result == Result.UNAVAILABLE) return Outcome.WAITING;
            if (result != Result.APPLIED) {
                Intent conflict = new Intent(intent.operationId(), request, "conflict");
                active.put(request.shopId(), conflict);
                DurableFiles.write(file, JSON.toJson(conflict));
                return Outcome.REVIEW;
            }
            Intent delivering = new Intent(intent.operationId(), request, "delivering");
            // Set memory first: an ambiguous disk write must also block delivery in this process.
            active.put(request.shopId(), delivering);
            DurableFiles.write(file, JSON.toJson(delivering));
            deliver.run();
            DurableFiles.delete(file);
            active.remove(request.shopId());
            return Outcome.APPLIED;
        } catch (IOException | RuntimeException ex) {
            logger.severe("Shop trade requires review; retained intent " + file + ": " + ex);
            return Outcome.REVIEW;
        }
    }
}
