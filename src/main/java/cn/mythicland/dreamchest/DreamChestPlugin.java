package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.bootstrap.DreamLibManagedPlugin;


/**
 * Minimal Bukkit entry point for the Lib-managed DreamChest component graph.
 */
public final class DreamChestPlugin extends DreamLibManagedPlugin {

    private static final String COMPONENT_PACKAGE = "cn.mythicland.dreamchest";

    public DreamChestPlugin() {
        super(COMPONENT_PACKAGE);
    }

    /**
     * Reloads DreamChest configuration through the injected lifecycle graph.
     */
    public void reloadDreamChest() {
        bootstrap(COMPONENT_PACKAGE).reload();
    }
}
