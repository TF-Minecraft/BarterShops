package net.tfminecraft.bartershops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;

class ShopMainTest {
    @TempDir Path dataFolder;

    @Test
    void enablingRegistersTheShopListenerAndPublishesThePlugin() throws Exception {
        Server server = mock(Server.class);
        PluginManager plugins = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(plugins);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             PluginLoader loader = new PluginLoader(server, dataFolder.toFile())) {
            bukkit.when(Bukkit::getUnsafe).thenReturn(mock(UnsafeValues.class));
            Class<?> main = loader.loadClass(ShopMain.class.getName());
            JavaPlugin plugin = (JavaPlugin) main.getDeclaredConstructor().newInstance();

            plugin.onEnable();

            assertSame(plugin, main.getField("plugin").get(null));
            verify(plugins).registerEvents(any(ShopEvents.class), same(plugin));
        }
    }

    /**
     * JavaPlugin only accepts instances defined by a Paper plugin class loader. This one defines
     * ShopMain itself and delegates everything else, so the listener and API types stay shared.
     */
    private static final class PluginLoader extends ClassLoader implements ConfiguredPluginClassLoader {
        private final Server server;
        private final File dataFolder;
        private JavaPlugin plugin;

        PluginLoader(Server server, File dataFolder) {
            super(ShopMainTest.class.getClassLoader());
            this.server = server;
            this.dataFolder = dataFolder;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(ShopMain.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] bytes = in.readAllBytes();
                    // Keep ShopMain's code source so the coverage agent still instruments this copy.
                    return defineClass(name, bytes, 0, bytes.length, ShopMain.class.getProtectionDomain());
                } catch (Exception ex) {
                    throw new ClassNotFoundException(name, ex);
                }
            }
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries)
                throws ClassNotFoundException {
            return loadClass(name, resolve);
        }

        @Override
        public void init(JavaPlugin plugin) {
            this.plugin = plugin;
            // JavaPlugin.init needs Paper's server-side PluginLoader service, so set only the
            // fields ShopMain reaches: getServer(), and getConfig()'s file and resource lookup.
            set(plugin, "server", server);
            set(plugin, "dataFolder", dataFolder);
            set(plugin, "configFile", new File(dataFolder, "config.yml"));
            set(plugin, "classLoader", this);
        }

        private static void set(JavaPlugin plugin, String name, Object value) {
            try {
                Field field = JavaPlugin.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(plugin, value);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public PluginMeta getConfiguration() {
            return null;
        }

        @Override
        public JavaPlugin getPlugin() {
            return plugin;
        }

        @Override
        public PluginClassLoaderGroup getGroup() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
