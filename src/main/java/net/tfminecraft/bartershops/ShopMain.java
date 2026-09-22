package net.tfminecraft.bartershops;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;


public class ShopMain extends JavaPlugin{
	FileConfiguration config = getConfig();
	
	public static ShopMain plugin;
	
	ShopEvents events = new ShopEvents();
	
	/* This is a very old plugin, so the code is pretty bad, but i maintain it since its unneccesary work to reinvent the wheel :) */
	@Override
	public void onEnable(){
		plugin = this;
		
		getServer().getPluginManager().registerEvents(events, this);
		
	}
}
