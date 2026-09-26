package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

class DatabaseTest {
    private static final String OWNER = "00000000-0000-0000-0000-000000000001";

    @TempDir Path directory;
    private World world;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void mockServer() {
        world = mock(World.class);
        when(world.toString()).thenReturn("CraftWorld{name=world}");
        Server server = mock(Server.class);
        when(server.getWorld("world")).thenReturn(world);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServer).thenReturn(server);
    }

    @AfterEach
    void closeServer() {
        bukkit.close();
    }

    @ParameterizedTest
    @CsvSource({
        "0.0,-2147483647.0", "1.0,-2147483647.0", "1.0,-2147483648.0",
        "0.0,10.0", "-1.0,10.0", "null,10.0", "1.0,null",
        "1.0,-0.4", "1.5,10.0", "1.0,0.5", "2147483648.0,10.0",
        "1.0,4294967296.0", "\"1\",10.0",
        "1.0000000000000001,10.0", "1.0,1e-400", "1.0,-1e-400",
        "1.0,2147483647.0000001", "1.0,\"0\""
    })
    void refusesInvalidSavedTermsWithoutDeletingTheShop(String quantity, String price) throws Exception {
        Path file = writeShop(quantity, price);
        String before = Files.readString(file);
        Location sign = new Location(world, 1, 2, 3);
        Database database = new Database(directory.toFile());
        assertTrue(database.shopExistsFromLoc(sign));
        assertNull(database.getShopFromLoc(sign));
        assertEquals(before, Files.readString(file));
    }

    @ParameterizedTest
    @CsvSource({"1.0,0.0,1,0", "64.0,100.0,64,100", "1,0,1,0", "2147483647.0,2147483647.0,2147483647,2147483647"})
    void preservesValidSavedTermsIncludingFreeShops(String quantity, String price, int expectedQuantity,
                                                   int expectedPrice) throws Exception {
        writeShop(quantity, price);
        Location sign = new Location(world, 1, 2, 3);
        ShopSign shop = new Database(directory.toFile()).getShopFromLoc(sign);
        assertNotNull(shop);
        assertEquals(expectedQuantity, shop.getBarterAmount());
        assertEquals(expectedPrice, shop.getPrice());
    }

    @Test
    void loadsEverySavedFieldForTheShopAtTheSign() throws Exception {
        Files.writeString(directory.resolve("shop.json"), """
            {
              "sign world": "world", "sign xPos": 1.0, "sign yPos": 2.0, "sign zPos": 3.0,
              "storage world": "world", "storage xPos": 4.0, "storage yPos": 5.0, "storage zPos": 6.0,
              "barter amount": 16.0, "price": 40.0, "payment item": "sword.katana",
              "owner": "%s", "type": "sell"
            }
            """.formatted(OWNER));

        ShopSign shop = new Database(directory.toFile()).getShopFromLoc(new Location(world, 1, 2, 3));

        assertEquals(new Location(world, 1, 2, 3), shop.getSignLoc());
        assertEquals(new Location(world, 4, 5, 6), shop.getStorageLoc());
        assertEquals(16, shop.getBarterAmount());
        assertEquals(40, shop.getPrice());
        assertEquals("sword.katana", shop.getPaymentItemString());
        assertEquals(OWNER, shop.getOwner());
        assertEquals("sell", shop.getType());
    }

    @Test
    void lookupsSkipFoldersUnreadableFilesAndOtherSigns() throws Exception {
        Files.createDirectory(directory.resolve("folder"));
        Files.writeString(directory.resolve("broken.json"), "{");
        writeShop("1.0", "10.0");
        Location elsewhere = new Location(world, 9, 9, 9);
        Database database = new Database(directory.toFile());

        assertNull(database.getShopFromLoc(elsewhere));
        assertFalse(database.shopExistsFromLoc(elsewhere));
        database.deleteFile(elsewhere);

        assertTrue(Files.exists(directory.resolve("shop.json")));
        assertTrue(Files.exists(directory.resolve("broken.json")));
        assertTrue(Files.isDirectory(directory.resolve("folder")));
    }

    @Test
    void existenceChecksAndDeletionNeedDecimalSignCoordinates() throws Exception {
        // json-simple reads whole numbers as Long, which the Double casts reject.
        Files.writeString(directory.resolve("shop.json"), """
            {"sign world": "world", "sign xPos": 1, "sign yPos": 2, "sign zPos": 3}
            """);
        Location sign = new Location(world, 1, 2, 3);
        Database database = new Database(directory.toFile());

        assertFalse(database.shopExistsFromLoc(sign));
        database.deleteFile(sign);

        assertTrue(Files.exists(directory.resolve("shop.json")));
    }

    @Test
    void deletesOnlyTheShopAtTheSign() throws Exception {
        Files.createDirectory(directory.resolve("folder"));
        Files.writeString(directory.resolve("broken.json"), "{");
        writeShop("1.0", "10.0");
        Files.writeString(directory.resolve("other.json"), """
            {"sign world": "world", "sign xPos": 7.0, "sign yPos": 2.0, "sign zPos": 3.0}
            """);
        Location sign = new Location(world, 1, 2, 3);
        Database database = new Database(directory.toFile());

        database.deleteFile(sign);

        assertFalse(Files.exists(directory.resolve("shop.json")));
        assertFalse(database.shopExistsFromLoc(sign));
        assertTrue(database.shopExistsFromLoc(new Location(world, 7, 2, 3)));
        assertTrue(Files.exists(directory.resolve("broken.json")));
    }

    @Test
    void savedShopsLoadBackUnchanged() throws Exception {
        ShopSign saved = shop();
        Database database = new Database(directory.toFile());

        database.saveShop(saved);

        File[] files = directory.toFile().listFiles();
        assertEquals(1, files.length);
        assertTrue(files[0].getName().matches("[0-9a-f-]{36}\\.json"));
        assertTrue(database.shopExistsFromLoc(saved.getSignLoc()));
        ShopSign loaded = database.getShopFromLoc(saved.getSignLoc());
        assertEquals(saved.getSignLoc(), loaded.getSignLoc());
        assertEquals(saved.getStorageLoc(), loaded.getStorageLoc());
        assertEquals(16, loaded.getBarterAmount());
        assertEquals(40, loaded.getPrice());
        assertEquals("sword.katana", loaded.getPaymentItemString());
        assertEquals(OWNER, loaded.getOwner());
        assertEquals("buy", loaded.getType());
    }

    @Test
    void savingPicksAnotherNameWhenTheFirstIsTaken() throws Exception {
        UUID taken = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID free = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Files.writeString(directory.resolve(taken + ".json"), "existing");
        try (MockedStatic<UUID> uuids = mockStatic(UUID.class)) {
            uuids.when(UUID::randomUUID).thenReturn(taken, free);
            new Database(directory.toFile()).saveShop(shop());
        }
        assertEquals("existing", Files.readString(directory.resolve(taken + ".json")));
        assertTrue(Files.readString(directory.resolve(free + ".json")).contains("sword.katana"));
    }

    @Test
    void savingAShopWithoutASignDoesNotThrow() {
        ShopSign shop = shop();
        shop.setSignLoc(null);
        assertDoesNotThrow(() -> new Database(directory.toFile()).saveShop(shop));
    }

    @Test
    void saveWritesSupportedValuesPreferringStoredOnesAndSkipsTheRest() throws Exception {
        Database database = databaseWithStoredValues();
        File file = directory.resolve("out.json").toFile();
        JSONObject object = new JSONObject();
        object.put("default", true);
        JSONArray array = new JSONArray();
        array.add("default");
        HashMap<String, Object> defaults = new HashMap<>();
        defaults.put("colour", "&aGreen");
        defaults.put("name", "ignored default");
        defaults.put("ratio", 0.5);
        defaults.put("count", 7);
        defaults.put("stored", object);
        defaults.put("fresh", object);
        defaults.put("list", array);
        defaults.put("new list", array);
        defaults.put("big", 5L);
        defaults.put("missing", null);

        assertTrue(database.save(file, defaults));

        JSONObject written = (JSONObject) new JSONParser().parse(Files.readString(file.toPath()));
        assertEquals("\u00a7aGreen", written.get("colour"));
        assertEquals("Stored", written.get("name"));
        assertEquals(0.5, written.get("ratio"));
        assertEquals(7.0, written.get("count"));
        assertEquals(Map.of("stored", 1L), written.get("stored"));
        assertEquals(Map.of("default", true), written.get("fresh"));
        assertEquals(List.of(1L, 2L), written.get("list"));
        assertEquals(List.of("default"), written.get("new list"));
        assertFalse(written.containsKey("big"));
        assertFalse(written.containsKey("missing"));
    }

    @Test
    void saveReportsFailure() throws Exception {
        Database database = databaseWithStoredValues();
        HashMap<String, Object> defaults = new HashMap<>();
        defaults.put("name", "value");
        // Nothing has been read yet, so there are no stored values to consult.
        assertFalse(new Database(directory.toFile()).save(directory.resolve("out.json").toFile(), defaults));
        assertFalse(database.save(directory.toFile(), defaults));
    }

    @Test
    void valueGettersPreferStoredValuesThenDefaultsThenAFallback() throws Exception {
        Database database = databaseWithStoredValues();
        JSONObject object = new JSONObject();
        JSONArray array = new JSONArray();
        HashMap<String, Object> defaults = new HashMap<>();
        defaults.put("name", "ignored default");
        defaults.put("flag", "true");
        defaults.put("ratio", "0.25");
        defaults.put("count", "12");
        defaults.put("text", "text");
        defaults.put("object", object);
        defaults.put("array", array);

        assertEquals("Stored", database.getRawData("name", defaults));
        assertEquals("true", database.getRawData("flag", defaults));
        assertEquals("absent", database.getRawData("absent", defaults));
        assertEquals("\u00a7cStored red", database.getString("red", defaults));
        assertTrue(database.getBoolean("flag", defaults));
        assertFalse(database.getBoolean("absent", defaults));
        assertEquals(0.25, database.getDouble("ratio", defaults));
        assertEquals(-1, database.getDouble("text", defaults));
        assertEquals(12, database.getInteger("count", defaults));
        assertEquals(-1, database.getInteger("ratio", defaults));
        assertEquals(Map.of("stored", 1L), database.getObject("stored", defaults));
        assertSame(object, database.getObject("object", defaults));
        assertEquals(new JSONObject(), database.getObject("absent", defaults));
        assertEquals(List.of(1L, 2L), database.getArray("list", defaults));
        assertSame(array, database.getArray("array", defaults));
        assertEquals(new JSONArray(), database.getArray("absent", defaults));
    }

    /** The value getters read whichever file the last lookup parsed. */
    private Database databaseWithStoredValues() throws Exception {
        Files.writeString(directory.resolve("stored.json"), """
            {
              "sign world": "world", "sign xPos": 1.0, "sign yPos": 2.0, "sign zPos": 3.0,
              "name": "Stored", "red": "&cStored red", "stored": {"stored": 1}, "list": [1, 2]
            }
            """);
        Database database = new Database(directory.toFile());
        assertTrue(database.shopExistsFromLoc(new Location(world, 1, 2, 3)));
        return database;
    }

    private ShopSign shop() {
        ShopSign shop = new ShopSign();
        shop.setSignLoc(new Location(world, 1, 2, 3));
        shop.setStorageLoc(new Location(world, 4, 5, 6));
        shop.setBarterAmount(16);
        shop.setPrice(40);
        shop.setPaymentItem("sword.katana");
        shop.setOwner(OWNER);
        shop.setType("buy");
        return shop;
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
