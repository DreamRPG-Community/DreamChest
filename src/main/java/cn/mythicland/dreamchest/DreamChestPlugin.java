package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.api.DreamLibApi;
import cn.mythicland.dreamlib.bootstrap.PluginBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Minimal Bukkit entry point for the Lib-managed DreamChest component graph.
 */
public final class DreamChestPlugin extends JavaPlugin {

    private static final String COMPONENT_PACKAGE = "cn.mythicland.dreamchest";

    private PluginBootstrap bootstrap;

    @Override
    @SuppressWarnings("resource")
    public void onEnable() {
        try {
            DreamLibApi lib = DreamLibApi.require(this);
            bootstrap = lib.createPluginBootstrap(this, COMPONENT_PACKAGE);
            bootstrap.enable();
        } catch (RuntimeException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "DreamChest failed to enable: " + DreamLibApi.rootCauseMessage(exception),
                    exception
            );
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (bootstrap != null) bootstrap.disable();
        bootstrap = null;
    }

    /**
     * Reloads DreamChest configuration through the injected lifecycle graph.
     */
    public void reloadDreamChest() {
        if (bootstrap == null) throw new IllegalStateException("DreamChest bootstrap is unavailable");
        bootstrap.reload();
    }
}
