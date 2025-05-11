package net.tfminecraft.BarterShops;

import java.util.HashMap;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.api.item.mmoitem.MMOItem;

public class ShopEvents implements Listener{
	
	public HashMap<Player, Integer> shopCreating = new HashMap<>();
	public HashMap<Player, ShopSign> currentShop = new HashMap<>();
	
	public HashMap<Player, Long> cooldown = new HashMap<>();
	
	Database db = new Database();
	
	@EventHandler
	public void chatEvent(AsyncPlayerChatEvent event) {
		Player player = event.getPlayer();
		if(shopCreating.get(player) == null) {
			return;
		}
		if(shopCreating.get(player) == 3){
			event.setCancelled(true);
			Integer amount;
			try {
				amount = Integer.parseInt(event.getMessage());
			} catch(Exception ex){
				player.sendMessage("�cInvalid Format, use numbers only");
				return;
			}
			ShopMain.plugin.getServer().getScheduler().runTask(ShopMain.plugin, new Runnable()
			{
			    @Override
			    public void run()
			    {
			    	ShopSign shop = currentShop.get(player);
			    	shop.setBarterAmount(amount);
			    	player.sendTitle("Shop Creation �e4/6", "�aRight Click on the payment chest with the redstone", 5, 80, 6);
					shopCreating.put(player, 4);
					currentShop.put(player, shop);
			    }
			});
		}
		if(shopCreating.get(player) == 5){
			event.setCancelled(true);
			Integer amount;
			try {
				amount = Integer.parseInt(event.getMessage());
			} catch(Exception ex){
				player.sendMessage("�cInvalid Format, use numbers only");
				return;
			}
			ShopMain.plugin.getServer().getScheduler().runTask(ShopMain.plugin, new Runnable()
			{
			    @Override
			    public void run()
			    {
			    	ShopSign shop = currentShop.get(player);
			    	shop.setPrice(amount);
			    	player.sendTitle("Shop Creation �e6/6", "�aShop Created!", 5, 80, 6);
			    	Sign sign = (Sign) shop.getSignLoc().getBlock().getState();
			    	sign.setLine(0, "�9["+shop.getType().toUpperCase()+"]");
			    	sign.update();
					shopCreating.remove(player);
					db.saveShop(shop);
					currentShop.remove(player);
			    }
			});
		}
	}
	
