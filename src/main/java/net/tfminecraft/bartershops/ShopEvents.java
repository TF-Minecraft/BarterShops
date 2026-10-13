package net.tfminecraft.bartershops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

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
import org.bukkit.event.Event;
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

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;
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
    ShopTrades trades;
    BooleanSupplier ready = () -> Characters.available() && Characters.legacyHeirsReady();

    private boolean stillCreating(Player player, ShopSign shop) {
        if (shop != null && ready.getAsBoolean() && Characters.currentSession(player)
                .map(session -> session.ref().equals(shop.getOwnerRef().orElse(null))).orElse(false)) return true;
        shopCreating.remove(player);
        currentShop.remove(player);
        player.sendMessage("§cShop creation cancelled: your active character changed or is unavailable.");
        return false;
    }

    private boolean setOwner(Player player, ShopSign shop) {
        Optional<CharacterRef> owner = Characters.currentSession(player).map(session -> session.ref());
        if (!ready.getAsBoolean() || owner.isEmpty()) {
            player.sendMessage("§cSelect a character before creating a shop; character shops must be ready.");
            return false;
        }
        shop.setOwnerAccount(owner.get().ownerId().toString());
        shop.setOwnerCharacter(owner.get().characterId().toString());
        return true;
    }
	
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
                    if (!stillCreating(player, shop)) return;
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
                    if (!stillCreating(player, shop)) return;
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
	// Runs after earlier-priority protection handlers, so the storage step can see whether they refused the chest.
	@SuppressWarnings("deprecation")
	@EventHandler(priority = EventPriority.HIGHEST)
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
				if (!setOwner(p, shop)) return;
				shop.setSignLoc(b.getLocation());
				e.setCancelled(true);
				p.sendTitle("Shop Creation §e1/4", "§aRight Click on the storage chest with the redstone", 5, 80, 6);
				shopCreating.put(p, 1);
				currentShop.put(p, shop);
			} else if(shopCreating.get(p) == 1) {
				ItemStack redstone = p.getInventory().getItemInMainHand();
				if(!redstone.getType().equals(Material.REDSTONE)) return;
				if(!b.getType().equals(Material.CHEST)) return;
				if(e.useInteractedBlock() == Event.Result.DENY) {
					p.sendMessage("§cYou can only use a chest you are allowed to open");
					return;
				}
				e.setCancelled(true);
				ShopSign shop = currentShop.get(p);
                if (!stillCreating(p, shop)) return;
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
			if (!setOwner(p, shop)) return;
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
        Optional<CharacterRef> customer = Characters.currentSession(p).map(session -> session.ref());
        Optional<CharacterRef> owner = shop.getOwnerRef();
        if (!ready.getAsBoolean() || customer.isEmpty() || owner.isEmpty()) {
            p.sendMessage("§cThis shop is closed until its owner and your active character are available.");
            return;
        }
        // A dead owner's shop waits for the estate to give it on; both directions stay closed.
        Optional<CharacterStatus> ownerStatus = Characters.info(owner.get().characterId()).map(CharacterInfo::status);
        if (ownerStatus.isEmpty() || ownerStatus.get() != CharacterStatus.ALIVE) {
            refused(p, ownerStatus.isPresent() ? "§cThis shop's owner has died."
                    : "§cThis shop is closed until its owner and your active character are available.");
            return;
        }
        if (!ShopEmbargo.ready()) {
            refused(p, "§cShops are waiting for faction and tax data. Try again shortly.");
            return;
        }
        if (ShopEmbargo.blocked(p, shop)) {
            p.sendMessage("§cYou cannot use this shop: your nation is under embargo.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        if (!shop.getStorageLoc().getBlock().getType().equals(Material.CHEST)) {
            refused(p, "§cShop missing a storage chest");
            return;
        }
        Inventory storage = ((Chest) shop.getStorageLoc().getBlock().getState()).getInventory();
        boolean buy = "buy".equalsIgnoreCase(shop.getType());
        if (!buy && !"sell".equalsIgnoreCase(shop.getType())) return;
        if (!buy && p.getInventory().firstEmpty() == -1) {
            p.sendMessage(ChatColor.RED + "Your inventory is full!");
            return;
        }
        ItemStack item = firstItem(storage);
        if (item == null) {
            refused(p, buy ? "§cShop out of stock" : "§cShop has no item set for selling");
            return;
        }
        Inventory sender = buy ? storage : p.getInventory();
        Inventory receiver = buy ? p.getInventory() : storage;
        if (!hasEnoughItems(sender, item, shop.getBarterAmount())) {
            refused(p, buy ? "§cShop out of stock" : "§cYou dont have enough to sell");
            return;
        }
        if (!hasRoomFor(receiver, item, shop.getBarterAmount())) {
            refused(p, buy ? ChatColor.RED + "Your inventory is full!" : ChatColor.RED + "Shop Storage is full!");
            return;
        }
        item.setAmount(1);
        ItemStack[] beforeSender = snapshot(sender);
        ItemStack[] beforeReceiver = snapshot(receiver);
        ShopTrades.Request request = new ShopTrades.Request(shop.getShopId(), customer.get(), owner.get(),
                buy ? "buy" : "sell", shop.getBarterAmount(), shop.getPrice().longValue() * 100L, Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        ShopTrades.Outcome result = trades.trade(request, () -> {
            // A tax listener can run inside the posting. Recheck before any item mutation.
            if (!Arrays.equals(beforeSender, sender.getStorageContents())
                    || !Arrays.equals(beforeReceiver, receiver.getStorageContents())
                    || !Characters.currentSession(p).map(session -> session.ref().equals(customer.get())).orElse(false))
                throw new IllegalStateException("Character or inventory changed during payment");
            exchangeItems(sender, receiver, item, shop.getBarterAmount());
        });
        if (result == ShopTrades.Outcome.APPLIED) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        } else if (result == ShopTrades.Outcome.REFUSED) {
            refused(p, buy ? "§cCannot afford this!" : "§cThe shop owner lacks money!");
        } else if (result == ShopTrades.Outcome.WAITING) {
            refused(p, "§cThe economy is unavailable. Retry this trade when it returns.");
        } else {
            refused(p, "§cThis shop has an interrupted trade. Ask staff to review it before trading again.");
        }
    }

    private static ItemStack[] snapshot(Inventory inventory) {
        return Arrays.stream(inventory.getStorageContents()).map(item -> item == null ? null : item.clone())
                .toArray(ItemStack[]::new);
    }

    private static void refused(Player player, String message) {
        player.sendMessage(message);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
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
		return ready.getAsBoolean() && Characters.currentSession(p)
            .map(session -> session.ref().characterId().toString().equals(db.getOwnerCharacterFromLoc(signLoc)))
            .orElse(false);
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
			return item.clone();
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

    public Boolean hasEnoughItems(Inventory inventory, ItemStack match, Integer amount) {
        long count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() != Material.AIR && match.isSimilar(item)) count += item.getAmount();
        }
        return count >= amount;
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
					if (!reciever.addItem(recieverItem).isEmpty())
                        throw new IllegalStateException("Inventory rejected part of the delivery");
					amount = amount - invItem.getAmount();
					invItem.setAmount(0);
					sender.setItem(i, null);
					continue;
				}
				if(amount == invItem.getAmount()) {
					ItemStack recieverItem = new ItemStack(invItem);
					recieverItem.setAmount(invItem.getAmount());
					if (!reciever.addItem(recieverItem).isEmpty())
                        throw new IllegalStateException("Inventory rejected part of the delivery");
					invItem.setAmount(0);
					sender.setItem(i, null);
					break;
				}
				invItem.setAmount(invItem.getAmount() - amount);
				ItemStack recieverItem = new ItemStack(invItem);
				recieverItem.setAmount(amount);
				if (!reciever.addItem(recieverItem).isEmpty())
                        throw new IllegalStateException("Inventory rejected part of the delivery");
				break;
			}
			continue;
		}
	}
}
