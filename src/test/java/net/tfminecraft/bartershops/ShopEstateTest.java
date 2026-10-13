package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;

class ShopEstateTest {
    private static final CharacterRef TESTATOR = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private static final CharacterRef HEIR = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private static final CharacterRef OTHER = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private static final UUID DEATH = UUID.randomUUID();

    @TempDir Path directory;
    private Path data;
    private Path tradesDirectory;
    private Database db;
    private ShopTrades trades;
    private ShopTransfers transfers;
    private ShopEstate estate;
    private boolean ready = true;
    private boolean guard = true;

    @BeforeEach
    void setUp() {
        data = directory.resolve("Data");
        tradesDirectory = directory.resolve("trades");
        db = new Database(data.toFile());
        trades = new ShopTrades(tradesDirectory, Logger.getAnonymousLogger());
        transfers = new ShopTransfers(db, trades, directory.resolve("estates"));
        estate = new ShopEstate(db, transfers, () -> ready, Logger.getAnonymousLogger());
        estate.guard = decision -> guard;
    }

    // --- Database ---

    @Test
    void ownerIsRewrittenInPlaceKeepingEveryOtherValue() throws Exception {
        String id = shop(TESTATOR, "buy", 10.5);
        Path file = data.resolve(id + ".json");
        Map<?, ?> before = json(file);

        assertTrue(db.setOwner(id, HEIR));

        assertEquals(List.of(file), files(data));
        Map<?, ?> after = json(file);
        assertEquals(HEIR.ownerId().toString(), after.get(Database.OWNER));
        assertEquals(HEIR.characterId().toString(), after.get(Database.OWNER_CHARACTER));
        for (Object key : before.keySet()) {
            if (key.equals(Database.OWNER) || key.equals(Database.OWNER_CHARACTER)) continue;
            assertEquals(before.get(key), after.get(key), key.toString());
        }
        assertEquals(Optional.of(HEIR.characterId().toString()), db.ownerCharacter(id));
        assertTrue(Files.readString(file).contains("\"sign xPos\": 10.5"));
    }

    @Test
    void missingShopsAreNotRewrittenOrCreated() throws Exception {
        String id = UUID.randomUUID().toString();
        assertFalse(db.setOwner(id, HEIR));
        assertEquals(Optional.empty(), db.ownerCharacter(id));
        assertFalse(Files.exists(data.resolve(id + ".json")));
        assertThrows(IllegalArgumentException.class, () -> db.setOwner("../escape", HEIR));
    }

    @Test
    void shopListingSkipsUnreadableFilesAndLabelsSigns() throws Exception {
        String id = shop(TESTATOR, "sell", -3.2);
        Files.writeString(data.resolve(UUID.randomUUID() + ".json"), "{ not json");
        Files.writeString(data.resolve(UUID.randomUUID() + ".json"), "");

        List<Database.ShopRecord> shops = db.shops();

        assertEquals(1, shops.size());
        assertEquals(id, shops.get(0).shopId());
        assertEquals(TESTATOR.characterId().toString(), shops.get(0).ownerCharacter());
        assertEquals("Sell shop at world -4, 64, -4", shops.get(0).label());
        assertEquals("Shop at w 0, 0, 0", new Database.ShopRecord("x", null, null, "w", 0, 0, 0).label());
        assertEquals("Shop at w 0, 0, 0", new Database.ShopRecord("x", null, "", "w", 0, 0, 0).label());
    }

    // --- Transfers ---

    @Test
    void decidedEstateGivesEveryShopOfTheTestatorToTheRecipient() throws Exception {
        String first = shop(TESTATOR, "buy", 1);
        String second = shop(TESTATOR, "sell", 2);
        String others = shop(OTHER, "buy", 3);

        Outcome outcome = estate.settle(decision(Recipient.character(HEIR), 0));

        List<String> moved = new ArrayList<>(List.of(first, second));
        moved.sort(null);
        assertEquals(Outcome.done(Map.of("shops", 2), moved), outcome);
        assertEquals(Optional.of(HEIR.characterId().toString()), db.ownerCharacter(first));
        assertEquals(Optional.of(HEIR.characterId().toString()), db.ownerCharacter(second));
        assertEquals(HEIR.ownerId().toString(), json(data.resolve(first + ".json")).get(Database.OWNER));
        assertEquals(Optional.of(OTHER.characterId().toString()), db.ownerCharacter(others));
    }

