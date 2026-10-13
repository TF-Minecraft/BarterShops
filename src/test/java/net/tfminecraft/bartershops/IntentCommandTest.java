package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.google.gson.Gson;

import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.tlibs.character.CharacterRef;

class IntentCommandTest {
    private static final CharacterRef CUSTOMER = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private static final CharacterRef OWNER = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private static final String OPERATION = "shop:op";

    @TempDir Path directory;
    private final String shopId = UUID.randomUUID().toString();
    private ShopTrades trades;
    private IntentCommand command;
    private CommandSender staff;
    private final List<String> messages = new ArrayList<>();
    private MockedStatic<Denars> denars;

    @BeforeEach
    void setUp() {
        trades = new ShopTrades(directory.resolve("trades"), Logger.getAnonymousLogger());
        command = new IntentCommand(trades, audit(), Logger.getAnonymousLogger());
        staff = mock(CommandSender.class);
        when(staff.getName()).thenReturn("Moderator");
        when(staff.hasPermission(ShopEvents.ADMIN_PERMISSION)).thenReturn(true);
        doAnswer(call -> messages.add(call.getArgument(0))).when(staff).sendMessage(anyString());
        denars = mockStatic(Denars.class);
        denars.when(Denars::ready).thenReturn(true);
        denars.when(() -> Denars.pouch(any())).thenReturn("pouch");
        denars.when(() -> Denars.bank(any())).thenReturn("bank");
    }

    @AfterEach
    void tearDown() {
        denars.close();
    }

    @ParameterizedTest
    @CsvSource({
        "prepared,,true,YES", "prepared,REFUSED,true,YES",
        "prepared,APPLIED,true,CONFIRM", "prepared,CONFLICT,true,CONFIRM",
        "delivering,APPLIED,true,CONFIRM", "delivering,,true,CONFIRM", "conflict,CONFLICT,true,CONFIRM",
        "conflict,,true,CONFIRM", "prepared,,false,LEDGER", "delivering,APPLIED,false,LEDGER"
    })
    void clearingFollowsTheIntentStatePostingAndLedger(String state, Result posting, boolean ready, IntentCommand.Clear expected) {
        assertEquals(expected, IntentCommand.clearable(state, Optional.ofNullable(posting), ready));
    }

