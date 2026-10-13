package net.tfminecraft.bartershops;

import java.io.File;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ShopMain extends JavaPlugin {
    public static ShopMain plugin;
    ShopEvents events = new ShopEvents();
    private boolean ready;

    @Override
    public void onEnable() {
        plugin = this;
        events.db = new Database(new File(getDataFolder(), "Data"));
        events.trades = new ShopTrades(getDataFolder().toPath().resolve("trades"), getLogger());
        events.ready = () -> ready;
        ShopMigration migration = new ShopMigration(new File(getDataFolder(), "Data"),
                new File(getDataFolder(), "migrations"), getLogger());
        ready = migration.runWhenReady();
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!ready) ready = migration.runWhenReady();
        }, 100L, 100L);
        getServer().getPluginManager().registerEvents(events, this);
        PluginCommand command = getCommand("bartershops");
        if (command != null) {
            IntentCommand intents = new IntentCommand(events.trades,
                    getDataFolder().toPath().resolve("logs").resolve("staff-audit.log"), getLogger());
            command.setExecutor(intents);
            command.setTabCompleter(intents);
        }
        if (EstateProbe.available(EstateProbe.CLASSES)) {
            ShopEstate.start(this, events.db, events.trades, () -> ready);
        } else {
            getLogger().warning("TLibs has no estate API (2.6.0 or later); shops of dead characters stay with them.");
        }
    }
}
