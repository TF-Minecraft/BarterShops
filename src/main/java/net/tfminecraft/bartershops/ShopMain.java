package net.tfminecraft.bartershops;

import java.io.File;
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
    }
}
