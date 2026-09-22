package net.tfminecraft.bartershops;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Database {
	private JSONObject json; // org.json.simple
    JSONParser parser = new JSONParser();
	public ShopSign getShopFromLoc(Location loc) {
    	File folder = new File("plugins/BarterShops/Data");
    	for (final File file : folder.listFiles()) {
            if (!file.isDirectory()) {
            	try {
    				json = (JSONObject) parser.parse(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    				Location fileLoc = new Location(Bukkit.getServer().getWorld((String) json.get("sign world")), (Double) json.get("sign xPos"),(Double) json.get("sign yPos"),(Double) json.get("sign zPos"));
    				if(!loc.equals(fileLoc)) continue;
    				Location storageLoc = new Location(Bukkit.getServer().getWorld((String) json.get("storage world")), (Double) json.get("storage xPos"),(Double) json.get("storage yPos"),(Double) json.get("storage zPos"));
    				ShopSign shop = new ShopSign();
    				shop.setSignLoc(fileLoc);
    				shop.setStorageLoc(storageLoc);
    				shop.setBarterAmount((int) Math.round((Double) json.get("barter amount")));
    				shop.setPrice((int) Math.round((Double) json.get("price")));
    				shop.setOwner((String) json.get("owner"));
    				shop.setPaymentItem((String) json.get("payment item"));
    				shop.setType((String) json.get("type"));
    				return shop;
    			} catch (Exception ex) {
    				ex.printStackTrace();
    			}
            }
        }
    	return null;
    }
	
	public Boolean shopExistsFromLoc(Location loc) {
    	File folder = new File("plugins/BarterShops/Data");
    	for (final File file : folder.listFiles()) {
            if (!file.isDirectory()) {
            	try {
    				json = (JSONObject) parser.parse(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    				Location fileLoc = new Location(Bukkit.getServer().getWorld((String) json.get("sign world")), (Double) json.get("sign xPos"),(Double) json.get("sign yPos"),(Double) json.get("sign zPos"));
    				if(!loc.equals(fileLoc)) continue;
    				return true;
    			} catch (Exception ex) {
    				ex.printStackTrace();
    			}
            }
        }
    	return false;
    }
	
	public void deleteFile(Location loc) {
    	File folder = new File("plugins/BarterShops/Data");
    	for (final File file : folder.listFiles()) {
            if (!file.isDirectory()) {
            	try {
    				json = (JSONObject) parser.parse(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    				Location fileLoc = new Location(Bukkit.getServer().getWorld((String) json.get("sign world")), (Double) json.get("sign xPos"),(Double) json.get("sign yPos"),(Double) json.get("sign zPos"));
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
    			UUID uuid = UUID. randomUUID();
    			String uuidAsString = uuid. toString();
    			File file = new File("plugins/BarterShops/Data",uuidAsString+".json");
    			while(file.exists() == true) {
    				UUID newuuid = UUID. randomUUID();
        			uuidAsString = newuuid.toString();
        			file = new File("plugins/BarterShops/Data",uuidAsString+".json");
    			}
    			file.createNewFile();
            	PrintWriter pw = new PrintWriter(file, "UTF-8");
            	pw.print("{");
            	pw.print("}");
            	pw.flush();
            	pw.close();
                HashMap<String, Object> defaults = new HashMap<String, Object>();
            	json = (JSONObject) parser.parse(new InputStreamReader(new FileInputStream(file), "UTF-8"));
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
            	defaults.put("owner", shop.getOwner());
            	defaults.put("type", shop.getType());
            	save(file, defaults);
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
      
        FileWriter fw = new FileWriter(file);
        fw.write(prettyJsonString);
        fw.flush();
        fw.close();
      
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
