package net.tfminecraft.bartershops;

import org.bukkit.Location;

import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.item.mmoitem.MMOItem;
import net.Indyuce.mmoitems.manager.ItemManager;

public class ShopSign {
	public Location signLoc;
	public Location storageLoc;
	public Integer barterAmount;
	public Integer price;
	public String owner;
	public String paymentitem;
	public String type;

	//This was before i knew about constructors, i am so sorry...
	
	//Setters
	public void setSignLoc(Location loc) {
		this.signLoc = loc;
	}
	public void setStorageLoc(Location loc) {
		this.storageLoc = loc;
	}
	public void setBarterAmount(Integer i) {
		this.barterAmount = i;
	}
	public void setPrice(Integer i) {
		this.price = i;
	}
	public void setOwner(String p) {
		this.owner = p;
	}
	public void setPaymentItem(String i) {
		this.paymentitem = i;
	}
	public void setType(String type) {
		this.type = type;
	}
	
	//Getters
	public Location getSignLoc() {
		return this.signLoc;
	}
	public Location getStorageLoc() {
		return this.storageLoc;
	}
	public Integer getBarterAmount() {
		return this.barterAmount;
	}
	public Integer getPrice() {
		return this.price;
	}
	public boolean hasValidTerms() {
		return barterAmount != null && barterAmount > 0 && price != null && price >= 0;
	}
	public String getOwner() {
		return this.owner;
	}
	public String getPaymentItemString() {
		return this.paymentitem;
	}
	@SuppressWarnings("deprecation")
	public MMOItem getPaymentItem() {
		String itemType = paymentitem.toString().split("\\.")[0];
		String itemID = paymentitem.toString().split("\\.")[1];
		ItemManager itemManager = MMOItems.plugin.getItems();
		return itemManager.getMMOItem(MMOItems.plugin.getTypes().get(itemType.toUpperCase()), itemID.toUpperCase());
	}
	public String getType() {
		return this.type;
	}
}
