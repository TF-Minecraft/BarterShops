package net.tfminecraft.bartershops.sf;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import net.tfminecraft.bartershops.ShopMain;
import net.tfminecraft.bartershops.ShopSign;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.managers.RelationManager;
import net.tfminecraft.simplefactions.objects.Faction;

class ShopEmbargoTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<FactionManager> factions;
    private MockedStatic<RelationManager> relations;
    private PluginManager plugins;
    private Plugin simpleFactions;
    private Player customer;
    private OfflinePlayer owner;
    private Faction customerFaction;
    private Faction ownerFaction;
    private ShopSign shop;
    private ShopMain previousPlugin;

    @BeforeEach
    void setUp() throws Exception {
        resetLoggedFail();
        previousPlugin = ShopMain.plugin;
        bukkit = mockStatic(Bukkit.class);
        factions = mockStatic(FactionManager.class);
        relations = mockStatic(RelationManager.class);
        plugins = mock(PluginManager.class);
        simpleFactions = mock(Plugin.class);
        customer = mock(Player.class);
        owner = mock(OfflinePlayer.class);
        customerFaction = mock(Faction.class);
        ownerFaction = mock(Faction.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        bukkit.when(() -> Bukkit.getOfflinePlayer(OWNER)).thenReturn(owner);
        when(plugins.getPlugin("SimpleFactions")).thenReturn(simpleFactions);
        when(simpleFactions.isEnabled()).thenReturn(true);
        when(customer.getName()).thenReturn("Customer");
        when(owner.getName()).thenReturn("Owner");
        factions.when(() -> FactionManager.getByMember("Customer")).thenReturn(customerFaction);
        factions.when(() -> FactionManager.getByMember("Owner")).thenReturn(ownerFaction);
        shop = new ShopSign();
        shop.setOwner(OWNER.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        relations.close();
        factions.close();
        bukkit.close();
        ShopMain.plugin = previousPlugin;
        resetLoggedFail();
    }

    @Test
    void blocksCustomersWhoseNationIsEmbargoedByTheOwner() {
        relations.when(() -> RelationManager.hasTradeEmbargo(ownerFaction, customerFaction)).thenReturn(true);
        assertTrue(ShopEmbargo.blocked(customer, shop));
    }

    @Test
    void allowsCustomersWithoutAnEmbargo() {
        relations.when(() -> RelationManager.hasTradeEmbargo(ownerFaction, customerFaction)).thenReturn(false);
        assertFalse(ShopEmbargo.blocked(customer, shop));
    }

    @Test
    void missingCustomerOrShopIsNeverBlocked() {
        assertFalse(ShopEmbargo.blocked(null, shop));
        assertFalse(ShopEmbargo.blocked(customer, null));
        bukkit.verifyNoInteractions();
    }

    @Test
    void missingSimpleFactionsIsNeverBlocked() {
        when(plugins.getPlugin("SimpleFactions")).thenReturn(null);
        assertFalse(ShopEmbargo.blocked(customer, shop));
        factions.verifyNoInteractions();
    }

    @Test
    void disabledSimpleFactionsIsNeverBlocked() {
        when(simpleFactions.isEnabled()).thenReturn(false);
        assertFalse(ShopEmbargo.blocked(customer, shop));
        factions.verifyNoInteractions();
    }

    @Test
    void customerWithoutANationIsNeverBlocked() {
        factions.when(() -> FactionManager.getByMember("Customer")).thenReturn(null);
        assertFalse(ShopEmbargo.blocked(customer, shop));
        relations.verifyNoInteractions();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "not-a-uuid"})
    void unusableOwnerIdIsNeverBlocked(String ownerId) {
        shop.setOwner(ownerId);
        assertFalse(ShopEmbargo.blocked(customer, shop));
        relations.verifyNoInteractions();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void ownerWithoutAKnownNameIsNeverBlocked(String name) {
        when(owner.getName()).thenReturn(name);
        assertFalse(ShopEmbargo.blocked(customer, shop));
        relations.verifyNoInteractions();
    }

    @Test
    void ownerWithoutANationIsNeverBlocked() {
        factions.when(() -> FactionManager.getByMember("Owner")).thenReturn(null);
        assertFalse(ShopEmbargo.blocked(customer, shop));
        relations.verifyNoInteractions();
    }

    @Test
    void simpleFactionsFailuresAllowTheTradeAndWarnOnce() {
        ShopMain plugin = mock(ShopMain.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        ShopMain.plugin = plugin;
        relations.when(() -> RelationManager.hasTradeEmbargo(ownerFaction, customerFaction))
            .thenThrow(new IllegalStateException("relations not loaded"))
            .thenThrow(new NoClassDefFoundError("RelationManager"));

        assertFalse(ShopEmbargo.blocked(customer, shop));
        assertFalse(ShopEmbargo.blocked(customer, shop));

        verify(logger, times(1)).warning("[BarterShops] SimpleFactions embargo skipped: relations not loaded");
    }

    @Test
    void failuresBeforeThePluginIsEnabledAreNotLogged() throws Exception {
        ShopMain.plugin = null;
        relations.when(() -> RelationManager.hasTradeEmbargo(ownerFaction, customerFaction))
            .thenThrow(new IllegalStateException("relations not loaded"));

        assertFalse(ShopEmbargo.blocked(customer, shop));

        assertFalse(loggedFail().getBoolean(null));
    }

    private static Field loggedFail() throws Exception {
        Field field = ShopEmbargo.class.getDeclaredField("loggedFail");
        field.setAccessible(true);
        return field;
    }

    private static void resetLoggedFail() throws Exception {
        loggedFail().setBoolean(null, false);
    }
}