    @Test
    void preparedTradeThatMovedNoMoneyClearsFileAndMemory() throws Exception {
        intentInMemory(Result.UNAVAILABLE);
        denars.when(() -> Denars.status(anyString())).thenReturn(Optional.empty());
        assertTrue(Files.exists(intentFile()));

        run("intent", shopId, "clear");

        assertFalse(Files.exists(intentFile()));
        assertFalse(trades.hasIntent(shopId));
        assertTrue(messages.stream().anyMatch(message -> message.contains("it trades again")));
        assertTrue(Files.readString(audit()).contains("Moderator cleared the trade at shop " + shopId + " state prepared, posting absent"));
        // The shop trades again with a fresh operation.
        denars.when(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()))
                .thenReturn(Result.APPLIED);
        assertEquals(ShopTrades.Outcome.APPLIED, trades.trade(request(), () -> {}));
    }

    @Test
    void preparedTradeWithARefusedPostingClearsFromTheFile() throws Exception {
        writeIntent("prepared");
        denars.when(() -> Denars.status(OPERATION)).thenReturn(Optional.of(Result.REFUSED));

        run("intent", shopId, "clear");

        assertFalse(Files.exists(intentFile()));
    }

    @ParameterizedTest
    @CsvSource({"prepared,APPLIED", "delivering,APPLIED", "conflict,CONFLICT", "delivering,"})
    void tradesThatMayHaveMovedSomethingNeedConfirmation(String state, Result posting) throws Exception {
        writeIntent(state);
        denars.when(() -> Denars.status(OPERATION)).thenReturn(Optional.ofNullable(posting));

        run("intent", shopId, "clear");

        assertTrue(Files.exists(intentFile()));
        assertTrue(trades.hasIntent(shopId));
        assertTrue(messages.stream().anyMatch(message -> message.contains("clear --confirm")));

        run("intent", shopId, "clear", "--confirm");

        assertFalse(Files.exists(intentFile()));
        assertTrue(Files.readString(audit()).contains("(confirmed)"));
    }

    @Test
    void appliedPaymentTellsStaffToHandOverTheItems() throws Exception {
        writeIntent("prepared");
        denars.when(() -> Denars.status(OPERATION)).thenReturn(Optional.of(Result.APPLIED));

        run("intent", shopId);

        assertTrue(messages.get(0).contains("state prepared, posting APPLIED"));
        assertTrue(messages.stream().anyMatch(message -> message.contains("Hand over the items")));
        assertTrue(Files.exists(intentFile()));
        assertTrue(Files.readString(audit()).contains("Moderator viewed the trade at shop " + shopId));
    }

    @Test
    void deliveringTradeIsShownWithItsRequest() throws Exception {
        writeIntent("delivering");
        denars.when(() -> Denars.status(OPERATION)).thenReturn(Optional.of(Result.APPLIED));

        run("intent", shopId);

        assertTrue(messages.stream().anyMatch(message -> message.contains("Operation " + OPERATION)));
        assertTrue(messages.stream().anyMatch(message -> message.contains("buy of 5 for 1000 cents, customer " + CUSTOMER.characterId())));
        assertTrue(messages.stream().anyMatch(message -> message.contains("may already have moved")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "--confirm"})
    void nothingClearsWhileTheLedgerIsNotReady(String confirm) throws Exception {
        writeIntent("prepared");
        denars.when(Denars::ready).thenReturn(false);

        run(confirm.isEmpty() ? new String[] {"intent", shopId, "clear"} : new String[] {"intent", shopId, "clear", confirm});

        assertTrue(Files.exists(intentFile()));
        assertTrue(messages.stream().anyMatch(message -> message.contains("ledger is not ready")));
        denars.verify(() -> Denars.status(anyString()), never());
    }

    @Test
    void ledgerNotReadyIsShownWhenViewing() throws Exception {
        writeIntent("prepared");
        denars.when(Denars::ready).thenReturn(false);

        run("intent", shopId);

        assertTrue(messages.get(0).contains("posting unknown (ledger not ready)"));
        assertTrue(messages.stream().anyMatch(message -> message.contains("nothing can be cleared yet")));
    }

    @Test
    void unreadableRecordIsShownAndClearsOnlyWithConfirmation() throws Exception {
        Files.createDirectories(intentFile().getParent());
        Files.writeString(intentFile(), "{ broken");

        run("intent", shopId, "clear");
        assertTrue(Files.exists(intentFile()));
        assertTrue(messages.stream().anyMatch(message -> message.contains("cannot be read")));

        run("intent", shopId, "clear", "--confirm");
        assertFalse(Files.exists(intentFile()));
        assertTrue(Files.readString(audit()).contains("state unreadable, posting unknown"));
    }

    @Test
    void shopWithoutATradeSaysSo() {
        run("intent", shopId);
        assertEquals(List.of("§aShop " + shopId + " has no unfinished trade."), messages);
    }

    @Test
    void failedRemovalKeepsTheRecord() throws Exception {
        writeIntent("prepared");
        denars.when(() -> Denars.status(OPERATION)).thenReturn(Optional.empty());
        Path file = intentFile();
        // A non-empty directory where the file should be cannot be deleted.
        Files.delete(file);
        Files.createDirectories(file.resolve("x"));
        ShopTrades broken = spy(trades);
        doReturn(Optional.of(new ShopTrades.Intent(OPERATION, request(), "prepared"))).when(broken).inspect(shopId);
        command = new IntentCommand(broken, audit(), Logger.getAnonymousLogger());

        run("intent", shopId, "clear");

        assertTrue(messages.stream().anyMatch(message -> message.startsWith("§cCould not remove the trade record")));
        assertFalse(Files.exists(audit()));
    }

    @Test
    void staffOnly() throws Exception {
        writeIntent("prepared");
        when(staff.hasPermission(ShopEvents.ADMIN_PERMISSION)).thenReturn(false);

        run("intent", shopId, "clear");

        assertTrue(Files.exists(intentFile()));
        assertEquals(List.of("§cYou do not have permission to use this command."), messages);
        assertEquals(List.of(), command.onTabComplete(staff, mock(Command.class), "bartershops", new String[] {""}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "list", "intent", "intent x y", "intent x clear --force", "intent x clear --confirm more"})
    void malformedCommandsShowUsage(String line) {
        run(line.isEmpty() ? new String[0] : line.replace("x", shopId).split(" "));
        assertEquals(List.of(IntentCommand.USAGE), messages);
    }

    @Test
    void shopIdMustBeAUuid() {
        run("intent", "../trades/x");
        assertEquals(List.of("§cA shop ID is a UUID, the name of its file in Data/."), messages);
    }

    @Test
    void tabCompletesTheCommandWords() {
        Command bukkit = mock(Command.class);
        assertEquals(List.of("intent"), command.onTabComplete(staff, bukkit, "bartershops", new String[] {""}));
        assertEquals(List.of(), command.onTabComplete(staff, bukkit, "bartershops", new String[] {"intent", ""}));
        assertEquals(List.of("clear"), command.onTabComplete(staff, bukkit, "bartershops", new String[] {"intent", shopId, ""}));
        assertEquals(List.of("--confirm"), command.onTabComplete(staff, bukkit, "bartershops", new String[] {"intent", shopId, "clear", ""}));
        assertEquals(List.of(), command.onTabComplete(staff, bukkit, "bartershops", new String[] {"intent", shopId, "x", ""}));
    }

    private void run(String... args) {
        assertTrue(command.onCommand(staff, mock(Command.class), "bartershops", args));
    }

    private ShopTrades.Request request() {
        return new ShopTrades.Request(shopId, CUSTOMER, OWNER, "buy", 5, 1000, "DIRT");
    }

    private void intentInMemory(Result posting) {
        denars.when(() -> Denars.status(anyString())).thenReturn(Optional.empty());
        denars.when(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()))
                .thenReturn(posting);
        assertEquals(ShopTrades.Outcome.WAITING, trades.trade(request(), () -> fail("delivery")));
    }

    private void writeIntent(String state) throws Exception {
        Files.createDirectories(intentFile().getParent());
        Files.writeString(intentFile(), new Gson().toJson(new ShopTrades.Intent(OPERATION, request(), state)));
    }

    private Path intentFile() {
        return directory.resolve("trades").resolve(shopId + ".json");
    }

    private Path audit() {
        return directory.resolve("logs").resolve("staff-audit.log");
    }
}
