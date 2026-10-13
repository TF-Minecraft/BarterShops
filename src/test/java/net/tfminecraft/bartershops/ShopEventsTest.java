package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.bukkit.ChatColor;
import org.bukkit.ExplosionResult;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockType;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;

import io.papermc.paper.registry.RegistryAccess;
import net.tfminecraft.bartershops.sf.ShopEmbargo;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.io.TempDir;


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
        shop.setOwnerAccount(CUSTOMER.toString());
        shop.setOwnerCharacter(CUSTOMER_CHARACTER.toString());
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
        verify(player).sendMessage("§cThe terms on this shop sign make no sense. Speak to the shop owner.");
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
    private MockedStatic<Denars> denar;
    private MockedStatic<Characters> characters;
    @TempDir Path tradeDirectory;
    private static final UUID OWNER_CHARACTER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CUSTOMER_CHARACTER = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final CharacterRef OWNER_REF = new CharacterRef(OWNER, OWNER_CHARACTER);
    private static final CharacterRef CUSTOMER_REF = new CharacterRef(CUSTOMER, CUSTOMER_CHARACTER);

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
        private static BlockType sand;

        // Loading Registry asks for registries again, so this must be re-entrant.
        static Registry<?> registry() {
            if (registry == null) {
                registry = mock(Registry.class, invocation -> switch (invocation.getMethod().getName()) {
                    case "getOrThrow" -> sound(invocation.<NamespacedKey>getArgument(0).getKey());
                    case "get" -> invocation.getArgument(0) instanceof NamespacedKey key ? blockType(key.getKey()) : null;
                    default -> RETURNS_DEFAULTS.answer(invocation);
                });
            }
            return registry;
        }

        // Material.hasGravity asks the block registry; sand is the only falling block the tests need.
        // A plain proxy, because a Mockito mock would load BlockType's constants from this registry.
        static BlockType blockType(String key) {
            if (!key.equals("sand")) return null;
            if (sand == null) {
                sand = (BlockType) Proxy.newProxyInstance(BlockType.class.getClassLoader(), new Class<?>[] {BlockType.class},
                    (proxy, method, args) -> method.getName().equals("hasGravity") ? Boolean.TRUE : null);
            }
            return sand;
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
        stock(inventory, new ItemStack[36]);
        signLocation = new Location(world, 0, 64, 0);
        block = mock(Block.class);
        sign = mock(Sign.class);
        when(block.getType()).thenReturn(Material.OAK_WALL_SIGN);
        when(block.getLocation()).thenReturn(signLocation);
        when(block.getState()).thenReturn(sign);
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(block.getRelative(any(BlockFace.class))).thenReturn(air);
        signLines("[Buy]");
        embargo = mockStatic(ShopEmbargo.class);
        embargo.when(ShopEmbargo::ready).thenReturn(true);
        characters = mockStatic(Characters.class);
        characters.when(Characters::available).thenReturn(true);
        characters.when(Characters::legacyHeirsReady).thenReturn(true);
        characters.when(() -> Characters.currentSession(any(Player.class))).thenReturn(Optional.of(new CharacterSession(CUSTOMER_REF, 1)));
        ownerStatus(CharacterStatus.ALIVE);
        denar = mockStatic(Denars.class);
        denar.when(() -> Denars.pouch(any())).thenAnswer(call -> "char:" + call.<CharacterRef>getArgument(0).characterId() + ":pouch");
        denar.when(() -> Denars.bank(any())).thenAnswer(call -> "char:" + call.<CharacterRef>getArgument(0).characterId() + ":bank");
        tradeResult(Result.APPLIED);
        denar.when(() -> Denars.post(anyString(), anyString(), anyList())).thenReturn(Result.APPLIED);
        events.trades = new ShopTrades(tradeDirectory, Logger.getAnonymousLogger());
    }

    @AfterEach
    void closeStatics() {
        characters.close();
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
        assertEquals(CUSTOMER.toString(), shop.getOwnerAccount());
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
    void chestThePlayerMayNotOpenIsRejected() {
        ShopSign shop = creatingShop();
        PlayerInteractEvent event = click(chestAt(new Location(world, 1, 64, 0)));
        // A lock or claim plugin has already refused to open the chest for this player.
        when(event.useInteractedBlock()).thenReturn(Event.Result.DENY);

        events.createShopEvent(event);

        verify(event, never()).setCancelled(anyBoolean());
        verify(player).sendMessage("§cYou can only use a chest you are allowed to open");
        assertEquals(1, events.shopCreating.get(player));
        assertNull(shop.getStorageLoc());
    }

    @Test
    void createShopRunsAfterLockAndClaimPlugins() throws NoSuchMethodException {
        EventHandler handler = ShopEvents.class.getMethod("createShopEvent", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, handler.priority());
        assertFalse(handler.ignoreCancelled());
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

        events.useShop(click(block));

        verifyTrade("buy");
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

        events.useShop(click(block));

        assertNull(stock[0]);
        assertEquals(new Stack(Material.DIRT, 5), stock[1]);
        assertEquals(List.of(new Stack(Material.DIRT, 5)), bought);
    }

    @Test
    void buyingNeedsEnoughStock() {
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 4));

        events.useShop(click(block));

        assertRefused("§cShop out of stock");
    }

    @Test
    void otherItemsInTheChestDoNotCountAsStock() {
        // Previously the dirt and stone were counted together: the buyer paid for five and got three dirt.
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 3), new Stack(Material.STONE, 2));
        List<ItemStack> bought = received(inventory);

        events.useShop(click(block));

        assertRefused("§cShop out of stock");
        assertTrue(bought.isEmpty());
    }

    @Test
    void emptyChestsAreOutOfStock() {
        stock(storage(tradingShop("buy")), null, new Stack(Material.AIR, 0));

        events.useShop(click(block));

        assertRefused("§cShop out of stock");
    }

    @Test
    void buyingNeedsRoomForTheWholeOrder() {
        // Previously one free slot was enough, and whatever did not fit was lost after payment.
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 5));
        stock(inventory, new Stack(Material.DIRT, 62), new Stack(Material.STONE, 64));

        events.useShop(click(block));

        assertRefused(ChatColor.RED + "Your inventory is full!");
    }

    @Test
    void buyingCanTopUpPartStacks() {
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 5));
        stock(inventory, new Stack(Material.DIRT, 59), new Stack(Material.STONE, 64));
        List<ItemStack> bought = received(inventory);

        events.useShop(click(block));

        verifyTrade("buy");
        assertEquals(List.of(new Stack(Material.DIRT, 5)), bought);
    }

    @Test
    void buyingNeedsEnoughMoney() {
        stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 5));
        tradeResult(Result.REFUSED);

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

        events.useShop(click(block));

        verifyTrade("sell");
        assertNull(held[2]);
        assertEquals(new Stack(Material.STONE, 1), held[3]);
        assertEquals(new Stack(Material.DIRT, 2), held[4]);
        assertEquals(List.of(new Stack(Material.DIRT, 3), new Stack(Material.DIRT, 2)), stored);
        verify(player).playSound(playerLocation, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void sellingNeedsRoomInTheChestForTheWholeOrder() {
        stock(storage(tradingShop("sell")), new Stack(Material.DIRT, 62), new Stack(Material.STONE, 64));
        stock(inventory, new Stack(Material.DIRT, 5));

        events.useShop(click(block));

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
        tradeResult(Result.REFUSED);

        events.useShop(click(block));

        assertRefused("§cThe shop owner lacks money!");
    }

    @Test
    void sellingNeedsAnItemInTheStorage() {
        stock(storage(tradingShop("sell")), null, new Stack(Material.AIR, 0));

        events.useShop(click(block));

        assertRefused("§cShop has no item set for selling");
    }

    @Test
    void sellingNeedsEnoughMatchingItems() {
        stock(storage(tradingShop("sell")), new Stack(Material.DIRT, 1));
        stock(inventory, new Stack(Material.DIRT, 4), new Stack(Material.STONE, 5));

        events.useShop(click(block));

        assertRefused("§cYou dont have enough to sell");
    }

    // --- Breaking shop signs ---

    @Test
    void breakingAShopSignDeletesItsRecord() {
        shopOwnedBy(signLocation, CUSTOMER);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        fire(event);

        assertFalse(event.isCancelled());
        verify(events.db).deleteFile(signLocation);
        assertTrue(events.approvedRemovals.isEmpty());
    }

    @Test
    void shopSignBreaksCancelledByAnotherPluginKeepTheShop() {
        shopOwnedBy(signLocation, CUSTOMER);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        fire(event, () -> event.setCancelled(true));

        verify(events.db, never()).deleteFile(any());
        assertTrue(events.approvedRemovals.isEmpty());
    }

    @Test
    void otherPlayersCannotBreakAShopSign() {
        shopOwnedBy(signLocation, OWNER);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        fire(event);

        assertTrue(event.isCancelled());
        verify(player).sendMessage("§cOnly the shop owner can break this shop.");
        verify(events.db, never()).deleteFile(any());
    }

    @Test
    void staffCanBreakOtherPlayersShops() {
        shopOwnedBy(signLocation, OWNER);
        when(player.hasPermission(ShopEvents.ADMIN_PERMISSION)).thenReturn(true);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        fire(event);

        assertFalse(event.isCancelled());
        verify(events.db).deleteFile(signLocation);
    }

    @Test
    void otherPlayersCannotBreakTheBlockAShopSignHangsOn() {
        Block wall = signOnWall();
        shopOwnedBy(signLocation, OWNER);
        BlockBreakEvent event = new BlockBreakEvent(wall, player);

        fire(event);

        assertTrue(event.isCancelled());
        verify(player).sendMessage("§cOnly the shop owner can break this shop.");
        assertTrue(events.approvedRemovals.isEmpty());
    }

    @Test
    void ownersBreakingTheBlockBehindTheirSignDeleteTheShopWhenTheSignFalls() {
        Block wall = signOnWall();
        shopOwnedBy(signLocation, CUSTOMER);
        BlockBreakEvent event = new BlockBreakEvent(wall, player);

        fire(event);

        assertFalse(event.isCancelled());
        verify(events.db, never()).deleteFile(any());
        BlockDestroyEvent destroy = destroyed(block);
        fire(destroy);
        assertFalse(destroy.isCancelled());
        verify(events.db).deleteFile(signLocation);
        assertTrue(events.approvedRemovals.isEmpty());
    }

    @Test
    void signsNotHangingOnTheBrokenBlockAreIgnored() {
        Block wall = signOnWall();
        WallSign data = mock(WallSign.class);
        when(data.getFacing()).thenReturn(BlockFace.EAST);
        when(block.getBlockData()).thenReturn(data);
        BlockBreakEvent event = new BlockBreakEvent(wall, player);

        fire(event);

        assertFalse(event.isCancelled());
        verifyNoInteractions(events.db);
    }

    @Test
    void eachSignKindNamesTheBlocksHoldingItUp() {
        WallSign wall = mock(WallSign.class);
        when(wall.getFacing()).thenReturn(BlockFace.NORTH);

        assertEquals(Set.of(BlockFace.SOUTH), ShopEvents.supports(wall));
        assertEquals(Set.of(), ShopEvents.supports(mock(WallHangingSign.class)));
        assertEquals(Set.of(BlockFace.UP), ShopEvents.supports(mock(HangingSign.class)));
        assertEquals(Set.of(BlockFace.DOWN), ShopEvents.supports(mock(org.bukkit.block.data.type.Sign.class)));
        assertEquals(Set.of(), ShopEvents.supports(mock(BlockData.class)));
    }

    @Test
    void otherPlayersCannotDropAShopSignByBreakingWhatItsChainHangsFrom() {
        Block top = blockOf(Material.OAK_HANGING_SIGN);
        Block ceiling = blockOf(Material.STONE);
        hangsUnder(top, ceiling);
        hangsUnder(block, top);
        shopOwnedBy(signLocation, OWNER);
        BlockBreakEvent event = new BlockBreakEvent(ceiling, player);

        fire(event);

        assertTrue(event.isCancelled());
        verify(player).sendMessage("§cOnly the shop owner can break this shop.");
        verify(events.db, never()).deleteFile(any());
    }

    @Test
    void ownersBreakingTheTopOfAChainDeleteEveryShopInItAsItFalls() {
        Location topLocation = new Location(world, 0, 65, 0);
        Block top = blockOf(Material.OAK_HANGING_SIGN);
        when(top.getLocation()).thenReturn(topLocation);
        when(block.getType()).thenReturn(Material.OAK_HANGING_SIGN);
        Block ceiling = blockOf(Material.STONE);
        hangsUnder(top, ceiling);
        hangsUnder(block, top);
        shopOwnedBy(topLocation, CUSTOMER);
        shopOwnedBy(signLocation, CUSTOMER);
        BlockBreakEvent event = new BlockBreakEvent(ceiling, player);

        fire(event);

        assertFalse(event.isCancelled());
        assertEquals(Set.of(topLocation, signLocation), events.approvedRemovals.keySet());
        fire(destroyed(top));
        fire(destroyed(block));
        verify(events.db).deleteFile(topLocation);
        verify(events.db).deleteFile(signLocation);
    }

    @Test
    void otherPlayersCannotDropAShopSignByUndercuttingTheSandItHangsOn() {
        Block ground = blockOf(Material.STONE);
        Block sand = blockOf(Material.SAND);
        when(ground.getRelative(BlockFace.UP)).thenReturn(sand);
        when(sand.getRelative(BlockFace.NORTH)).thenReturn(block);
        signFacing(BlockFace.NORTH);
        shopOwnedBy(signLocation, OWNER);
        BlockBreakEvent event = new BlockBreakEvent(ground, player);

        fire(event);

        assertTrue(event.isCancelled());
        verify(player).sendMessage("§cOnly the shop owner can break this shop.");
    }

    @Test
    void ownersUndercuttingATallSandColumnDeleteTheShopOnlyWhenTheSignFalls() {
        long[] now = {1_000L};
        events.clock = () -> now[0];
        Block ground = blockOf(Material.STONE);
        Block below = ground;
        for (int i = 0; i < 8; i++) {
            Block sand = blockOf(Material.SAND);
            when(below.getRelative(BlockFace.UP)).thenReturn(sand);
            below = sand;
        }
        when(below.getRelative(BlockFace.NORTH)).thenReturn(block);
        signFacing(BlockFace.NORTH);
        shopOwnedBy(signLocation, CUSTOMER);

        fire(new BlockBreakEvent(ground, player));

        verify(events.db, never()).deleteFile(any());
        // Eight blocks of sand take about a second to fall one after another.
        now[0] += 1_000L;
        BlockDestroyEvent destroy = destroyed(block);
        fire(destroy);
        assertFalse(destroy.isCancelled());
        verify(events.db).deleteFile(signLocation);
    }

    @Test
    void otherPlayersCannotDestroyAShopSignOnACactusByBreakingTheSandUnderIt() {
        Block sand = blockOf(Material.SAND);
        Block cactus = blockOf(Material.CACTUS);
        when(sand.getRelative(BlockFace.UP)).thenReturn(cactus);
        when(cactus.getRelative(BlockFace.UP)).thenReturn(block);
        when(block.getType()).thenReturn(Material.OAK_SIGN);
        when(block.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.Sign.class));
        shopOwnedBy(signLocation, OWNER);
        BlockBreakEvent event = new BlockBreakEvent(sand, player);

        fire(event);

        // The cactus gives way later, so the break itself goes ahead and the sign stays up.
        assertFalse(event.isCancelled());
        BlockDestroyEvent destroy = destroyed(block);
        fire(destroy);
        assertTrue(destroy.isCancelled());
        verify(events.db, never()).deleteFile(any());
    }

    @Test
    void approvedSignsKeepTheirShopWhenAnotherPluginStopsThemFalling() {
        Block wall = signOnWall();
        shopOwnedBy(signLocation, CUSTOMER);
        fire(new BlockBreakEvent(wall, player));

        BlockDestroyEvent destroy = destroyed(block);
        fire(destroy, () -> destroy.setCancelled(true));

        verify(events.db, never()).deleteFile(any());
    }

    @Test
    void breakingAnApprovedSignDirectlyLeavesNoApprovalForTheNextShop() {
        Block wall = signOnWall();
        shopOwnedBy(signLocation, CUSTOMER);
        fire(new BlockBreakEvent(wall, player));
        BlockDestroyEvent stopped = destroyed(block);
        fire(stopped, () -> stopped.setCancelled(true));

        fire(new BlockBreakEvent(block, player));

        assertTrue(events.approvedRemovals.isEmpty());
        verify(events.db).deleteFile(signLocation);
        // Someone else builds a shop on the same spot, and physics then reaches it.
        shopOwnedBy(signLocation, OWNER);
        BlockDestroyEvent destroy = destroyed(block);
        fire(destroy);
        assertTrue(destroy.isCancelled());
        verify(events.db).deleteFile(signLocation);
    }

    @Test
    void approvalsRunOut() {
        long[] now = {0L};
        events.clock = () -> now[0];
        Block wall = signOnWall();
        shopOwnedBy(signLocation, CUSTOMER);
        fire(new BlockBreakEvent(wall, player));
        assertEquals(Set.of(signLocation), events.approvedRemovals.keySet());

        now[0] += ShopEvents.APPROVAL_MILLIS;
        BlockDestroyEvent destroy = destroyed(block);
        fire(destroy);

        assertTrue(destroy.isCancelled());
        assertTrue(events.approvedRemovals.isEmpty());
        verify(events.db, never()).deleteFile(any());
    }

    @Test
    void physicsStillDestroysOtherBlocksAndSigns() {
        Block stone = blockOf(Material.STONE);
        BlockDestroyEvent stoneDestroy = destroyed(stone);
        BlockDestroyEvent signDestroy = destroyed(block);

        fire(stoneDestroy);
        fire(signDestroy);

        assertFalse(stoneDestroy.isCancelled());
        assertFalse(signDestroy.isCancelled());
        verify(events.db).shopExistsFromLoc(signLocation);
        verifyNoMoreInteractions(events.db);
    }

    @Test
    void explosionsLeaveShopSignsStanding() {
        Block stone = blockOf(Material.STONE);
        Block plainSign = blockOf(Material.OAK_SIGN);
        when(plainSign.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        shopOwnedBy(signLocation, OWNER);
        EntityExplodeEvent creeper = new EntityExplodeEvent(mock(Entity.class), signLocation,
            new ArrayList<>(List.of(stone, block, plainSign)), 1f, ExplosionResult.DESTROY);
        BlockExplodeEvent bed = new BlockExplodeEvent(stone, mock(BlockState.class),
            new ArrayList<>(List.of(stone, block, plainSign)), 1f, ExplosionResult.DESTROY);

        fire(creeper);
        fire(bed);

        assertEquals(List.of(stone, plainSign), creeper.blockList());
        assertEquals(List.of(stone, plainSign), bed.blockList());
    }

    @ParameterizedTest
    @EnumSource(value = BlockFace.class, names = {"EAST", "WEST"})
    void anyoneCanBreakTheBlocksBesideAWallHangingShopSign(BlockFace side) {
        wallHangingSignFacingNorth();
        Block post = blockOf(Material.STONE);
        when(post.getRelative(side.getOppositeFace())).thenReturn(block);
        shopOwnedBy(signLocation, OWNER);
        BlockBreakEvent event = new BlockBreakEvent(post, player);

        fire(event);

        assertFalse(event.isCancelled());
        verify(events.db, never()).shopExistsFromLoc(any());
    }

    @Test
    void otherPlayersCannotBreakAWallHangingShopSign() {
        wallHangingSignFacingNorth();
        shopOwnedBy(signLocation, OWNER);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        fire(event);

        assertTrue(event.isCancelled());
    }

    private void shopOwnedBy(Location location, UUID owner) {
        when(events.db.shopExistsFromLoc(location)).thenReturn(true);
        when(events.db.getOwnerCharacterFromLoc(location)).thenReturn((owner.equals(CUSTOMER) ? CUSTOMER_CHARACTER : OWNER_CHARACTER).toString());
    }

    private static BlockDestroyEvent destroyed(Block b) {
        return new BlockDestroyEvent(b, null, null, 0, true);
    }

    private void wallHangingSignFacingNorth() {
        WallHangingSign data = mock(WallHangingSign.class);
        when(data.getFacing()).thenReturn(BlockFace.NORTH);
        when(block.getType()).thenReturn(Material.OAK_WALL_HANGING_SIGN);
        when(block.getBlockData()).thenReturn(data);
    }

    // A block of the given type with air on every side.
    private static Block blockOf(Material type) {
        Block b = mock(Block.class);
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(b.getType()).thenReturn(type);
        when(b.getRelative(any(BlockFace.class))).thenReturn(air);
        return b;
    }

    // Hangs a ceiling hanging sign from the block above it.
    private static void hangsUnder(Block sign, Block above) {
        when(sign.getBlockData()).thenReturn(mock(HangingSign.class));
        when(above.getRelative(BlockFace.DOWN)).thenReturn(sign);
    }

    private void signFacing(BlockFace facing) {
        WallSign data = mock(WallSign.class);
        when(data.getFacing()).thenReturn(facing);
        when(block.getBlockData()).thenReturn(data);
    }

    // A stone block with the shop sign on its north face, facing north.
    private Block signOnWall() {
        Block wall = blockOf(Material.STONE);
        when(wall.getRelative(BlockFace.NORTH)).thenReturn(block);
        signFacing(BlockFace.NORTH);
        return wall;
    }

    @Test
    void breakingOtherBlocksLeavesShopsAlone() {
        when(block.getType()).thenReturn(Material.CHEST);
        fire(new BlockBreakEvent(block, player));
        verifyNoInteractions(events.db);
    }

    @Test
    void breakingSignsThatAreNotShopsChangesNothing() {
        fire(new BlockBreakEvent(block, player));
        verify(events.db, never()).deleteFile(any());
        assertTrue(events.approvedRemovals.isEmpty());
    }

    /** Runs the listener's handlers for the event the way Bukkit does. */
    private void fire(Event event) {
        fire(event, () -> {});
    }

    /**
     * Runs the listener's handlers for the event in priority order, skipping ignoreCancelled handlers
     * once it is cancelled. {@code otherPlugin} stands for a plugin listening at HIGH priority.
     */
    private void fire(Event event, Runnable otherPlugin) {
        List<Method> handlers = Stream.of(ShopEvents.class.getMethods())
            .filter(m -> m.isAnnotationPresent(EventHandler.class) && m.getParameterTypes()[0] == event.getClass())
            .sorted(Comparator.comparing((Method m) -> m.getAnnotation(EventHandler.class).priority()))
            .toList();
        assertFalse(handlers.isEmpty());
        boolean otherPluginRan = false;
        for (Method handler : handlers) {
            EventHandler annotation = handler.getAnnotation(EventHandler.class);
            if (!otherPluginRan && annotation.priority().compareTo(EventPriority.HIGH) > 0) {
                otherPlugin.run();
                otherPluginRan = true;
            }
            if (annotation.ignoreCancelled() && ((Cancellable) event).isCancelled()) continue;
            try {
                handler.invoke(events, event);
            } catch (InvocationTargetException ex) {
                throw new AssertionError(ex.getCause());
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        }
        if (!otherPluginRan) otherPlugin.run();
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
    void roomCountsEmptySlotsAndSpaceInMatchingStacks() {
        stock(inventory, null, new Stack(Material.AIR, 0), new Stack(Material.DIRT, 60),
            new Stack(Material.STONE, 1), new Stack(Material.DIRT, 70));
        ItemStack dirt = new Stack(Material.DIRT, 1);
        assertTrue(events.hasRoomFor(inventory, dirt, 132));
        assertFalse(events.hasRoomFor(inventory, dirt, 133));
        assertEquals(1, dirt.getAmount());
    }

    @Test
    void roomRespectsTheInventoryStackLimit() {
        stock(inventory, null, null);
        when(inventory.getMaxStackSize()).thenReturn(16);
        assertTrue(events.hasRoomFor(inventory, new Stack(Material.DIRT, 1), 32));
        assertFalse(events.hasRoomFor(inventory, new Stack(Material.DIRT, 1), 33));
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

    @Test
    void anotherCharacterOnTheSameAccountCannotBreakTheShop() {
        shopOwnedBy(signLocation, CUSTOMER);
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(
                new CharacterSession(new CharacterRef(CUSTOMER, UUID.randomUUID()), 2)));
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        fire(event);
        assertTrue(event.isCancelled());
        verify(events.db, never()).deleteFile(any());
    }

    @ParameterizedTest
    @CsvSource({"buy,DEAD", "sell,DEAD", "buy,MISSING", "sell,MISSING"})
    void deadOwnersShopIsPausedInBothDirections(String type, CharacterStatus status) {
        ShopSign shop = tradingShop(type);
        ownerStatus(status);

        events.useShop(click(block));

        assertRefused("§cThis shop's owner has died.");
        denar.verifyNoInteractions();
        embargo.verify(() -> ShopEmbargo.blocked(any(), any()), never());
        verify(shop.getStorageLoc(), never()).getBlock();
    }

    @Test
    void shopWhoseOwnerCannotBeReadStaysClosed() {
        tradingShop("buy");
        characters.when(() -> Characters.info(OWNER_CHARACTER)).thenReturn(Optional.empty());

        events.useShop(click(block));

        assertRefused("§cThis shop is closed until its owner and your active character are available.");
        denar.verifyNoInteractions();
    }

    @Test
    void revivedOwnersShopTradesAgain() {
        Inventory storage = storage(tradingShop("buy"));
        stock(storage, new Stack(Material.DIRT, 8));
        received(inventory);
        ownerStatus(CharacterStatus.DEAD);
        events.useShop(click(block));
        denar.verifyNoInteractions();

        ownerStatus(CharacterStatus.ALIVE);
        events.useShop(click(block));

        verifyTrade("buy");
    }

    private void ownerStatus(CharacterStatus status) {
        characters.when(() -> Characters.info(any(UUID.class))).thenAnswer(call -> Optional.of(new CharacterInfo(
                new CharacterRef(OWNER, call.getArgument(0)), "Owner", status, null, Set.of(), 0, null)));
    }

    @Test
    void unmigratedShopCannotTradeOrBeBrokenExceptByStaff() {
        ShopSign shop = tradingShop("buy");
        shop.setOwnerCharacter(null);
        events.useShop(click(block));
        denar.verifyNoInteractions();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        fire(event);
        assertTrue(event.isCancelled());
        when(player.hasPermission(ShopEvents.ADMIN_PERMISSION)).thenReturn(true);
        BlockBreakEvent staff = new BlockBreakEvent(block, player);
        fire(staff);
        assertFalse(staff.isCancelled());
    }

    @Test
    void creationRequiresAnActiveCharacter() {
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
        events.createShopEvent(click(block));
        assertTrue(events.currentShop.isEmpty());
        assertTrue(events.shopCreating.isEmpty());
    }

    @Test
    void switchingCharactersCancelsTheStorageCreationStep() {
        creatingShop();
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(
                new CharacterSession(new CharacterRef(CUSTOMER, UUID.randomUUID()), 2)));
        events.createShopEvent(click(chestAt(new Location(world, 1, 64, 0))));
        assertTrue(events.currentShop.isEmpty());
        assertTrue(events.shopCreating.isEmpty());
        verify(events.db, never()).saveShop(any());
    }

    @Test
    void unavailablePaymentLeavesItemsUntilTheSameIntentApplies() throws Exception {
        ItemStack[] stock = stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 10));
        List<ItemStack> bought = received(inventory);
        tradeResult(Result.UNAVAILABLE);
        events.useShop(click(block));
        assertTrue(bought.isEmpty());
        assertEquals(10, stock[0].getAmount());
        Path file = tradeDirectory.resolve("00000000-0000-0000-0000-000000000003.json");
        String before = Files.readString(file);
        tradeResult(Result.APPLIED);
        events.useShop(click(block));
        assertEquals(5, stock[0].getAmount());
        assertEquals(List.of(new Stack(Material.DIRT, 5)), bought);
        assertFalse(Files.exists(file));
        assertTrue(before.contains("prepared"));
    }

    @Test
    void changesMadeByPaymentEventsFreezeDeliveryAndCannotReplayIt() throws Exception {
        ItemStack[] stock = stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 10));
        List<ItemStack> bought = received(inventory);
        denar.when(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()))
                .thenAnswer(call -> { stock[0].setAmount(9); return Result.APPLIED; });
        events.useShop(click(block));
        events.useShop(click(block));
        assertTrue(bought.isEmpty());
        assertEquals(9, stock[0].getAmount());
        denar.verify(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()), times(1));
    }

    @Test
    void factionMigrationWaitBlocksPostingUntilTheProviderIsReady() {
        ItemStack[] stock = stock(storage(tradingShop("buy")), new Stack(Material.DIRT, 10));
        List<ItemStack> bought = received(inventory);
        embargo.when(ShopEmbargo::ready).thenReturn(false);
        events.useShop(click(block));
        denar.verifyNoInteractions();
        assertTrue(bought.isEmpty());
        assertEquals(10, stock[0].getAmount());
        embargo.when(ShopEmbargo::ready).thenReturn(true);
        events.useShop(click(block));
        assertEquals(5, stock[0].getAmount());
        assertEquals(List.of(new Stack(Material.DIRT, 5)), bought);
        verifyTrade("buy");
        embargo.when(ShopEmbargo::ready).thenReturn(false);
        events.useShop(click(block));
        assertEquals(5, stock[0].getAmount());
        verifyTrade("buy");
    }

    @Test
    void freeShopMovesItemsOnlyAfterItsLedgerPostingApplies() {
        ShopSign shop = tradingShop("buy");
        shop.setPrice(0);
        ItemStack[] stock = stock(storage(shop), new Stack(Material.DIRT, 10));
        List<ItemStack> bought = received(inventory);
        events.useShop(click(block));
        assertEquals(5, stock[0].getAmount());
        assertEquals(List.of(new Stack(Material.DIRT, 5)), bought);
        denar.verify(() -> Denars.post(startsWith("shop:"), eq("shop:buy"), anyList()));
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
        shop.setOwnerAccount(CUSTOMER.toString());
        shop.setOwnerCharacter(CUSTOMER_CHARACTER.toString());
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
        shop.setOwnerAccount(CUSTOMER.toString());
        shop.setOwnerCharacter(CUSTOMER_CHARACTER.toString());
        shop.setSignLoc(signLocation);
        shop.setStorageLoc(storageLocation);
        shop.setType(type);
        shop.setBarterAmount(5);
        shop.setPrice(10);
        shop.setOwnerAccount(OWNER.toString());
        shop.setOwnerCharacter(OWNER_CHARACTER.toString());
        shop.setShopId("00000000-0000-0000-0000-000000000003");
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
        when(inventory.getMaxStackSize()).thenReturn(99);
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

    private void tradeResult(Result result) {
        denar.when(() -> Denars.trade(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyBoolean()))
                .thenReturn(result);
    }

    private void verifyTrade(String direction) {
        boolean buy = direction.equals("buy");
        denar.verify(() -> Denars.trade(startsWith("shop:"), eq("shop:" + direction),
                eq(buy ? "char:" + CUSTOMER_CHARACTER + ":pouch" : "char:" + OWNER_CHARACTER + ":bank"),
                eq(buy ? OWNER_REF : CUSTOMER_REF),
                eq(buy ? "char:" + OWNER_CHARACTER + ":bank" : "char:" + CUSTOMER_CHARACTER + ":pouch"), eq(1000L), eq(true)));
    }

    private void assertRefused(String message) {
        verify(player).sendMessage(message);
        verify(player).playSound(playerLocation, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        verify(inventory, never()).addItem(any(ItemStack[].class));
        verify(inventory, never()).setItem(anyInt(), any());
    }

    /**
     * Paper's ItemStack forwards to a server-side stack. This stands in for that stack, comparing
     * type (and, for equality, amount) the way Bukkit does for plain items.
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
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public ItemStack clone() {
            return new Stack(type, amount);
        }

        @Override
        public boolean isSimilar(ItemStack stack) {
            return stack != null && stack.getType() == type;
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
        public byte[] serializeAsBytes() {
            return (type.name() + ":" + amount).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return type + " x" + amount;
        }
    }
}
