package net.tfminecraft.bartershops.sf;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.managers.RelationManager;
import net.tfminecraft.simplefactions.objects.Faction;
import net.tfminecraft.bartershops.ShopMain;
import net.tfminecraft.bartershops.ShopSign;

/**
 * SimpleFactions trade embargo. Isolated so shop events do not import SF types.
 */
public final class ShopEmbargo {

	private static boolean loggedFail;

	private ShopEmbargo() {}

	public static boolean blocked(Player customer, ShopSign shop) {
		if (customer == null || shop == null) {
			return false;
		}
		try {
			if (!sfReady()) {
				return false;
			}
			Faction customerFaction = FactionManager.getByMember(customer.getName());
			if (customerFaction == null) {
				return false;
			}
			Faction ownerFaction = ownerFaction(shop.getOwner());
			if (ownerFaction == null) {
				return false;
			}
			return RelationManager.hasTradeEmbargo(ownerFaction, customerFaction);
		} catch (RuntimeException | LinkageError ex) {
			warn(ex);
			return false;
		}
	}

	private static Faction ownerFaction(String ownerUuid) {
		if (ownerUuid == null || ownerUuid.isBlank()) {
			return null;
		}
		UUID uuid;
		try {
			uuid = UUID.fromString(ownerUuid);
		} catch (IllegalArgumentException ex) {
			return null;
		}
		OfflinePlayer owner = Bukkit.getOfflinePlayer(uuid);
		String name = owner.getName();
		if (name == null || name.isBlank()) {
			return null;
		}
		return FactionManager.getByMember(name);
	}

	private static boolean sfReady() {
		Plugin plugin = Bukkit.getPluginManager().getPlugin("SimpleFactions");
		return plugin != null && plugin.isEnabled();
	}

	private static void warn(Throwable ex) {
		if (!loggedFail && ShopMain.plugin != null) {
			loggedFail = true;
			ShopMain.plugin.getLogger().warning("[BarterShops] SimpleFactions embargo skipped: " + ex.getMessage());
		}
	}
}
