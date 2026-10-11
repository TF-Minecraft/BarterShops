package net.tfminecraft.bartershops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.bartershops.sf.ShopEmbargo;

public class ShopEvents implements Listener{
	
	public HashMap<Player, Integer> shopCreating = new HashMap<>();
	public HashMap<Player, ShopSign> currentShop = new HashMap<>();
	
	public HashMap<Player, Long> cooldown = new HashMap<>();

	// Lets staff break shops owned by other players.
	public static final String ADMIN_PERMISSION = "bartershops.admin";
	private static final BlockFace[] NEIGHBOURS = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
	// Long enough for a tall column of sand to finish falling.
	static final long APPROVAL_MILLIS = 60_000L;

	// Shop signs an allowed break is expected to bring down, with when each approval runs out.
	final Map<Location, Long> approvedRemovals = new HashMap<>();
	LongSupplier clock = System::currentTimeMillis;
	
	Database db = new Database();
	
	// Retain Bukkit chat-event ordering and String message semantics for existing integrations.
	@SuppressWarnings("deprecation")
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
				player.sendMessage("§cInvalid Format, use numbers only");
				return;
			}
			if(amount <= 0) {
				player.sendMessage("§cItem quantity must be greater than 0.");
				return;
			}
			ShopMain.plugin.getServer().getScheduler().runTask(ShopMain.plugin, new Runnable()
			{
			    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
			    @SuppressWarnings("deprecation")
			    @Override
			    public void run()
			    {
			    	ShopSign shop = currentShop.get(player);
			    	shop.setBarterAmount(amount);
			    	player.sendTitle("Shop Creation §e3/4", "§aType the price in chat", 5, 80, 6);
					shopCreating.put(player, 4);
					currentShop.put(player, shop);
			    }
			});
		}
		if(shopCreating.get(player) == 4){
			event.setCancelled(true);
			Integer amount;
			try {
				amount = Integer.parseInt(event.getMessage());
			} catch(Exception ex){
				player.sendMessage("§cInvalid Format, use numbers only");
				return;
			}
			if(amount < 0) {
				player.sendMessage("§cPrice must be 0 or greater.");
				return;
			}
			ShopMain.plugin.getServer().getScheduler().runTask(ShopMain.plugin, new Runnable()
			{
			    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
			    @SuppressWarnings("deprecation")
			    @Override
			    public void run()
			    {
			    	ShopSign shop = currentShop.get(player);
			    	shop.setPrice(amount);
			    	player.sendTitle("Shop Creation §e4/4", "§aShop Created!", 5, 80, 6);
			    	Sign sign = (Sign) shop.getSignLoc().getBlock().getState();
			    	sign.setLine(0, "§9["+shop.getType().toUpperCase()+"]");
			    	sign.update();
					shopCreating.remove(player);
					db.saveShop(shop);
					currentShop.remove(player);
			    }
			});
		}
	}
	
	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
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
					p.sendMessage("§cShop Sign has invalid type");
					return;
				}
				shop.setOwner(p.getUniqueId().toString());
				shop.setSignLoc(b.getLocation());
				e.setCancelled(true);
				p.sendTitle("Shop Creation §e1/4", "§aRight Click on the storage chest with the redstone", 5, 80, 6);
				shopCreating.put(p, 1);
				currentShop.put(p, shop);
			} else if(shopCreating.get(p) == 1) {
				ItemStack redstone = p.getInventory().getItemInMainHand();
				if(!redstone.getType().equals(Material.REDSTONE)) return;
				if(!b.getType().equals(Material.CHEST)) return;
				e.setCancelled(true);
				ShopSign shop = currentShop.get(p);
				if(b.getLocation().distanceSquared(shop.getSignLoc()) > 1024) {
					p.sendMessage("§cChest is too far away");
					return;
				}
				shop.setStorageLoc(b.getLocation());
				currentShop.put(p, shop);
				e.setCancelled(true);
				p.sendTitle("Shop Creation §e2/4", "§aType the buy/sell amount in chat", 5, 80, 6);
				shopCreating.put(p, 3);
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
				p.sendMessage("§cShop Sign has invalid type");
				return;
			}
			shop.setOwner(p.getUniqueId().toString());
			shop.setSignLoc(b.getLocation());
			e.setCancelled(true);
			p.sendTitle("Shop Creation §e1/4", "§aRight Click on the storage chest with the redstone", 5, 80, 6);
			shopCreating.put(p, 1);
			currentShop.put(p, shop);
		}
	}
	
	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
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
		if(shop == null || !shop.hasValidTerms()) {
			p.sendMessage("§cThe terms on this shop sign make no sense. Speak to the shop owner.");
			return;
		}
		if(ShopEmbargo.blocked(p, shop)) {
			p.sendMessage("§cYou cannot use this shop: your nation is under embargo.");
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		if(!shop.getStorageLoc().getBlock().getType().equals(Material.CHEST)) {
			p.sendMessage("§cShop missing a storage chest");
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		Chest storage = (Chest) shop.getStorageLoc().getBlock().getState();
		
		if(shop.getType().equalsIgnoreCase("buy")) {
			// Only stacks of the item being sold count as stock; other items in the chest are never handed over.
			ItemStack item = firstItem(storage.getInventory());
			if(item == null || hasEnoughItems(storage.getInventory(), item, shop.getBarterAmount()) == false) {
				p.sendMessage("§cShop out of stock");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			// Items that do not fit would be lost after payment, so the whole order must fit.
			if(hasRoomFor(p.getInventory(), item, shop.getBarterAmount()) == false) {
				p.sendMessage(ChatColor.RED + "Your inventory is full!");	
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			Account pouch = DenarEconomy.getPlayerManager().get(p).getPouch();
			if(pouch.getBal() < shop.getPrice()){
				p.sendMessage("§cCannot afford this!");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			pouch.change(shop.getPrice()*-1);
			DenarEconomy.getMoneyManager().addMoneyToAccount(shop.getOwner(), shop.getPrice(), true, true, Accounts.BANK);
			p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
			exchangeItems(storage.getInventory(), p.getInventory(), item, shop.getBarterAmount());
		}
		if(shop.getType().equalsIgnoreCase("sell")) {
			ItemStack item = firstItem(storage.getInventory());
			if(item != null && hasRoomFor(storage.getInventory(), item, shop.getBarterAmount()) == false) {
				p.sendMessage(ChatColor.RED + "Shop Storage is full!");	
				return;
			}
			if(p.getInventory().firstEmpty() == -1) {
				p.sendMessage(ChatColor.RED + "Your inventory is full!");	
				return;
			}
			if(DenarEconomy.getPlayerManager().get(UUID.fromString(shop.getOwner())).getBank().getBal() < shop.getPrice()){
				p.sendMessage("§cThe shop owner lacks money!");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			if(item == null) {
				p.sendMessage("§cShop has no item set for selling");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			if(hasEnoughItems(p.getInventory(), item, shop.getBarterAmount()) == false) {
				p.sendMessage("§cYou dont have enough to sell");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			Account pouch = DenarEconomy.getPlayerManager().get(p).getPouch();
			DenarEconomy.getMoneyManager().changeBal(shop.getOwner(), shop.getPrice()*-1, Accounts.BANK);
			DenarEconomy.getMoneyManager().addMoney(p, shop.getPrice(), false, true);
			p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
			exchangeItems(p.getInventory(), storage.getInventory(), item, shop.getBarterAmount());
		}
	}
	
	@EventHandler
	public void breakDoor(BlockBreakEvent e) {
		Player p = e.getPlayer();
		for(Block b : signsBrokenWith(e.getBlock())) {
			if(db.shopExistsFromLoc(b.getLocation()) == false) continue;
			if(!mayBreak(p, b.getLocation())) {
				e.setCancelled(true);
				p.sendMessage("§cOnly the shop owner can break this shop.");
				return;
			}
		}
	}

	// Once nothing has cancelled the break: the broken sign's record goes now, and signs expected
	// to fall with it may come down for a while.
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void approveBreak(BlockBreakEvent e) {
		// A sign broken directly must not leave an approval for a new shop built in its place.
		approvedRemovals.remove(e.getBlock().getLocation());
		for(Block b : signsBrokenWith(e.getBlock())) {
			Location loc = b.getLocation();
			if(db.shopExistsFromLoc(loc) == false) continue;
			if(b.equals(e.getBlock())) db.deleteFile(loc);
			else approvedRemovals.put(loc, clock.getAsLong() + APPROVAL_MILLIS);
		}
	}

	// Physics, falling blocks and the like: a shop sign comes down only when an allowed break approved it.
	@EventHandler(ignoreCancelled = true)
	public void keepShopSign(BlockDestroyEvent e) {
		Block b = e.getBlock();
		if(!isSign(b) || approved(b.getLocation())) return;
		if(db.shopExistsFromLoc(b.getLocation())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void removeDestroyedShop(BlockDestroyEvent e) {
		Block b = e.getBlock();
		if(!isSign(b) || !approved(b.getLocation())) return;
		approvedRemovals.remove(b.getLocation());
		db.deleteFile(b.getLocation());
	}

	@EventHandler(ignoreCancelled = true)
	public void entityExplode(EntityExplodeEvent e) {
		e.blockList().removeIf(this::isShopSign);
	}

	@EventHandler(ignoreCancelled = true)
	public void blockExplode(BlockExplodeEvent e) {
		e.blockList().removeIf(this::isShopSign);
	}

	private boolean approved(Location loc) {
		long now = clock.getAsLong();
		approvedRemovals.values().removeIf(expiry -> expiry <= now);
		return approvedRemovals.containsKey(loc);
	}

	private boolean isShopSign(Block b) {
		return isSign(b) && db.shopExistsFromLoc(b.getLocation());
	}

	private static boolean isSign(Block b) {
		return b.getType().toString().contains("SIGN");
	}

	private boolean mayBreak(Player p, Location signLoc) {
		if(p.hasPermission(ADMIN_PERMISSION)) return true;
		return p.getUniqueId().toString().equals(db.getOwnerFromLoc(signLoc));
	}

	// Every sign that comes down with the broken block: the block itself, signs held up by anything
	// that falls, and sand or gravel left with nothing under it.
	private List<Block> signsBrokenWith(Block broken) {
		List<Block> signs = new ArrayList<>();
		Set<Block> falling = new HashSet<>(List.of(broken));
		Deque<Block> queue = new ArrayDeque<>(falling);
		while(!queue.isEmpty()) {
			Block b = queue.poll();
			if(b.getType().toString().contains("SIGN")) signs.add(b);
			for(BlockFace face : NEIGHBOURS) {
				Block n = b.getRelative(face);
				if(falling.contains(n) || !fallsWithout(n, face.getOppositeFace())) continue;
				falling.add(n);
				queue.add(n);
			}
		}
		return signs;
	}

	private static boolean fallsWithout(Block b, BlockFace side) {
		if(supports(b.getBlockData()).contains(side)) return true;
		return side == BlockFace.DOWN && b.getType().hasGravity();
	}

	// Sides holding a sign up. Wall hanging signs stay up once placed, even with both sides gone.
	static Set<BlockFace> supports(BlockData data) {
		if(data instanceof WallSign wall) return Set.of(wall.getFacing().getOppositeFace());
		if(data instanceof WallHangingSign) return Set.of();
		if(data instanceof HangingSign) return Set.of(BlockFace.UP);
		if(data instanceof org.bukkit.block.data.type.Sign) return Set.of(BlockFace.DOWN);
		return Set.of();
	}

	private ItemStack firstItem(Inventory i) {
		for(ItemStack item : i.getStorageContents()) {
			if(item == null) continue;
			if(item.getType().equals(Material.AIR)) continue;
			return new ItemStack(item);
		}
		return null;
	}

	public boolean hasRoomFor(Inventory i, ItemStack match, Integer amount) {
		int max = Math.min(match.getMaxStackSize(), i.getMaxStackSize());
		long room = 0;
		for(ItemStack item : i.getStorageContents()) {
			if(item == null || item.getType().equals(Material.AIR)) {
				room += max;
				continue;
			}
			if(match.isSimilar(item)) {
				room += Math.max(0, max - item.getAmount());
			}
		}
		return room >= amount;
	}

	public Boolean hasEnoughItems(Inventory i, ItemStack match, Integer amount) {
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
