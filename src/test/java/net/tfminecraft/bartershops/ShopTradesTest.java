package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.tlibs.character.CharacterRef;

class ShopTradesTest {
    @TempDir Path directory;
    private final String shopId = UUID.randomUUID().toString();
    private final CharacterRef customer = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private final CharacterRef owner = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private final Logger logger = mock(Logger.class);

    private ShopTrades.Request request() {
        return new ShopTrades.Request(shopId, customer, owner, "buy", 5, 1000, "DIRT");
    }
    private Path intent() { return directory.resolve(shopId + ".json"); }

    @Test
    void recordsIntentBeforePostingAndDeletesItOnlyAfterDelivery() throws Exception {
        ShopTrades trades = new ShopTrades(directory, logger);
        AtomicInteger delivered = new AtomicInteger();
        try (var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
            denars.when(() -> Denars.status(anyString())).thenReturn(Optional.empty());
            denars.when(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()))
                    .thenAnswer(call -> {
                        String json = Files.readString(intent());
                        assertTrue(json.contains(call.<String>getArgument(0)));
                        assertTrue(json.contains("prepared"));
                        assertEquals(0, delivered.get());
                        assertEquals("char:" + owner.characterId() + ":bank", call.getArgument(4));
                        assertEquals(owner, call.getArgument(3));
                        assertEquals(true, call.getArgument(6));
                        return Result.APPLIED;
                    });
            assertEquals(ShopTrades.Outcome.APPLIED, trades.trade(request(), () -> {
                try { assertTrue(Files.readString(intent()).contains("delivering")); }
                catch (IOException ex) { fail(ex); }
                delivered.incrementAndGet();
            }));
            assertEquals(1, delivered.get());
            assertFalse(Files.exists(intent()));
        }
    }

    @Test
    void unavailableRetriesReuseTheDurableOperationId() throws Exception {
        ShopTrades trades = new ShopTrades(directory, logger);
        AtomicInteger delivered = new AtomicInteger();
        List<String> ids = new ArrayList<>();
        try (var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
            denars.when(() -> Denars.status(anyString())).thenReturn(Optional.empty());
            denars.when(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()))
                    .thenAnswer(call -> { ids.add(call.getArgument(0)); return ids.size() == 1 ? Result.UNAVAILABLE : Result.APPLIED; });
            assertEquals(ShopTrades.Outcome.WAITING, trades.trade(request(), delivered::incrementAndGet));
            assertTrue(Files.exists(intent()));
            assertEquals(0, delivered.get());
            assertEquals(ShopTrades.Outcome.APPLIED, trades.trade(request(), delivered::incrementAndGet));
            assertEquals(2, ids.size());
            assertEquals(ids.get(0), ids.get(1));
            assertEquals(1, delivered.get());
        }
    }

    @Test
    void appliedPostingIsNotRepricedWhenTaxOrAnEventFailsAfterCommit() {
        ShopTrades trades = new ShopTrades(directory, logger);
        AtomicInteger delivered = new AtomicInteger();
        try (var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
            denars.when(() -> Denars.status(anyString())).thenReturn(Optional.empty(), Optional.of(Result.APPLIED));
            denars.when(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()))
                    .thenThrow(new IllegalStateException("event after commit"));
            assertEquals(ShopTrades.Outcome.REVIEW, trades.trade(request(), delivered::incrementAndGet));
            assertEquals(ShopTrades.Outcome.APPLIED, trades.trade(request(), delivered::incrementAndGet));
            assertEquals(1, delivered.get());
            denars.verify(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()), times(1));
        }
    }

    @Test
    void failedDeliveryCannotReplayOrRechargeInThisProcessOrAfterRestart() throws Exception {
        ShopTrades trades = new ShopTrades(directory, logger);
        AtomicInteger delivered = new AtomicInteger();
        try (var denars = mockStatic(Denars.class)) {
            denars.when(() -> Denars.status(anyString())).thenReturn(Optional.of(Result.APPLIED));
            assertEquals(ShopTrades.Outcome.REVIEW, trades.trade(request(), () -> {
                delivered.incrementAndGet(); throw new IllegalStateException("partial item delivery");
            }));
            assertTrue(Files.readString(intent()).contains("delivering"));
            assertEquals(ShopTrades.Outcome.REVIEW, trades.trade(request(), delivered::incrementAndGet));
            assertEquals(ShopTrades.Outcome.REVIEW,
                    new ShopTrades(directory, logger).trade(request(), delivered::incrementAndGet));
            assertEquals(1, delivered.get());
            denars.verify(() -> Denars.status(anyString()), times(1));
            denars.verifyNoMoreInteractions();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Result.class, names = {"REFUSED", "CONFLICT"})
    void rejectionCannotMoveItems(Result result) throws Exception {
        ShopTrades trades = new ShopTrades(directory, logger);
        AtomicInteger delivered = new AtomicInteger();
        try (var denars = mockStatic(Denars.class)) {
            denars.when(() -> Denars.status(anyString())).thenReturn(Optional.of(result));
            assertEquals(result == Result.REFUSED ? ShopTrades.Outcome.REFUSED : ShopTrades.Outcome.REVIEW,
                    trades.trade(request(), delivered::incrementAndGet));
            assertEquals(0, delivered.get());
            assertEquals(result != Result.REFUSED, Files.exists(intent()));
        }
    }

    @Test
    void failingToSaveIntentCannotReachMoneyOrItems() throws Exception {
        Path notDirectory = directory.resolve("file");
        Files.writeString(notDirectory, "occupied");
        try (var denars = mockStatic(Denars.class)) {
            assertEquals(ShopTrades.Outcome.REVIEW, new ShopTrades(notDirectory, logger).trade(request(), () -> fail("delivery")));
            denars.verifyNoInteractions();
        }
    }

    @Test
    void changingCharacterOrTermsDoesNotAdoptAnUnfinishedTrade() {
        ShopTrades trades = new ShopTrades(directory, logger);
        try (var denars = mockStatic(Denars.class)) {
            denars.when(() -> Denars.trade(anyString(), anyString(), any(), any(), any(), anyLong(), anyBoolean()))
                    .thenReturn(Result.UNAVAILABLE);
            assertEquals(ShopTrades.Outcome.WAITING, trades.trade(request(), () -> fail("delivery")));
            var changed = new ShopTrades.Request(shopId, new CharacterRef(customer.ownerId(), UUID.randomUUID()),
                    owner, "buy", 5, 1000, "DIRT");
            assertEquals(ShopTrades.Outcome.REVIEW, trades.trade(changed, () -> fail("delivery")));
            denars.verify(() -> Denars.status(anyString()), times(1));
        }
    }

    @Test
    void freeShopsRecordAZeroPosting() {
        ShopTrades trades = new ShopTrades(directory, logger);
        var free = new ShopTrades.Request(shopId, customer, owner, "sell", 5, 0, "DIRT");
        try (var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
            denars.when(() -> Denars.status(anyString())).thenReturn(Optional.empty());
            denars.when(() -> Denars.post(anyString(), anyString(), anyList())).thenReturn(Result.APPLIED);
            assertEquals(ShopTrades.Outcome.APPLIED, trades.trade(free, () -> {}));
            denars.verify(() -> Denars.post(startsWith("shop:"), eq("shop:sell"), argThat(legs ->
                    legs.size() == 2 && legs.stream().allMatch(leg -> leg.cents() == 0))));
        }
    }

    @Test
    void conflictingPostingCannotBecomeDeliveryOnARetry() {
        ShopTrades trades = new ShopTrades(directory, logger);
        try (var denars = mockStatic(Denars.class)) {
            denars.when(() -> Denars.status(anyString())).thenReturn(Optional.of(Result.CONFLICT), Optional.of(Result.APPLIED));
            assertEquals(ShopTrades.Outcome.REVIEW, trades.trade(request(), () -> fail("delivery")));
            assertEquals(ShopTrades.Outcome.REVIEW, trades.trade(request(), () -> fail("delivery")));
            denars.verify(() -> Denars.status(anyString()), times(1));
        }
    }
    @Test
    void inspectionPrefersThisProcessesStateAndReadsEarlierRecords() throws Exception {
        ShopTrades trades = new ShopTrades(directory, logger);
        assertFalse(trades.hasIntent(shopId));
        assertEquals(Optional.empty(), trades.inspect(shopId));
        try (var denars = mockStatic(Denars.class)) {
            denars.when(() -> Denars.status(anyString())).thenReturn(Optional.of(Result.APPLIED));
            assertEquals(ShopTrades.Outcome.REVIEW, trades.trade(request(), () -> { throw new IllegalStateException(); }));
        }
        assertTrue(trades.hasIntent(shopId));
        assertEquals("delivering", trades.inspect(shopId).orElseThrow().state());
        ShopTrades restarted = new ShopTrades(directory, logger);
        assertTrue(restarted.hasIntent(shopId));
        ShopTrades.Intent read = restarted.inspect(shopId).orElseThrow();
        assertEquals("delivering", read.state());
        assertEquals(request(), read.request());
        assertTrue(read.operationId().startsWith("shop:" + shopId + ":"));

        restarted.clear(shopId);
        assertFalse(Files.exists(intent()));
        assertFalse(restarted.hasIntent(shopId));
    }

    @Test
    void unreadableOrIncompleteRecordsCannotBeInspected() throws Exception {
        ShopTrades trades = new ShopTrades(directory, logger);
        Files.writeString(intent(), "{ broken");
        assertThrows(IOException.class, () -> trades.inspect(shopId));
        Files.writeString(intent(), "{\"state\": \"prepared\"}");
        assertThrows(IOException.class, () -> trades.inspect(shopId));
        Files.writeString(intent(), "");
        assertThrows(IOException.class, () -> trades.inspect(shopId));
        assertTrue(trades.hasIntent(shopId));
    }
    @Test
    void startupIndexOffersOnlyIntentFilenamesThatInspectionResolves() throws Exception {
        Files.writeString(intent(), new com.google.gson.Gson().toJson(
                new ShopTrades.Intent("retained-operation", request(), "prepared")));
        String shortened = "1-1-1-1-1";
        String uppercase = "ABCDEFAB-ABCD-4ABC-8ABC-ABCDEFABCDEF";
        Files.writeString(directory.resolve(shortened + ".json"), "{}");
        Files.writeString(directory.resolve(uppercase + ".json"), "{}");
        ShopTrades trades = new ShopTrades(directory, logger);
        assertEquals(List.of(shopId), trades.retainedShopIds());
        assertEquals("prepared", trades.inspect(shopId).orElseThrow().state());
        assertTrue(trades.inspect(UUID.fromString(shortened).toString()).isEmpty());
        assertTrue(trades.inspect(UUID.fromString(uppercase).toString()).isEmpty());
    }

    @Test
    void traversalFailureKeepsAlreadyIndexedTradesAndLogsWithoutPreventingStartup() throws Exception {
        Files.writeString(intent(), "{}");
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var paths = java.util.stream.Stream.concat(java.util.stream.Stream.of(intent()),
                java.util.stream.Stream.<Path>generate(() -> {
                    throw new java.io.UncheckedIOException(new IOException("Directory access lost"));
                })).onClose(() -> closed.set(true));
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.list(directory)).thenReturn(paths);
            ShopTrades trades = assertDoesNotThrow(() -> new ShopTrades(directory, logger));
            assertEquals(List.of(shopId), trades.retainedShopIds());
            verify(logger).warning(contains("Could not index retained shop trades"));
            assertTrue(closed.get());
        }
    }

}
