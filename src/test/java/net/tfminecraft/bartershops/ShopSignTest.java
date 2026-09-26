package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import net.Indyuce.mmoitems.api.item.mmoitem.MMOItem;
import net.Indyuce.mmoitems.manager.ItemManager;
import net.Indyuce.mmoitems.manager.TypeManager;

class ShopSignTest {
    @Test
    void storesEveryField() {
        Location sign = mock(Location.class);
        Location storage = mock(Location.class);
        ShopSign shop = new ShopSign();
        shop.setSignLoc(sign);
        shop.setStorageLoc(storage);
        shop.setBarterAmount(16);
        shop.setPrice(40);
        shop.setOwner("00000000-0000-0000-0000-000000000001");
        shop.setPaymentItem("sword.katana");
        shop.setType("sell");

        assertSame(sign, shop.getSignLoc());
        assertSame(storage, shop.getStorageLoc());
        assertEquals(16, shop.getBarterAmount());
        assertEquals(40, shop.getPrice());
        assertEquals("00000000-0000-0000-0000-000000000001", shop.getOwner());
        assertEquals("sword.katana", shop.getPaymentItemString());
        assertEquals("sell", shop.getType());
    }

    @ParameterizedTest
    @CsvSource({
        "1,0,true", "64,100,true", "0,0,false", "-1,10,false",
        ",10,false", "1,,false", "1,-1,false"
    })
    void validTermsNeedAPositiveQuantityAndNonNegativePrice(Integer quantity, Integer price, boolean valid) {
        ShopSign shop = new ShopSign();
        shop.setBarterAmount(quantity);
        shop.setPrice(price);
        assertEquals(valid, shop.hasValidTerms());
    }

    @Test
    void paymentItemIsLookedUpByUpperCaseTypeAndId() {
        MMOItems previous = MMOItems.plugin;
        MMOItems mmoItems = mock(MMOItems.class);
        ItemManager items = mock(ItemManager.class);
        TypeManager types = mock(TypeManager.class);
        Type sword = mock(Type.class);
        MMOItem katana = mock(MMOItem.class);
        when(mmoItems.getItems()).thenReturn(items);
        when(mmoItems.getTypes()).thenReturn(types);
        when(types.get("SWORD")).thenReturn(sword);
        when(items.getMMOItem(sword, "KATANA")).thenReturn(katana);
        ShopSign shop = new ShopSign();
        shop.setPaymentItem("sword.katana");
        MMOItems.plugin = mmoItems;
        try {
            assertSame(katana, shop.getPaymentItem());
        } finally {
            MMOItems.plugin = previous;
        }
    }
}