    @Test
    void settlingAgainMovesNothingTwiceAndStillReportsEveryMovedShop() throws Exception {
        String id = shop(TESTATOR, "buy", 1);
        Outcome first = estate.settle(decision(Recipient.character(HEIR), 0));
        String after = Files.readString(data.resolve(id + ".json"));

        Outcome again = estate.settle(decision(Recipient.character(HEIR), 1));

        assertEquals(first, again);
        assertEquals(Outcome.done(Map.of("shops", 1), List.of(id)), again);
        assertEquals(after, Files.readString(data.resolve(id + ".json")));
    }

    @Test
    void crashAfterThePlanFinishesTheMoveOnTheNextSettle() throws Exception {
        String id = shop(TESTATOR, "buy", 1);
        Path journal = directory.resolve("estates").resolve(DEATH + ".json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, new Gson().toJson(new ShopTransfers.Journal(DEATH.toString(),
                TESTATOR.characterId().toString(), Map.of(id, new ShopTransfers.Entry(HEIR.ownerId().toString(),
                        HEIR.characterId().toString(), ShopTransfers.PLANNED)))));
        // A shop planned before the crash is finished even when the scan does not list it.
        ShopTransfers.Result result = transfers.move(DEATH, TESTATOR.characterId(), HEIR, List.of());

        assertEquals(List.of(id), result.moved());
        assertEquals(Optional.of(HEIR.characterId().toString()), db.ownerCharacter(id));
        assertTrue(Files.readString(journal).contains(ShopTransfers.MOVED));
        assertEquals(Outcome.done(Map.of("shops", 1), List.of(id)), estate.settle(decision(Recipient.character(HEIR), 0)));
    }

    @Test
    void crashAfterTheShopWriteCountsTheShopWithoutWritingItAgain() throws Exception {
        String id = shop(HEIR, "buy", 1);
        Path journal = directory.resolve("estates").resolve(DEATH + ".json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, new Gson().toJson(new ShopTransfers.Journal(DEATH.toString(),
                TESTATOR.characterId().toString(), Map.of(id, new ShopTransfers.Entry(HEIR.ownerId().toString(),
                        HEIR.characterId().toString(), ShopTransfers.PLANNED)))));
        Database spy = spy(db);
        ShopEstate replay = new ShopEstate(spy, new ShopTransfers(spy, trades, directory.resolve("estates")),
                () -> true, Logger.getAnonymousLogger());
        replay.guard = decision -> true;

        assertEquals(Outcome.done(Map.of("shops", 1), List.of(id)), replay.settle(decision(Recipient.character(HEIR), 0)));
        verify(spy, never()).setOwner(anyString(), any());
    }

    @Test
    void shopsRemovedOrGivenAwayBeforeTheMoveAreLeftAlone() throws Exception {
        String gone = UUID.randomUUID().toString();
        String givenAway = shop(OTHER, "buy", 1);
        Path journal = directory.resolve("estates").resolve(DEATH + ".json");
        Files.createDirectories(journal.getParent());
        ShopTransfers.Entry planned = new ShopTransfers.Entry(HEIR.ownerId().toString(), HEIR.characterId().toString(),
                ShopTransfers.PLANNED);
        Files.writeString(journal, new Gson().toJson(new ShopTransfers.Journal(DEATH.toString(),
                TESTATOR.characterId().toString(), Map.of(gone, planned, givenAway, planned))));

        assertEquals(Outcome.done(Map.of("shops", 0), List.of()), estate.settle(decision(Recipient.character(HEIR), 0)));
        assertEquals(Optional.of(OTHER.characterId().toString()), db.ownerCharacter(givenAway));
        assertFalse(Files.readString(journal).contains(ShopTransfers.PLANNED));
    }

    @Test
    void shopWithAnUnfinishedTradeIsBlockedAndKeptWhileTheOthersMove() throws Exception {
        String stuck = shop(TESTATOR, "buy", 1);
        String free = shop(TESTATOR, "buy", 2);
        Files.createDirectories(tradesDirectory);
        Files.writeString(tradesDirectory.resolve(stuck + ".json"), "{}");

        Outcome outcome = estate.settle(decision(Recipient.character(HEIR), 0));

        Outcome.Blocked blocked = assertInstanceOf(Outcome.Blocked.class, outcome);
        assertTrue(blocked.reason().contains(stuck));
        assertTrue(blocked.reason().contains("/bartershops intent"));
        assertFalse(blocked.reason().contains(free));
        assertEquals(Optional.of(TESTATOR.characterId().toString()), db.ownerCharacter(stuck));
        assertEquals(Optional.of(HEIR.characterId().toString()), db.ownerCharacter(free));

        // Staff clear the trade; the next settle moves the shop and reports both.
        trades.clear(stuck);
        Outcome.Done done = assertInstanceOf(Outcome.Done.class, estate.settle(decision(Recipient.character(HEIR), 0)));
        assertEquals(Map.of("shops", 2), done.counts());
        assertEquals(java.util.Set.of(stuck, free), java.util.Set.copyOf(done.movedIds()));
    }

    @Test
    void waitingAndFrozenPropertyMovesNothingAndAnswersDone() throws Exception {
        String id = shop(TESTATOR, "buy", 1);
        assertEquals(Outcome.done(), estate.settle(decision(Recipient.WAITING, 0)));
        assertEquals(Outcome.done(), estate.settle(decision(Recipient.FROZEN, 1)));
        assertEquals(Optional.of(TESTATOR.characterId().toString()), db.ownerCharacter(id));
        assertFalse(Files.exists(directory.resolve("estates")));
    }

    @Test
    void failingGuardOrUnmigratedShopsMoveNothing() throws Exception {
        String id = shop(TESTATOR, "buy", 1);
        guard = false;
        assertEquals(Outcome.Kind.RETRY, estate.settle(decision(Recipient.character(HEIR), 0)).kind());
        guard = true;
        ready = false;
        assertEquals(Outcome.Kind.RETRY, estate.settle(decision(Recipient.character(HEIR), 0)).kind());
        assertEquals(Optional.of(TESTATOR.characterId().toString()), db.ownerCharacter(id));
    }

    @Test
    void failedWriteRetriesLater() throws Exception {
        shop(TESTATOR, "buy", 1);
        Files.writeString(directory.resolve("estates"), "not a directory");

        Outcome.Retry retry = assertInstanceOf(Outcome.Retry.class, estate.settle(decision(Recipient.character(HEIR), 0)));
        assertTrue(retry.reason().startsWith("Could not save a shop"));
    }

    @Test
    void unreadableJournalRetriesWithoutMoving() throws Exception {
        String id = shop(TESTATOR, "buy", 1);
        Files.createDirectories(directory.resolve("estates"));
        Files.writeString(directory.resolve("estates").resolve(DEATH + ".json"), "{ broken");
        assertEquals(Outcome.Kind.RETRY, estate.settle(decision(Recipient.character(HEIR), 0)).kind());
        Files.writeString(directory.resolve("estates").resolve(DEATH + ".json"), "{}");
        assertEquals(Outcome.Kind.RETRY, estate.settle(decision(Recipient.character(HEIR), 0)).kind());
        assertEquals(Optional.of(TESTATOR.characterId().toString()), db.ownerCharacter(id));
    }

    @Test
    void shopFileNotNamedByAShopIdIsBlocked() throws Exception {
        Files.createDirectories(data);
        Files.writeString(data.resolve("odd.json"), shopJson(TESTATOR, "buy", 1));
        Outcome.Blocked blocked = assertInstanceOf(Outcome.Blocked.class, estate.settle(decision(Recipient.character(HEIR), 0)));
        assertTrue(blocked.reason().contains("odd"));
    }

    // --- Describe and reconcile ---

    @Test
    void describeListsTheCharactersShops() throws Exception {
        shop(TESTATOR, "buy", 1);
        shop(TESTATOR, "sell", 2);
        shop(OTHER, "buy", 3);

        Holdings holdings = estate.describe(TESTATOR.characterId());

        assertEquals(Map.of("shops", 2), holdings.counts());
        assertEquals(List.of("Buy shop at world 1, 64, 1", "Sell shop at world 2, 64, 2"), holdings.labels());
        assertSame(Holdings.NONE, estate.describe(HEIR.characterId()));
    }

    @Test
    void reconcileSettlesDecidedEstatesAndReportsOnlyChanges() throws Exception {
        String id = shop(TESTATOR, "buy", 1);
        EstateDecision decided = decision(Recipient.character(HEIR), 0);
        EstateDecision reverted = new EstateDecision(UUID.randomUUID(), OTHER, Optional.empty(), Instant.EPOCH,
                Optional.empty(), Optional.empty(), Optional.empty(), Recipient.FROZEN, Recipient.character(HEIR),
                Recipient.FROZEN, Recipient.SENDER, 0);
        shop(OTHER, "buy", 2);
        estate.guard = decision -> decision != reverted;
        estate.decided = () -> List.of(reverted, decided);
        List<Object[]> reports = new ArrayList<>();
        estate.reporter = (death, participant, revision, outcome) -> reports.add(new Object[] {death, participant, revision, outcome});

        estate.reconcile();
        estate.reconcile();

        assertEquals(1, reports.size());
        assertEquals(DEATH, reports.get(0)[0]);
        assertEquals("bartershops", reports.get(0)[1]);
        assertEquals(0L, reports.get(0)[2]);
        assertEquals(Outcome.done(Map.of("shops", 1), List.of(id)), reports.get(0)[3]);
        assertEquals(Optional.of(OTHER.characterId().toString()), db.ownerCharacter(shopOf(OTHER)));

        // A new revision is reported again.
        estate.decided = () -> List.of(decision(Recipient.character(HEIR), 1));
        estate.reconcile();
        assertEquals(2, reports.size());
        assertEquals(1L, reports.get(1)[2]);
    }

    @Test
    void reconcileReportsAgainWhenNoServiceTookTheReport() throws Exception {
        shop(TESTATOR, "buy", 1);
        estate.decided = () -> List.of(decision(Recipient.character(HEIR), 0));
        List<Outcome> reports = new ArrayList<>();
        estate.reporter = (death, participant, revision, outcome) -> reports.add(outcome) && false;

        estate.reconcile();
        estate.reconcile();

        assertEquals(2, reports.size());
    }

    @Test
    void reconcileWaitsForMigration() {
        ready = false;
        estate.decided = () -> fail("read before shops were ready");
        estate.reconcile();
    }

    @Test
    void shopsReceivedFromOneEstateFollowTheRecipientsOwnEstate() throws Exception {
        String id = shop(TESTATOR, "buy", 1);
        UUID heirDeath = UUID.randomUUID();
        EstateDecision heirs = new EstateDecision(heirDeath, HEIR, Optional.empty(), Instant.EPOCH, Optional.empty(),
                Optional.empty(), Optional.empty(), Recipient.FROZEN, Recipient.character(OTHER), Recipient.FROZEN,
                Recipient.SENDER, 0);
        estate.decided = () -> List.of(decision(Recipient.character(HEIR), 0), heirs);
        estate.reporter = (death, participant, revision, outcome) -> true;

        estate.reconcile();

        assertEquals(Optional.of(OTHER.characterId().toString()), db.ownerCharacter(id));
    }

    @Test
    void startRegistersTheParticipantAndReconcilesOnTheFirstTickThenHourly() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        ServicesManager services = mock(ServicesManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getServicesManager()).thenReturn(services);
        when(server.getScheduler()).thenReturn(scheduler);

        ShopEstate.start(plugin, db, trades, () -> true);

        var participant = org.mockito.ArgumentCaptor.forClass(EstateParticipant.class);
        verify(services).register(eq(EstateParticipant.class), participant.capture(), eq(plugin), eq(ServicePriority.Normal));
        assertEquals("bartershops", participant.getValue().id());
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(72_000L));
    }

    @Test
    void probeFindsTheEstateApiAndMissesAbsentClasses() {
        assertTrue(EstateProbe.available(EstateProbe.CLASSES));
        assertFalse(EstateProbe.available("net.tfminecraft.tlibs.character.estate.Missing"));
    }

    // --- Helpers ---

    private EstateDecision decision(Recipient property, long revision) {
        return new EstateDecision(DEATH, TESTATOR, Optional.of(Instant.EPOCH), Instant.EPOCH, Optional.empty(),
                Optional.empty(), Optional.empty(), Recipient.FROZEN, property, Recipient.FROZEN, Recipient.SENDER, revision);
    }

    private String shopOf(CharacterRef owner) {
        return db.shops().stream().filter(shop -> owner.characterId().toString().equals(shop.ownerCharacter()))
                .findFirst().orElseThrow().shopId();
    }

    private String shop(CharacterRef owner, String type, double x) throws IOException {
        String id = UUID.randomUUID().toString();
        Files.createDirectories(data);
        Files.writeString(data.resolve(id + ".json"), shopJson(owner, type, x));
        return id;
    }

    private static String shopJson(CharacterRef owner, String type, double x) {
        return """
            {
              "barter amount": 5.0,
              "owner": "%s",
              "owner character": "%s",
              "payment item": "MATERIAL.COIN",
              "price": 10.0,
              "sign world": "world",
              "sign xPos": %s,
              "sign yPos": 64.0,
              "sign zPos": %s,
              "storage world": "world",
              "storage xPos": 0.0,
              "storage yPos": 63.0,
              "storage zPos": 0.0,
              "type": "%s"
            }""".formatted(owner.ownerId(), owner.characterId(), x, x, type);
    }

    private static Map<?, ?> json(Path file) throws IOException {
        return Database.SHOP_READER.fromJson(Files.readString(file), Map.class);
    }

    private static List<Path> files(Path folder) throws IOException {
        try (var stream = Files.list(folder)) {
            return stream.toList();
        }
    }
}
