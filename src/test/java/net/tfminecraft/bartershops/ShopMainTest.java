package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;

class ShopMainTest {
    @TempDir Path directory;

    @Test
    void enablesProtectionButWaitsForHeirsBeforeOpeningShops() {
        ShopMain plugin = mock(ShopMain.class);
        Server server = mock(Server.class);
        PluginManager plugins = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.getScheduler()).thenReturn(scheduler);
        ServicesManager services = mock(ServicesManager.class);
        when(server.getServicesManager()).thenReturn(services);
        PluginCommand command = mock(PluginCommand.class);
        when(plugin.getCommand("bartershops")).thenReturn(command);
        plugin.events = new ShopEvents();
        doCallRealMethod().when(plugin).onEnable();
        ShopMain previous = ShopMain.plugin;
        try (var characters = mockStatic(Characters.class)) {
            plugin.onEnable();
            assertSame(plugin, ShopMain.plugin);
            assertFalse(plugin.events.ready.getAsBoolean());
            verify(plugins).registerEvents(plugin.events, plugin);
            var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTaskTimer(eq(plugin), task.capture(), eq(100L), eq(100L));
            characters.when(Characters::available).thenReturn(true);
            characters.when(Characters::legacyHeirsReady).thenReturn(true);
            task.getValue().run();
            assertTrue(plugin.events.ready.getAsBoolean());
            verify(command).setExecutor(any(IntentCommand.class));
            verify(command).setTabCompleter(any(IntentCommand.class));
            verify(services).register(eq(EstateParticipant.class), any(ShopEstate.class), eq(plugin), eq(ServicePriority.Normal));
            verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(ShopEstate.HOUR_TICKS));
        } finally {
            ShopMain.plugin = previous;
        }
    }
}