	@EventHandler
	public void createShopEvent(PlayerInteractEvent e) {
		if(!e.getAction().equals(Action.RIGHT_CLICK_BLOCK)) return;
		Block b = e.getClickedBlock();
		Player p = e.getPlayer();
		if(shopCreating.containsKey(p)) {
			if(shopCreating.get(p) == 0) {
				if(!b.getType().toString().contains("SIGN")) return;
				if(db.shopExistsFromLoc(b.getLocation()) == true) return;
				ItemStack redstone = p.getInventory().getItemInMainHand();
				if(!redstone.getType().equals(Material.REDSTONE)) return;
				Sign sign = (Sign) b.getState();
				String[] lines = sign.getLines();
				ShopSign shop = new ShopSign();
				if(lines[0].replace("[", "").replace("]", "").equalsIgnoreCase("BUY")) {
					shop.setType("buy");
				} else if(lines[0].replace("[", "").replace("]", "").equalsIgnoreCase("SELL")) {
					shop.setType("sell");
				} else {
					p.sendMessage("�cShop Sign has invalid type");
					return;
				}
				shop.setOwner(p.getDisplayName());
				shop.setSignLoc(b.getLocation());
				e.setCancelled(true);
				p.sendTitle("Shop Creation �e1/6", "�aRight Click on the sign with your currency", 5, 80, 6);
				shopCreating.put(p, 1);
				currentShop.put(p, shop);
			} else if(shopCreating.get(p) == 1) {
				if(!b.getType().toString().contains("SIGN")) return;
				ShopSign shop = currentShop.get(p);
				if(!shop.getSignLoc().equals(b.getLocation())) return;
				ItemStack currency = p.getInventory().getItemInMainHand();
				NBTItem nbt = NBTItem.get(currency);
				if(nbt.hasType() == false) {
					p.sendMessage("�cNot a valid currency.");
					return;
				}
				if(!nbt.getType().equalsIgnoreCase("currency")) {
					p.sendMessage("�cNot a valid currency.");
					return;
				}
				shop.setPaymentItem(nbt.getType().toLowerCase() + "." + nbt.getString("MMOITEMS_ITEM_ID").toLowerCase());
				currentShop.put(p, shop);
				e.setCancelled(true);
				p.sendTitle("Shop Creation �e2/6", "�aRight Click on the storage chest with the redstone", 5, 80, 6);
				shopCreating.put(p, 2);
			} else if(shopCreating.get(p) == 2) {
				ItemStack redstone = p.getInventory().getItemInMainHand();
				if(!redstone.getType().equals(Material.REDSTONE)) return;
				if(!b.getType().equals(Material.CHEST)) return;
				e.setCancelled(true);
				ShopSign shop = currentShop.get(p);
				if(b.getLocation().distanceSquared(shop.getSignLoc()) > 1024) {
					p.sendMessage("�cChest is too far away");
					return;
				}
				shop.setStorageLoc(b.getLocation());
				currentShop.put(p, shop);
				e.setCancelled(true);
				p.sendTitle("Shop Creation �e3/6", "�aType the buy/sell amount in chat", 5, 80, 6);
				shopCreating.put(p, 3);
			} else if(shopCreating.get(p) == 4) {
				ItemStack redstone = p.getInventory().getItemInMainHand();
				if(!redstone.getType().equals(Material.REDSTONE)) return;
				if(!b.getType().equals(Material.CHEST)) return;
				e.setCancelled(true);
				ShopSign shop = currentShop.get(p);
				if(b.getLocation().distanceSquared(shop.getSignLoc()) > 1024) {
					p.sendMessage("�cChest is too far away");
					return;
				}
				shop.setBankLoc(b.getLocation());
				currentShop.put(p, shop);
				e.setCancelled(true);
				p.sendTitle("Shop Creation �e5/6", "�aType the price in chat", 5, 80, 6);
				shopCreating.put(p, 5);
			}
		} else {
			if(!b.getType().toString().contains("SIGN")) return;
			if(db.shopExistsFromLoc(b.getLocation()) == true) return;
			ItemStack redstone = p.getInventory().getItemInMainHand();
			if(!redstone.getType().equals(Material.REDSTONE)) return;
			Sign sign = (Sign) b.getState();
			String[] lines = sign.getLines();
			ShopSign shop = new ShopSign();
			if(lines[0].replace("[", "").replace("]", "").equalsIgnoreCase("BUY")) {
				shop.setType("buy");
			} else if(lines[0].replace("[", "").replace("]", "").equalsIgnoreCase("SELL")) {
				shop.setType("sell");
			} else {
				p.sendMessage("�cShop Sign has invalid type");
				return;
			}
			shop.setOwner(p.getDisplayName());
			shop.setSignLoc(b.getLocation());
			e.setCancelled(true);
			p.sendTitle("Shop Creation �e1/6", "�aRight Click on the sign with your currency", 5, 80, 6);
			shopCreating.put(p, 1);
			currentShop.put(p, shop);
		}
	}
	
