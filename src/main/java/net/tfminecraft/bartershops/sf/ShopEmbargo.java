package net.tfminecraft.bartershops.sf;

import java.util.UUID;

import org.bukkit.Bukkit;
import net.tfminecraft.tlibs.character.Characters;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
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
            Plugin plugin = Bukkit.getPluginManager().getPlugin("SimpleFactions");
            if (plugin == null) return false;
            if (!plugin.isEnabled() || !SimpleFactionsApi.ready()) return true;
			String character = Characters.currentSession(customer).map(session -> session.ref().characterId().toString()).orElse(null);
            if (character == null) return false;
            Faction customerFaction = FactionManager.getByMember(character);
			if (customerFaction == null) {
				return false;
			}
			Faction ownerFaction = ownerFaction(shop.getOwnerCharacter());
			if (ownerFaction == null) {
				return false;
			}
			return RelationManager.hasTradeEmbargo(ownerFaction, customerFaction);
		} catch (RuntimeException | LinkageError ex) {
			warn(ex);
			return true;
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
		return FactionManager.getByMember(uuid.toString());
	}

    /** Absent optional integration is allowed; installed but unavailable factions close all trades. */
    public static boolean ready() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("SimpleFactions");
            return plugin == null || (plugin.isEnabled() && SimpleFactionsApi.ready());
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

	private static void warn(Throwable ex) {
		if (!loggedFail && ShopMain.plugin != null) {
			loggedFail = true;
			ShopMain.plugin.getLogger().warning("[BarterShops] SimpleFactions trade blocked: " + ex.getMessage());
		}
	}
}
