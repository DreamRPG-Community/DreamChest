package cn.mythicland.dreamchest;

import cn.mythicland.framework.bukkit.content.ContentUpdate;
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

    /** Reloads editable chest definitions, preserving persisted player claim contents. */
    public void reloadContent() {
        prepareContentReload().apply().run();
    }

    /** @return validated chest template update, preserving personal claim state */
    public ContentUpdate prepareContentReload() {
        return bootstrap(COMPONENT_PACKAGE).resolve(DreamChestService.class).prepareContentReload();
    }
}
