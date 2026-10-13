package net.tfminecraft.bartershops;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

import net.tfminecraft.tlibs.character.CharacterRef;

public class Database {
	static final Gson SHOP_READER = new GsonBuilder()
        .setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
	// The owner's account; written before shops belonged to characters, so every shop has it.
	static final String OWNER = "owner";
	static final String OWNER_CHARACTER = "owner character";
	private final File dataFolder;
	Logger logger = Logger.getLogger("BarterShops");
	// Unreadable shop files already reported, so the hourly estate pass warns once per file.
	private final Set<String> unreadable = new HashSet<>();

	public Database() {
		this(new File("plugins/BarterShops/Data"));
	}

	Database(File dataFolder) {
		this.dataFolder = dataFolder;
	}

	private JSONObject json; // org.json.simple
    JSONParser parser = new JSONParser();
	public ShopSign getShopFromLoc(Location loc) {
    	for (final File file : shopFiles()) {
            if (!file.isDirectory()) {
                try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
                    Map<?, ?> shopJson = SHOP_READER.fromJson(reader, Map.class);
                    Location fileLoc = new Location(Bukkit.getServer().getWorld((String) shopJson.get("sign world")), ((Number) shopJson.get("sign xPos")).doubleValue(),((Number) shopJson.get("sign yPos")).doubleValue(),((Number) shopJson.get("sign zPos")).doubleValue());
    				if(!loc.equals(fileLoc)) continue;
                    Location storageLoc = new Location(Bukkit.getServer().getWorld((String) shopJson.get("storage world")), ((Number) shopJson.get("storage xPos")).doubleValue(),((Number) shopJson.get("storage yPos")).doubleValue(),((Number) shopJson.get("storage zPos")).doubleValue());
    				ShopSign shop = new ShopSign();
    				shop.setSignLoc(fileLoc);
    				shop.setStorageLoc(storageLoc);
                    shop.setBarterAmount(readInteger(shopJson.get("barter amount")));
                    shop.setPrice(readInteger(shopJson.get("price")));
                    if (!shop.hasValidTerms()) return null;
                    shop.setShopId(shopId(file));
                    shop.setOwnerAccount((String) shopJson.get(OWNER));
                    shop.setOwnerCharacter(readCharacter(shopJson.get(OWNER_CHARACTER)));
                    shop.setPaymentItem((String) shopJson.get("payment item"));
                    shop.setType((String) shopJson.get("type"));
    				return shop;
    			} catch (Exception ex) {
    				ex.printStackTrace();
    			}
            }
        }
    	return null;
    }

    static String shopId(File file) {
        String name = file.getName();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    // Only a well-formed character ID owns a shop; anything else leaves it closed.
    static String readCharacter(Object value) {
        if (!(value instanceof String text)) return null;
        try {
            return UUID.fromString(text).toString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** One shop file's owner and sign, as estates need them. */
    public record ShopRecord(String shopId, String ownerCharacter, String type, String world, double x, double y, double z) {
        String label() {
            return (type == null || type.isEmpty() ? "Shop" : type.substring(0, 1).toUpperCase() + type.substring(1) + " shop")
                + " at " + world + " " + (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
        }
    }

    /** Every readable shop file. Unreadable files are skipped, as the sign lookups skip them. */
    public List<ShopRecord> shops() {
        List<ShopRecord> shops = new ArrayList<>();
        for (File file : shopFiles()) {
            try {
                Map<?, ?> shopJson = read(file);
                shops.add(new ShopRecord(shopId(file), readCharacter(shopJson.get(OWNER_CHARACTER)),
                    shopJson.get("type") instanceof String type ? type : null, String.valueOf(shopJson.get("sign world")),
                    number(shopJson.get("sign xPos")), number(shopJson.get("sign yPos")), number(shopJson.get("sign zPos"))));
            } catch (IOException ex) {
                if (unreadable.add(file.getName()))
                    logger.warning("Skipping unreadable shop file " + file.getName() + ": " + ex.getMessage());
            }
        }
        return shops;
    }

    /** The owning character of one shop; empty when the shop is gone or has no character. */
    public Optional<String> ownerCharacter(String shopId) throws IOException {
        File file = shopFile(shopId);
        if (!file.isFile()) return Optional.empty();
        return Optional.ofNullable(readCharacter(read(file).get(OWNER_CHARACTER)));
    }

    /**
     * Gives one shop to another character, rewriting its file in place and keeping every other value.
     * False when the shop is gone.
     */
    public boolean setOwner(String shopId, CharacterRef owner) throws IOException {
        File file = shopFile(shopId);
        if (!file.isFile()) return false;
        Map<String, Object> shopJson = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        read(file).forEach((key, value) -> shopJson.put(String.valueOf(key), value));
        shopJson.put(OWNER, owner.ownerId().toString());
        shopJson.put(OWNER_CHARACTER, owner.characterId().toString());
        DurableFiles.write(file.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(shopJson));
        return true;
    }

    // Shop IDs are file names, so only a UUID may name one.
    private File shopFile(String shopId) {
        return new File(dataFolder, UUID.fromString(shopId) + ".json");
    }

    private static Map<?, ?> read(File file) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
            Map<?, ?> shopJson = SHOP_READER.fromJson(reader, Map.class);
            if (shopJson == null) throw new IOException("Empty shop file " + file);
            return shopJson;
        } catch (RuntimeException ex) {
            throw new IOException("Unreadable shop file " + file, ex);
        }
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    // The data folder only exists once a shop has been saved.
    private File[] shopFiles() {
        File[] files = dataFolder.listFiles((folder, name) -> name.endsWith(".json"));
        return files == null ? new File[0] : files;
    }

    // Keep saved decimal tokens exact through parsing and integer conversion:
    // rounding or narrowing can turn invalid prices/quantities into usable trades.
    private static Integer readInteger(Object value) {
        if (!(value instanceof Number)) return null;
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }
	
	public Boolean shopExistsFromLoc(Location loc) {
    	for (final File file : shopFiles()) {
            if (!file.isDirectory()) {
            	try {
    				json = (JSONObject) parser.parse(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    				Location fileLoc = new Location(Bukkit.getServer().getWorld((String) json.get("sign world")), ((Number) json.get("sign xPos")).doubleValue(),((Number) json.get("sign yPos")).doubleValue(),((Number) json.get("sign zPos")).doubleValue());
    				if(!loc.equals(fileLoc)) continue;
    				return true;
    			} catch (Exception ex) {
    				ex.printStackTrace();
    			}
            }
        }
    	return false;
    }
	
	/** The character owning the shop at the sign, or null when it has none yet. */
	public String getOwnerCharacterFromLoc(Location loc) {
    	for (final File file : shopFiles()) {
            if (!file.isDirectory()) {
            	try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
    				JSONObject shopJson = (JSONObject) parser.parse(reader);
    				Location fileLoc = new Location(Bukkit.getServer().getWorld((String) shopJson.get("sign world")), ((Number) shopJson.get("sign xPos")).doubleValue(),((Number) shopJson.get("sign yPos")).doubleValue(),((Number) shopJson.get("sign zPos")).doubleValue());
    				if(!loc.equals(fileLoc)) continue;
    				return readCharacter(shopJson.get(OWNER_CHARACTER));
    			} catch (Exception ex) {
    				ex.printStackTrace();
    			}
            }
        }
    	return null;
    }
	
	public void deleteFile(Location loc) {
    	for (final File file : shopFiles()) {
            if (!file.isDirectory()) {
            	try {
    				json = (JSONObject) parser.parse(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    				Location fileLoc = new Location(Bukkit.getServer().getWorld((String) json.get("sign world")), ((Number) json.get("sign xPos")).doubleValue(),((Number) json.get("sign yPos")).doubleValue(),((Number) json.get("sign zPos")).doubleValue());
    				if(!loc.equals(fileLoc)) continue;
    				file.delete();
    			} catch (Exception ex) {
    				ex.printStackTrace();
    			}
            }
        }
    }
	
	public void saveShop(ShopSign shop) {
    		try {
                // Collect every value before creating the file, so a bad shop leaves no empty file behind.
                HashMap<String, Object> defaults = new HashMap<String, Object>();
            	defaults.put("sign world", shop.getSignLoc().getWorld().toString().replace("CraftWorld{name=", "").replace("}", ""));
            	defaults.put("sign xPos", shop.getSignLoc().getX());
            	defaults.put("sign yPos", shop.getSignLoc().getY());
            	defaults.put("sign zPos", shop.getSignLoc().getZ());
            	
            	defaults.put("storage world", shop.getStorageLoc().getWorld().toString().replace("CraftWorld{name=", "").replace("}", ""));
            	defaults.put("storage xPos", shop.getStorageLoc().getX());
            	defaults.put("storage yPos", shop.getStorageLoc().getY());
            	defaults.put("storage zPos", shop.getStorageLoc().getZ());
            	
            	defaults.put("barter amount", shop.getBarterAmount());
            	defaults.put("price", shop.getPrice());
            	defaults.put("payment item", shop.getPaymentItemString());
            	defaults.put(OWNER, shop.getOwnerAccount());
            	defaults.put(OWNER_CHARACTER, shop.getOwnerCharacter());
            	defaults.put("type", shop.getType());
            	dataFolder.mkdirs();
    			UUID uuid = UUID. randomUUID();
    			String uuidAsString = uuid. toString();
                File file = new File(dataFolder,uuidAsString+".json");
    			while(file.exists() == true) {
    				UUID newuuid = UUID. randomUUID();
        			uuidAsString = newuuid.toString();
                file = new File(dataFolder,uuidAsString+".json");
    			}
            	// A new shop has no stored values, so save() writes the defaults above.
            	json = new JSONObject();
            	if(save(file, defaults)) shop.setShopId(uuidAsString);
            	else file.delete();
            } catch (Throwable ex) {
				ex.printStackTrace();
            }
    	}
    
    @SuppressWarnings("unchecked")
    public boolean save(File file, HashMap<String, Object> defaults) {
      try {
    	  JSONObject toSave = new JSONObject();
      
        for (String s : defaults.keySet()) {
          Object o = defaults.get(s);
          if (o instanceof String) {
            toSave.put(s, getString(s, defaults));
          } else if (o instanceof Double) {
            toSave.put(s, getDouble(s, defaults));
          } else if (o instanceof Integer) {
            toSave.put(s, getInteger(s, defaults));
          } else if (o instanceof JSONObject) {
            toSave.put(s, getObject(s, defaults));
          } else if (o instanceof JSONArray) {
            toSave.put(s, getArray(s, defaults));
          }
        }
      
        TreeMap<String, Object> treeMap = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
        treeMap.putAll(toSave);
      
       Gson g = new GsonBuilder().setPrettyPrinting().create();
       String prettyJsonString = g.toJson(treeMap);
      
        DurableFiles.write(file.toPath(), prettyJsonString);
      
        return true;
      } catch (Exception ex) {
        ex.printStackTrace();
        return false;
      }
    }
    
    public String getRawData(String key, HashMap<String, Object> defaults) {
        return json.containsKey(key) ? json.get(key).toString()
           : (defaults.containsKey(key) ? defaults.get(key).toString() : key);
      }
    
      // Keep the existing legacy text representation, formatting, and exact-string comparisons.
      @SuppressWarnings("deprecation")
      public String getString(String key, HashMap<String, Object> defaults) {
        return ChatColor.translateAlternateColorCodes('&', getRawData(key, defaults));
      }

      public boolean getBoolean(String key, HashMap<String, Object> defaults) {
        return Boolean.valueOf(getRawData(key, defaults));
      }

      public double getDouble(String key, HashMap<String, Object> defaults) {
        try {
          return Double.parseDouble(getRawData(key, defaults));
        } catch (Exception ex) { }
        return -1;
      }

      public double getInteger(String key, HashMap<String, Object> defaults) {
        try {
          return Integer.parseInt(getRawData(key, defaults));
        } catch (Exception ex) { }
        return -1;
      }
     
      public JSONObject getObject(String key, HashMap<String, Object> defaults) {
         return json.containsKey(key) ? (JSONObject) json.get(key)
           : (defaults.containsKey(key) ? (JSONObject) defaults.get(key) : new JSONObject());
      }
     
      public JSONArray getArray(String key, HashMap<String, Object> defaults) {
    	     return json.containsKey(key) ? (JSONArray) json.get(key)
    	       : (defaults.containsKey(key) ? (JSONArray) defaults.get(key) : new JSONArray());
      }

}
