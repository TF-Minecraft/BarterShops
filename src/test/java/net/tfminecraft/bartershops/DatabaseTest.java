package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

class DatabaseTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({
        "0.0,-2147483647.0", "1.0,-2147483647.0", "1.0,-2147483648.0",
        "0.0,10.0", "-1.0,10.0", "null,10.0", "1.0,null",
        "1.0,-0.4", "1.5,10.0", "1.0,0.5", "2147483648.0,10.0",
        "1.0,4294967296.0", "\"1\",10.0"
    })
    void refusesInvalidSavedTermsWithoutDeletingTheShop(String quantity, String price) throws Exception {
        Path file = writeShop(quantity, price);
        String before = Files.readString(file);
        World world = mock(World.class);
        Server server = mock(Server.class);
        when(server.getWorld("world")).thenReturn(world);
        Location sign = new Location(world, 1, 2, 3);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            Database database = new Database(directory.toFile());
            assertTrue(database.shopExistsFromLoc(sign));
            assertNull(database.getShopFromLoc(sign));
        }
        assertEquals(before, Files.readString(file));
    }

    @ParameterizedTest
    @CsvSource({"1.0,0.0,1,0", "64.0,100.0,64,100", "1,0,1,0", "2147483647.0,2147483647.0,2147483647,2147483647"})
    void preservesValidSavedTermsIncludingFreeShops(String quantity, String price, int expectedQuantity,
                                                   int expectedPrice) throws Exception {
        writeShop(quantity, price);
        World world = mock(World.class);
        Server server = mock(Server.class);
        when(server.getWorld("world")).thenReturn(world);
        Location sign = new Location(world, 1, 2, 3);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            ShopSign shop = new Database(directory.toFile()).getShopFromLoc(sign);
            assertNotNull(shop);
            assertEquals(expectedQuantity, shop.getBarterAmount());
            assertEquals(expectedPrice, shop.getPrice());
        }
    }

    private Path writeShop(String quantity, String price) throws Exception {
        return Files.writeString(directory.resolve("shop.json"), """
            {
              "sign world": "world", "sign xPos": 1.0, "sign yPos": 2.0, "sign zPos": 3.0,
              "storage world": "world", "storage xPos": 4.0, "storage yPos": 2.0, "storage zPos": 3.0,
              "barter amount": %s, "price": %s,
              "owner": "00000000-0000-0000-0000-000000000001", "type": "buy"
            }
            """.formatted(quantity, price));
    }
}