	@EventHandler
	public void useShop(PlayerInteractEvent e) {
		if(!e.getAction().equals(Action.RIGHT_CLICK_BLOCK)) return;
		Block b = e.getClickedBlock();
		if(!b.getType().toString().contains("SIGN")) return;
		if(db.shopExistsFromLoc(b.getLocation()) == false) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		ItemStack hand = p.getInventory().getItemInMainHand();
		if(hand.getType().equals(Material.REDSTONE)) return;
		ShopSign shop = db.getShopFromLoc(b.getLocation());
		if(!shop.getStorageLoc().getBlock().getType().equals(Material.CHEST)) {
			p.sendMessage("�cShop missing a storage chest");
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		if(!shop.getBankLoc().getBlock().getType().equals(Material.CHEST)) {
			p.sendMessage("�cShop missing a payment chest");
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		Chest storage = (Chest) shop.getStorageLoc().getBlock().getState();
		Chest bank = (Chest) shop.getBankLoc().getBlock().getState();
		
		if(shop.getType().equalsIgnoreCase("buy")) {
			if(storageHasEnoughItems(storage.getInventory(), shop.getBarterAmount()) == false) {
				p.sendMessage("�cShop out of stock");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			if(hasEnoughCurrency(p.getInventory(), shop.getPaymentItemString(), shop.getPrice()) == false) {
				p.sendMessage("�cYou cannot afford this");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			if(bank.getInventory().firstEmpty() == -1) {
				p.sendMessage(ChatColor.RED + "Shop Bank is full!");	
				return;
			}
			if(p.getInventory().firstEmpty() == -1) {
				p.sendMessage(ChatColor.RED + "Your inventory is full!");	
				return;
			}
			ItemStack item = null;
			for(ItemStack storeItem : storage.getInventory().getStorageContents()) {
				if(storeItem == null) continue;
				if(storeItem.getType().equals(Material.AIR)) continue;
				item = new ItemStack(storeItem);
				break;
			}
			p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
			exchangeCurrency(p.getInventory(), bank.getInventory(), shop.getPaymentItemString(), shop.getPrice());
			exchangeItems(storage.getInventory(), p.getInventory(), item, shop.getBarterAmount());
			
		}
		if(shop.getType().equalsIgnoreCase("sell")) {
			if(storage.getInventory().firstEmpty() == -1) {
				p.sendMessage(ChatColor.RED + "Shop Storage is full!");	
				return;
			}
			if(p.getInventory().firstEmpty() == -1) {
				p.sendMessage(ChatColor.RED + "Your inventory is full!");	
				return;
			}
			ItemStack item = null;
			for(ItemStack storeItem : storage.getInventory().getStorageContents()) {
				if(storeItem == null) continue;
				if(storeItem.getType().equals(Material.AIR)) continue;
				item = new ItemStack(storeItem);
				break;
			}
			if(item == null) {
				p.sendMessage("�cShop has no item set for selling");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			if(playerHasEnoughItems(p.getInventory(), item, shop.getBarterAmount()) == false) {
				p.sendMessage("�cYou dont have enough to sell");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			if(hasEnoughCurrency(bank.getInventory(), shop.getPaymentItemString(), shop.getPrice()) == false) {
				p.sendMessage("�cShop out of money");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
			exchangeCurrency(bank.getInventory(), p.getInventory(), shop.getPaymentItemString(), shop.getPrice());
			exchangeItems(p.getInventory(), storage.getInventory(), item, shop.getBarterAmount());
		}
	}
	
	@EventHandler
	public void breakDoor(BlockBreakEvent e) {
		Block b = e.getBlock();
		String type = e.getBlock().getType().toString();
		if(!b.getType().toString().contains("SIGN")) return;
		Boolean isShop = db.shopExistsFromLoc(b.getLocation());
		if(isShop != false) {
			new BukkitRunnable()
			{
				public void run()
			    {
					if(e.getBlock().getLocation().getBlock().getType().toString().equals(type)) return;
					db.deleteFile(b.getLocation());
			    }
			}.runTaskLater(ShopMain.plugin,5L);
		}
	}
	
	public Boolean hasEnoughCurrency(Inventory i, String currency, Integer amount) {
		Integer counter = 0;
		for(ItemStack item : i.getStorageContents()) {
			if(item == null) continue;
			NBTItem nbt = NBTItem.get(item);
			if(nbt.hasType() == false) continue;
			String currencyType = currency.split("\\.")[0];
			String currencyID = currency.split("\\.")[1];
			if(nbt.getType().equalsIgnoreCase(currencyType) && nbt.getString("MMOITEMS_ITEM_ID").equalsIgnoreCase(currencyID)) {
				counter = counter + item.getAmount();
			}
		}
		if(counter >= amount) {
			return true;
		}
		return false;
	}
	
	public void exchangeCurrency(Inventory sender, Inventory reciever, String currency, Integer amount) {
		for(ItemStack item : sender.getStorageContents()) {
			if(item == null) continue;
			NBTItem nbt = NBTItem.get(item);
			if(nbt.hasType() == false) continue;
			String currencyType = currency.split("\\.")[0];
			String currencyID = currency.split("\\.")[1];
			if(nbt.getType().equalsIgnoreCase(currencyType) && nbt.getString("MMOITEMS_ITEM_ID").equalsIgnoreCase(currencyID)) {
				if(amount > item.getAmount()) {
					ItemStack recieverItem = new ItemStack(item);
					recieverItem.setAmount(item.getAmount());
					reciever.addItem(recieverItem);
					amount = amount - item.getAmount();
					item.setAmount(0);
					continue;
				}
				if(amount == item.getAmount()) {
					ItemStack recieverItem = new ItemStack(item);
					recieverItem.setAmount(item.getAmount());
					reciever.addItem(recieverItem);
					item.setAmount(0);
					break;
				}
				item.setAmount(item.getAmount() - amount);
				ItemStack recieverItem = new ItemStack(item);
				recieverItem.setAmount(amount);
				reciever.addItem(recieverItem);
				break;
			}
		}
	}
	public Boolean playerHasEnoughItems(Inventory i, ItemStack match, Integer amount) {
		Integer counter = 0;
		for(ItemStack item : i.getStorageContents()) {
			if(item == null) continue;
			if(item.getType().equals(Material.AIR)) continue;
			match.setAmount(item.getAmount());
			if(match.equals(item)) {
				counter = counter + item.getAmount();
			}
		}
		if(counter >= amount) {
			return true;
		}
		return false;
	}
	
	public Boolean storageHasEnoughItems(Inventory i, Integer amount) {
		Integer counter = 0;
		for(ItemStack item : i.getStorageContents()) {
			if(item == null) continue;
			if(item.getType().equals(Material.AIR)) continue;
			counter = counter + item.getAmount();
		}
		if(counter >= amount) {
			return true;
		}
		return false;
	}
	
	public void exchangeItems(Inventory sender, Inventory reciever, ItemStack item, Integer amount) {
		Integer i = -1;
		for(ItemStack invItem : sender.getStorageContents()) {
			i++;
			if(invItem == null) continue;
			if(invItem.getType().equals(Material.AIR)) continue;
			item.setAmount(invItem.getAmount());
			if(item.equals(invItem)) {
				if(amount == 0) break;
				if(amount > invItem.getAmount()) {
					ItemStack recieverItem = new ItemStack(invItem);
					recieverItem.setAmount(invItem.getAmount());
					reciever.addItem(recieverItem);
					amount = amount - invItem.getAmount();
					invItem.setAmount(0);
					sender.setItem(i, null);
					continue;
				}
				if(amount == invItem.getAmount()) {
					ItemStack recieverItem = new ItemStack(invItem);
					recieverItem.setAmount(invItem.getAmount());
					reciever.addItem(recieverItem);
					invItem.setAmount(0);
					sender.setItem(i, null);
					break;
				}
				invItem.setAmount(invItem.getAmount() - amount);
				ItemStack recieverItem = new ItemStack(invItem);
				recieverItem.setAmount(amount);
				reciever.addItem(recieverItem);
				break;
			}
			continue;
		}
	}
}
