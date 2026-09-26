package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import io.papermc.paper.registry.RegistryAccess;
import net.tfminecraft.bartershops.sf.ShopEmbargo;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.denareconomy.managers.PlayerManager;

class ShopEventsTest {
    @SuppressWarnings("deprecation")
    @ParameterizedTest
    @CsvSource({"1,0", "64,100", "2147483647,2147483647"})
    void validCreationStillCompletesIncludingFreeShops(int quantity, int price) {
        ShopEvents events = new ShopEvents();
        events.db = mock(Database.class);
        Player player = mock(Player.class);
        ShopMain previousPlugin = ShopMain.plugin;
        ShopMain plugin = mock(ShopMain.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        List<Runnable> tasks = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(1));
            return null;
        });
        Location location = mock(Location.class);
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        when(location.getBlock()).thenReturn(block);
        when(block.getState()).thenReturn(sign);
        ShopSign shop = new ShopSign();
        shop.setSignLoc(location);
        shop.setType("buy");
        events.currentShop.put(player, shop);
        events.shopCreating.put(player, 3);
        ShopMain.plugin = plugin;
        try {
            events.chatEvent(new AsyncPlayerChatEvent(true, player, String.valueOf(quantity), Set.of()));
            assertEquals(1, tasks.size());
            tasks.get(0).run();
            assertEquals(4, events.shopCreating.get(player));
            events.chatEvent(new AsyncPlayerChatEvent(true, player, String.valueOf(price), Set.of()));
            assertEquals(2, tasks.size());
            tasks.get(1).run();
            assertEquals(quantity, shop.getBarterAmount());
            assertEquals(price, shop.getPrice());
            verify(events.db).saveShop(shop);
            assertFalse(events.shopCreating.containsKey(player));
            assertFalse(events.currentShop.containsKey(player));
        } finally {
            ShopMain.plugin = previousPlugin;
        }
    }

    static Stream<Arguments> invalidCreationInputs() {
        return Stream.of(
            Arguments.of(3, "ten", "§cInvalid Format, use numbers only"),
            Arguments.of(3, "0", "§cItem quantity must be greater than 0."),
            Arguments.of(3, "-1", "§cItem quantity must be greater than 0."),
            Arguments.of(3, "-2147483648", "§cItem quantity must be greater than 0."),
            Arguments.of(4, "1.5", "§cInvalid Format, use numbers only"),
            Arguments.of(4, "-1", "§cPrice must be 0 or greater."),
            Arguments.of(4, "-2147483647", "§cPrice must be 0 or greater."),
            Arguments.of(4, "-2147483648", "§cPrice must be 0 or greater.")
        );
    }

    @SuppressWarnings("deprecation")
    @ParameterizedTest
    @MethodSource("invalidCreationInputs")
    void invalidChatInputLeavesCreationAtTheSameStep(int stage, String input, String error) {
        ShopEvents events = new ShopEvents();
        events.db = mock(Database.class);
        Player player = mock(Player.class);
        ShopSign shop = new ShopSign();
        events.shopCreating.put(player, stage);
        events.currentShop.put(player, shop);
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, input, Set.of());

        assertDoesNotThrow(() -> events.chatEvent(event));

        assertTrue(event.isCancelled());
        assertEquals(stage, events.shopCreating.get(player));
        assertNull(shop.getPrice());
        assertNull(shop.getBarterAmount());
        verify(player).sendMessage(error);
        verifyNoInteractions(events.db);
    }

    static Stream<Arguments> invalidTransactions() {
        return Stream.of("buy", "sell").flatMap(type -> Stream.of(
            Arguments.of(type, 0, -2147483647),
            Arguments.of(type, 1, -2147483647),
            Arguments.of(type, 1, Integer.MIN_VALUE),
            Arguments.of(type, 0, 100),
            Arguments.of(type, -1, 100),
            Arguments.of(type, null, 100),
            Arguments.of(type, 1, null)
        ));
    }

    @ParameterizedTest
    @MethodSource("invalidTransactions")
    void invalidShopCannotReachInventoryOrEconomy(String type, Integer quantity, Integer price) {
        ShopSign shop = new ShopSign();
        shop.setType(type);
        shop.setBarterAmount(quantity);
        shop.setPrice(price);
        assertShopRejected(shop);
    }

    @Test
    void refusedSavedShopDoesNotCauseNullDereference() {
        assertShopRejected(null);
    }

    private void assertShopRejected(ShopSign shop) {
        ShopEvents events = new ShopEvents();
        events.db = mock(Database.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack hand = mock(ItemStack.class);
        Block sign = mock(Block.class);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(sign);
        when(event.getPlayer()).thenReturn(player);
        when(sign.getType()).thenReturn(Material.OAK_WALL_SIGN);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(hand);
        when(hand.getType()).thenReturn(Material.AIR);
        when(events.db.shopExistsFromLoc(null)).thenReturn(true);
        when(events.db.getShopFromLoc(null)).thenReturn(shop);

        // No server, storage or economy is available: validation must return before touching them.
        assertDoesNotThrow(() -> events.useShop(event));

        verify(event).setCancelled(true);
        verify(player).sendMessage("§cShop has an invalid quantity or price. Contact the shop owner.");
        verify(inventory).getItemInMainHand();
        verifyNoMoreInteractions(inventory);
    }

    // --- Shared fixture for the listener tests below ---

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private ShopEvents events;
    private World world;
    private Player player;
    private PlayerInventory inventory;
    private Location playerLocation;
    private Block block;
    private Location signLocation;
    private Sign sign;
    private MockedStatic<ShopEmbargo> embargo;
    private MockedStatic<DenarEconomy> denar;
    private Account pouch;
    private Account bank;
    private MoneyManager money;

    /**
     * Sound constants resolve through Paper's server registry when the interface is first loaded,
     * so load them once against a stand-in registry. Only the two sounds shops play are distinct.
     * Mocking Registry or Sound initialises it, so both are mocked lazily from inside that loading.
     */
    @BeforeAll
    static void loadSounds() {
        RegistryAccess access = mock(RegistryAccess.class, invocation -> StandInRegistry.registry());
        try (MockedStatic<RegistryAccess> registryAccess = mockStatic(RegistryAccess.class)) {
            registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
            assertNotSame(Sound.ENTITY_VILLAGER_NO, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        }
    }

    private static final class StandInRegistry {
        private static Registry<?> registry;
        private static Sound villagerNo;
        private static Sound orbPickup;
        private static Sound other;

        // Loading Registry asks for registries again, so this must be re-entrant.
        static Registry<?> registry() {
            if (registry == null) {
                registry = mock(Registry.class, invocation -> invocation.getMethod().getName().equals("getOrThrow")
                    ? sound(invocation.<NamespacedKey>getArgument(0).getKey())
                    : RETURNS_DEFAULTS.answer(invocation));
            }
            return registry;
        }

        static Sound sound(String key) {
            return switch (key) {
                case "entity.villager.no" -> villagerNo = villagerNo == null ? mock(Sound.class) : villagerNo;
                case "entity.experience_orb.pickup" -> orbPickup = orbPickup == null ? mock(Sound.class) : orbPickup;
                default -> other = other == null ? mock(Sound.class) : other;
            };
        }
    }

    @BeforeEach
    void setUpListener() {
        events = new ShopEvents();
        events.db = mock(Database.class);
        world = mock(World.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        playerLocation = mock(Location.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(CUSTOMER);
        when(player.getLocation()).thenReturn(playerLocation);
        holding(Material.REDSTONE);
        signLocation = new Location(world, 0, 64, 0);
        block = mock(Block.class);
        sign = mock(Sign.class);
        when(block.getType()).thenReturn(Material.OAK_WALL_SIGN);
        when(block.getLocation()).thenReturn(signLocation);
        when(block.getState()).thenReturn(sign);
        signLines("[Buy]");
        embargo = mockStatic(ShopEmbargo.class);
        denar = mockStatic(DenarEconomy.class);
        PlayerManager players = mock(PlayerManager.class);
        PlayerData customerData = mock(PlayerData.class);
        PlayerData ownerData = mock(PlayerData.class);
        pouch = mock(Account.class);
        bank = mock(Account.class);
        money = mock(MoneyManager.class);
        denar.when(DenarEconomy::getPlayerManager).thenReturn(players);
        denar.when(DenarEconomy::getMoneyManager).thenReturn(money);
        when(players.get(player)).thenReturn(customerData);
        when(players.get(OWNER)).thenReturn(ownerData);
        when(customerData.getPouch()).thenReturn(pouch);
        when(ownerData.getBank()).thenReturn(bank);
    }

    @AfterEach
    void closeStatics() {
        denar.close();
        embargo.close();
    }

    // --- Shop creation: clicks ---

    static Stream<Arguments> newSigns() {
        return Stream.of((Integer) null, 0).flatMap(stage -> Stream.of(
            Arguments.of(stage, "[Buy]", "buy"),
            Arguments.of(stage, "BUY", "buy"),
            Arguments.of(stage, "[sell]", "sell")));
    }

    @SuppressWarnings("deprecation")
    @ParameterizedTest
    @MethodSource("newSigns")
    void clickingASignWithRedstoneStartsCreation(Integer stage, String firstLine, String type) {
        startAt(stage);
        signLines(firstLine);
        PlayerInteractEvent event = click(block);

        events.createShopEvent(event);

        verify(event).setCancelled(true);
        assertEquals(1, events.shopCreating.get(player));
        ShopSign shop = events.currentShop.get(player);
        assertEquals(type, shop.getType());
        assertEquals(CUSTOMER.toString(), shop.getOwner());
        assertEquals(signLocation, shop.getSignLoc());
        verify(player).sendTitle("Shop Creation §e1/4", "§aRight Click on the storage chest with the redstone", 5, 80, 6);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 0)
    void signsWithoutAShopTypeAreRejected(Integer stage) {
        startAt(stage);
        signLines("[Trade]");
        PlayerInteractEvent event = click(block);

        events.createShopEvent(event);

        verify(player).sendMessage("§cShop Sign has invalid type");
        assertCreationUnchanged(stage, event);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 0)
    void creationIgnoresBlocksThatAreNotSigns(Integer stage) {
        startAt(stage);
        when(block.getType()).thenReturn(Material.CHEST);
        PlayerInteractEvent event = click(block);

        events.createShopEvent(event);

        assertCreationUnchanged(stage, event);
        verifyNoInteractions(events.db);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 0)
    void creationIgnoresSignsThatAreAlreadyShops(Integer stage) {
        startAt(stage);
        when(events.db.shopExistsFromLoc(signLocation)).thenReturn(true);
        PlayerInteractEvent event = click(block);

        events.createShopEvent(event);

        assertCreationUnchanged(stage, event);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 0)
    void creationNeedsRedstoneInHand(Integer stage) {
        startAt(stage);
        holding(Material.STICK);
        PlayerInteractEvent event = click(block);

        events.createShopEvent(event);

        assertCreationUnchanged(stage, event);
        verify(block, never()).getState();
    }

    @Test
    void creationIgnoresEverythingButRightClicks() {
        PlayerInteractEvent event = click(block);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);

        events.createShopEvent(event);

        assertCreationUnchanged(null, event);
        verifyNoInteractions(events.db);
    }

    @SuppressWarnings("deprecation")
    @ParameterizedTest
    @ValueSource(ints = {-32, 32})
    void chestWithinThirtyTwoBlocksBecomesTheStorage(int offset) {
        ShopSign shop = creatingShop();
        Block chest = chestAt(new Location(world, offset, 64, 0));
        PlayerInteractEvent event = click(chest);

        events.createShopEvent(event);

        verify(event, atLeastOnce()).setCancelled(true);
        assertEquals(3, events.shopCreating.get(player));
        assertSame(shop, events.currentShop.get(player));
        assertEquals(new Location(world, offset, 64, 0), shop.getStorageLoc());
        verify(player).sendTitle("Shop Creation §e2/4", "§aType the buy/sell amount in chat", 5, 80, 6);
    }

    @Test
    void chestFurtherThanThirtyTwoBlocksIsRejected() {
        ShopSign shop = creatingShop();
        PlayerInteractEvent event = click(chestAt(new Location(world, 32, 65, 0)));

        events.createShopEvent(event);

        verify(event).setCancelled(true);
        verify(player).sendMessage("§cChest is too far away");
        assertEquals(1, events.shopCreating.get(player));
        assertNull(shop.getStorageLoc());
    }

    @Test
    void storageNeedsRedstoneInHand() {
        ShopSign shop = creatingShop();
        holding(Material.STICK);
        PlayerInteractEvent event = click(chestAt(new Location(world, 1, 64, 0)));

        events.createShopEvent(event);

        verify(event, never()).setCancelled(anyBoolean());
        assertEquals(1, events.shopCreating.get(player));
        assertNull(shop.getStorageLoc());
    }

    @Test
    void storageMustBeAChest() {
        ShopSign shop = creatingShop();
        Block barrel = chestAt(new Location(world, 1, 64, 0));
        when(barrel.getType()).thenReturn(Material.BARREL);
        PlayerInteractEvent event = click(barrel);

        events.createShopEvent(event);

        verify(event, never()).setCancelled(anyBoolean());
        assertEquals(1, events.shopCreating.get(player));
        assertNull(shop.getStorageLoc());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void clicksAreIgnoredWhileWaitingForChatInput(int stage) {
        events.shopCreating.put(player, stage);
        PlayerInteractEvent event = click(chestAt(new Location(world, 1, 64, 0)));

        events.createShopEvent(event);

        verify(event, never()).setCancelled(anyBoolean());
        assertEquals(stage, events.shopCreating.get(player));
        verifyNoInteractions(events.db);
    }

    // --- Shop creation: chat ---

    @SuppressWarnings("deprecation")
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1})
    void chatIsLeftAloneOutsideTheChatSteps(Integer stage) {
        startAt(stage);
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, "12", Set.of());

        events.chatEvent(event);

        assertFalse(event.isCancelled());
        assertEquals(stage, events.shopCreating.get(player));
        verify(player, never()).sendMessage(anyString());
    }

    // --- Using shops: guards ---

    @Test
    void useIgnoresEverythingButRightClicks() {
        PlayerInteractEvent event = click(block);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);

        events.useShop(event);

        verify(event, never()).setCancelled(anyBoolean());
        verifyNoInteractions(events.db);
    }

    @Test
    void useIgnoresBlocksThatAreNotSigns() {
        when(block.getType()).thenReturn(Material.CHEST);
        PlayerInteractEvent event = click(block);

        events.useShop(event);

        verify(event, never()).setCancelled(anyBoolean());
        verifyNoInteractions(events.db);
    }

    @Test
    void useIgnoresSignsThatAreNotShops() {
        PlayerInteractEvent event = click(block);

        events.useShop(event);

        verify(event, never()).setCancelled(anyBoolean());
        verify(events.db, never()).getShopFromLoc(any());
    }

    @Test
    void redstoneClicksOnAShopDoNotTrade() {
        tradingShop("buy");
        PlayerInteractEvent event = click(block);
        holding(Material.REDSTONE);

        events.useShop(event);

        verify(event).setCancelled(true);
        verify(events.db, never()).getShopFromLoc(any());
    }

    @Test
    void embargoedCustomersCannotTrade() {
        ShopSign shop = tradingShop("buy");
        embargo.when(() -> ShopEmbargo.blocked(player, shop)).thenReturn(true);
        PlayerInteractEvent event = click(block);

        events.useShop(event);

        verify(event).setCancelled(true);
        assertRefused("§cYou cannot use this shop: your nation is under embargo.");
        verify(shop.getStorageLoc(), never()).getBlock();
    }

    @Test
    void shopsWithoutAStorageChestCannotTrade() {
        ShopSign shop = tradingShop("buy");
        when(shop.getStorageLoc().getBlock().getType()).thenReturn(Material.AIR);

        events.useShop(click(block));

        assertRefused("§cShop missing a storage chest");
    }

    @Test
    void shopsOfAnUnknownTypeDoNothing() {
        tradingShop("trade");
        PlayerInteractEvent event = click(block);

        events.useShop(event);

        verify(event).setCancelled(true);
        verify(player, never()).sendMessage(anyString());
        denar.verifyNoInteractions();
    }

    // --- Using shops: buying ---

    @Test
    void buyingTakesStockAndPaysTheOwner() {
        Inventory storage = storage(tradingShop("buy"));
        ItemStack[] stock = stock(storage, null, new Stack(Material.AIR, 0), new Stack(Material.DIRT, 8));
        List<ItemStack> bought = received(inventory);
        when(pouch.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        verify(pouch).change(-10.0);
        verify(money).addMoneyToAccount(OWNER.toString(), 10.0, true, true, Accounts.BANK);
        assertEquals(new Stack(Material.DIRT, 3), stock[2]);
        assertEquals(List.of(new Stack(Material.DIRT, 5)), bought);
        verify(player).playSound(playerLocation, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void buyingDrawsFromSeveralMatchingStacks() {
        Inventory storage = storage(tradingShop("buy"));
        ItemStack[] stock = stock(storage,
            new Stack(Material.DIRT, 3), new Stack(Material.STONE, 2), new Stack(Material.DIRT, 4));
        List<ItemStack> bought = received(inventory);
        when(pouch.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        assertNull(stock[0]);
        assertEquals(new Stack(Material.STONE, 2), stock[1]);
        assertEquals(new Stack(Material.DIRT, 2), stock[2]);
        assertEquals(List.of(new Stack(Material.DIRT, 3), new Stack(Material.DIRT, 2)), bought);
    }

    @Test
    void buyingAWholeStackEmptiesItsSlot() {
        Inventory storage = storage(tradingShop("buy"));
        ItemStack[] stock = stock(storage, new Stack(Material.DIRT, 5), new Stack(Material.DIRT, 5));
        List<ItemStack> bought = received(inventory);
        when(pouch.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        assertNull(stock[0]);
        assertEquals(new Stack(Material.DIRT, 5), stock[1]);
        assertEquals(List.of(new Stack(Material.DIRT, 5)), bought);
    }

    @Test
    void buyingNeedsEnoughStock() {
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 4));
        when(pouch.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        assertRefused("§cShop out of stock");
    }

    @Test
    void otherItemsInTheChestDoNotCountAsStock() {
        // Previously the dirt and stone were counted together: the buyer paid for five and got three dirt.
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 3), new Stack(Material.STONE, 2));
        List<ItemStack> bought = received(inventory);
        when(pouch.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        assertRefused("§cShop out of stock");
        assertTrue(bought.isEmpty());
    }

    @Test
    void emptyChestsAreOutOfStock() {
        stock(storage(tradingShop("buy")), null, new Stack(Material.AIR, 0));
        when(pouch.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        assertRefused("§cShop out of stock");
    }

    @Test
    void buyingNeedsInventorySpace() {
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 5));
        when(inventory.firstEmpty()).thenReturn(-1);
        when(pouch.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        assertRefused(ChatColor.RED + "Your inventory is full!");
    }

    @Test
    void buyingNeedsEnoughMoney() {
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 5));
        when(pouch.getBal()).thenReturn(9.99);

        events.useShop(click(block));

        assertRefused("§cCannot afford this!");
    }

    // --- Using shops: selling ---

    @Test
    void sellingTakesTheCustomersItemsAndPaysThem() {
        Inventory storage = storage(tradingShop("sell"));
        stock(storage, null, new Stack(Material.AIR, 0), new Stack(Material.DIRT, 1));
        List<ItemStack> stored = received(storage);
        ItemStack[] held = stock(inventory,
            null, new Stack(Material.AIR, 0), new Stack(Material.DIRT, 3), new Stack(Material.STONE, 1),
            new Stack(Material.DIRT, 4));
        when(bank.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        verify(money).changeBal(OWNER.toString(), -10.0, Accounts.BANK);
        verify(money).addMoney(player, 10.0, false, true);
        assertNull(held[2]);
        assertEquals(new Stack(Material.STONE, 1), held[3]);
        assertEquals(new Stack(Material.DIRT, 2), held[4]);
        assertEquals(List.of(new Stack(Material.DIRT, 3), new Stack(Material.DIRT, 2)), stored);
        verify(player).playSound(playerLocation, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void sellingNeedsStorageSpace() {
        Inventory storage = storage(tradingShop("sell"));
        when(storage.firstEmpty()).thenReturn(-1);
        PlayerInteractEvent event = click(block);

        events.useShop(event);

        verify(player).sendMessage(ChatColor.RED + "Shop Storage is full!");
        denar.verifyNoInteractions();
    }

    @Test
    void sellingNeedsInventorySpace() {
        storage(tradingShop("sell"));
        when(inventory.firstEmpty()).thenReturn(-1);

        events.useShop(click(block));

        verify(player).sendMessage(ChatColor.RED + "Your inventory is full!");
        denar.verifyNoInteractions();
    }

    @Test
    void sellingNeedsTheOwnerToAffordIt() {
        stock(storage(tradingShop("sell")), new Stack(Material.DIRT, 1));
        stock(inventory, new Stack(Material.DIRT, 5));
        when(bank.getBal()).thenReturn(9.99);

        events.useShop(click(block));

        assertRefused("§cThe shop owner lacks money!");
    }

    @Test
    void sellingNeedsAnItemInTheStorage() {
        stock(storage(tradingShop("sell")), null, new Stack(Material.AIR, 0));
        when(bank.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        assertRefused("§cShop has no item set for selling");
    }

    @Test
    void sellingNeedsEnoughMatchingItems() {
        stock(storage(tradingShop("sell")), new Stack(Material.DIRT, 1));
        stock(inventory, new Stack(Material.DIRT, 4), new Stack(Material.STONE, 5));
        when(bank.getBal()).thenReturn(10.0);

        events.useShop(click(block));

        assertRefused("§cYou dont have enough to sell");
    }

    // --- Breaking shop signs ---

    @Test
    void breakingAShopSignDeletesItOnceTheSignIsGone() {
        when(events.db.shopExistsFromLoc(signLocation)).thenReturn(true);
        when(block.getType()).thenReturn(Material.OAK_WALL_SIGN, Material.OAK_WALL_SIGN, Material.AIR);
        when(world.getBlockAt(signLocation)).thenReturn(block);

        Runnable check = scheduledBreakCheck(new BlockBreakEvent(block, player));

        check.run();
        verify(events.db).deleteFile(signLocation);
    }

    @Test
    void cancelledShopSignBreaksKeepTheShop() {
        when(events.db.shopExistsFromLoc(signLocation)).thenReturn(true);
        when(world.getBlockAt(signLocation)).thenReturn(block);

        Runnable check = scheduledBreakCheck(new BlockBreakEvent(block, player));

        check.run();
        verify(events.db, never()).deleteFile(any());
    }

    @Test
    void breakingOtherBlocksLeavesShopsAlone() {
        when(block.getType()).thenReturn(Material.CHEST);
        events.breakDoor(new BlockBreakEvent(block, player));
        verifyNoInteractions(events.db);
    }

    @Test
    void breakingSignsThatAreNotShopsSchedulesNothing() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            events.breakDoor(new BlockBreakEvent(block, player));
            bukkit.verifyNoInteractions();
        }
        verify(events.db, never()).deleteFile(any());
    }

    // --- Inventory helpers ---

    @Test
    void stockCountsOnlyMatchingStacks() {
        stock(inventory, null, new Stack(Material.AIR, 0), new Stack(Material.DIRT, 3),
            new Stack(Material.STONE, 9), new Stack(Material.DIRT, 2));
        assertTrue(events.hasEnoughItems(inventory, new Stack(Material.DIRT, 1), 5));
        assertFalse(events.hasEnoughItems(inventory, new Stack(Material.DIRT, 1), 6));
    }

    @Test
    void exchangingNothingMovesNothing() {
        Inventory storage = mock(Inventory.class);
        ItemStack[] stock = stock(storage, new Stack(Material.DIRT, 3));
        List<ItemStack> moved = received(inventory);

        events.exchangeItems(storage, inventory, new Stack(Material.DIRT, 1), 0);

        assertEquals(new Stack(Material.DIRT, 3), stock[0]);
        assertTrue(moved.isEmpty());
    }

    @Test
    void exchangingStopsWhenTheSenderRunsOut() {
        Inventory storage = mock(Inventory.class);
        ItemStack[] stock = stock(storage, new Stack(Material.DIRT, 3));
        List<ItemStack> moved = received(inventory);

        events.exchangeItems(storage, inventory, new Stack(Material.DIRT, 1), 5);

        assertNull(stock[0]);
        assertEquals(List.of(new Stack(Material.DIRT, 3)), moved);
    }

    // --- Fixture helpers ---

    private void startAt(Integer stage) {
        if (stage != null) {
            events.shopCreating.put(player, stage);
        }
    }

    private void assertCreationUnchanged(Integer stage, PlayerInteractEvent event) {
        verify(event, never()).setCancelled(anyBoolean());
        assertEquals(stage, events.shopCreating.get(player));
        assertFalse(events.currentShop.containsKey(player));
    }

    private void holding(Material type) {
        when(inventory.getItemInMainHand()).thenReturn(new Stack(type, 1));
    }

    @SuppressWarnings("deprecation")
    private void signLines(String firstLine) {
        when(sign.getLines()).thenReturn(new String[] {firstLine, "", "", ""});
    }

    private PlayerInteractEvent click(Block clicked) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(clicked);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private ShopSign creatingShop() {
        ShopSign shop = new ShopSign();
        shop.setSignLoc(signLocation);
        events.shopCreating.put(player, 1);
        events.currentShop.put(player, shop);
        return shop;
    }

    private Block chestAt(Location location) {
        Block chest = mock(Block.class);
        when(chest.getType()).thenReturn(Material.CHEST);
        when(chest.getLocation()).thenReturn(location);
        return chest;
    }

    /** A valid five-for-ten shop at the clicked sign, with an empty chest as its storage. */
    private ShopSign tradingShop(String type) {
        Location storageLocation = mock(Location.class);
        Block storageBlock = mock(Block.class);
        Chest chest = mock(Chest.class);
        Inventory storage = mock(Inventory.class);
        when(storageLocation.getBlock()).thenReturn(storageBlock);
        when(storageBlock.getType()).thenReturn(Material.CHEST);
        when(storageBlock.getState()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(storage);
        stock(storage);
        ShopSign shop = new ShopSign();
        shop.setSignLoc(signLocation);
        shop.setStorageLoc(storageLocation);
        shop.setType(type);
        shop.setBarterAmount(5);
        shop.setPrice(10);
        shop.setOwner(OWNER.toString());
        when(events.db.shopExistsFromLoc(signLocation)).thenReturn(true);
        when(events.db.getShopFromLoc(signLocation)).thenReturn(shop);
        holding(Material.AIR);
        return shop;
    }

    private static Inventory storage(ShopSign shop) {
        return ((Chest) shop.getStorageLoc().getBlock().getState()).getInventory();
    }

    /** Backs the inventory with the returned array, which slot updates write through to. */
    private static ItemStack[] stock(Inventory inventory, ItemStack... contents) {
        when(inventory.getStorageContents()).thenReturn(contents);
        doAnswer(invocation -> {
            contents[invocation.<Integer>getArgument(0)] = invocation.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        return contents;
    }

    private static List<ItemStack> received(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(invocation -> {
            items.addAll(List.of((ItemStack[]) invocation.getRawArguments()[0]));
            return new HashMap<Integer, ItemStack>();
        });
        return items;
    }

    private void assertRefused(String message) {
        verify(player).sendMessage(message);
        verify(player).playSound(playerLocation, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        verify(pouch, never()).change(anyDouble());
        verifyNoInteractions(money);
    }

    private Runnable scheduledBreakCheck(BlockBreakEvent event) {
        ShopMain previousPlugin = ShopMain.plugin;
        ShopMain plugin = mock(ShopMain.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<Runnable> tasks = new ArrayList<>();
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(5L))).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });
        ShopMain.plugin = plugin;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            events.breakDoor(event);
        } finally {
            ShopMain.plugin = previousPlugin;
        }
        assertEquals(1, tasks.size());
        verify(events.db, never()).deleteFile(any());
        return tasks.get(0);
    }

    /**
     * Paper's ItemStack forwards to a server-side stack. This stands in for that stack, comparing
     * type and amount the way Bukkit's equality does for plain items.
     */
    private static final class Stack extends ItemStack {
        private final Material type;
        private int amount;

        Stack(Material type, int amount) {
            this.type = type;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public ItemStack clone() {
            return new Stack(type, amount);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ItemStack stack && stack.getType() == type && stack.getAmount() == amount;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, amount);
        }

        @Override
        public String toString() {
            return type + " x" + amount;
        }
    }
}
