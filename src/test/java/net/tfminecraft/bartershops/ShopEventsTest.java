package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

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
            Arguments.of(3, "0", "§cItem quantity must be greater than 0."),
            Arguments.of(3, "-1", "§cItem quantity must be greater than 0."),
            Arguments.of(3, "-2147483648", "§cItem quantity must be greater than 0."),
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
}
