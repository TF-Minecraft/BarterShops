package net.tfminecraft.bartershops;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;

/**
 * The {@code bartershops} estate participant: a dead character's shops go to the estate's property
 * recipient. Loaded only after {@link EstateProbe} finds the TLibs 2.6.0 estate API.
 */
final class ShopEstate implements EstateParticipant {
    static final String ID = "bartershops";
    static final long HOUR_TICKS = 20L * 60 * 60;

    private final Database db;
    private final ShopTransfers transfers;
    private final BooleanSupplier ready;
    private final Logger logger;
    Predicate<EstateDecision> guard = Estates::guardHolds;
    Supplier<List<EstateDecision>> decided = Estates::decided;
    Reporter reporter = Estates::report;
    // The last outcome reported per death, so the hourly pass reports only changes.
    private final Map<UUID, Map.Entry<Long, Outcome>> reported = new HashMap<>();

    interface Reporter {
        boolean report(UUID deathId, String participantId, long revision, Outcome outcome);
    }

    ShopEstate(Database db, ShopTransfers transfers, BooleanSupplier ready, Logger logger) {
        this.db = db;
        this.transfers = transfers;
        this.ready = ready;
        this.logger = logger;
    }

    /** Registers the participant and runs the reconcile on the first tick, then hourly. */
    static void start(Plugin plugin, Database db, ShopTrades trades, BooleanSupplier ready) {
        ShopEstate estate = new ShopEstate(db,
                new ShopTransfers(db, trades, plugin.getDataFolder().toPath().resolve("estates")), ready,
                plugin.getLogger());
        plugin.getServer().getServicesManager().register(EstateParticipant.class, estate, plugin, ServicePriority.Normal);
        plugin.getServer().getScheduler().runTaskTimer(plugin, estate::reconcile, 1L, HOUR_TICKS);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Outcome settle(EstateDecision decision) {
        return settle(decision, db.shops());
    }

    private Outcome settle(EstateDecision decision, List<Database.ShopRecord> shops) {
        if (!ready.getAsBoolean()) return Outcome.retry("Shops are waiting for character data");
        if (!guard.test(decision)) return Outcome.retry("The estate guard does not hold");
        Optional<CharacterRef> recipient = decision.property().character();
        // Waiting and frozen property stays with the testator; there is nothing to move at this revision.
        if (recipient.isEmpty()) return Outcome.done();
        String testator = decision.testator().characterId().toString();
        List<String> owned = shops.stream().filter(shop -> testator.equals(shop.ownerCharacter()))
                .map(Database.ShopRecord::shopId).sorted().toList();
        ShopTransfers.Result result;
        try {
            result = transfers.move(decision.deathId(), decision.testator().characterId(), recipient.get(), owned);
        } catch (IOException | RuntimeException ex) {
            logger.warning("Could not move the shops of estate " + decision.deathId() + ": " + ex);
            return Outcome.retry("Could not save a shop: " + ex.getMessage());
        }
        if (!result.blocked().isEmpty()) {
            return Outcome.blocked("Unfinished trades at shops " + String.join(", ", result.blocked())
                    + "; staff resolve them with /bartershops intent <shopId>");
        }
        return Outcome.done(Map.of("shops", result.moved().size()), result.moved());
    }

    @Override
    public Holdings describe(UUID characterId) {
        String character = characterId.toString();
        List<String> labels = db.shops().stream().filter(shop -> character.equals(shop.ownerCharacter()))
                .map(Database.ShopRecord::label).sorted().toList();
        return labels.isEmpty() ? Holdings.NONE : new Holdings(Map.of("shops", labels.size()), labels);
    }

    /** Settles every decided estate whose guard holds and reports outcomes that changed. */
    void reconcile() {
        if (!ready.getAsBoolean()) return;
        List<Database.ShopRecord> shops = db.shops();
        for (EstateDecision decision : decided.get()) {
            // One estate failing must not stop the others until the next hour.
            try {
                if (!guard.test(decision)) continue;
                Outcome outcome = settle(decision, shops);
                Map.Entry<Long, Outcome> last = Map.entry(decision.revision(), outcome);
                if (last.equals(reported.get(decision.deathId()))) continue;
                if (reporter.report(decision.deathId(), ID, decision.revision(), outcome)) {
                    reported.put(decision.deathId(), last);
                }
                // A recipient may also be a later testator, so read the shops again after a move.
                if (outcome instanceof Outcome.Done done && !done.movedIds().isEmpty()) shops = db.shops();
            } catch (RuntimeException ex) {
                logger.warning("Could not reconcile the shops of estate " + decision.deathId() + ": " + ex);
            }
        }
    }
}
