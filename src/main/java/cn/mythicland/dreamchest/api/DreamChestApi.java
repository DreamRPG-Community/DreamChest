package cn.mythicland.dreamchest.api;

import java.util.Collection;
import java.util.Optional;

/**
 * Read-only Bukkit service for querying DreamChest runtime state.
 *
 * <p>All methods are intended for the Bukkit primary thread. Returned snapshots are detached and
 * cannot mutate the plugin's inventory, configuration, or storage.</p>
 */
public interface DreamChestApi {

    /**
     * Finds a managed chest by its world-coordinate key.
     *
     * @param key chest identity
     * @return snapshot when managed
     */
    Optional<DreamChestSnapshot> find(DreamChestKey key);

    /**
     * Returns all currently managed chests.
     *
     * @return immutable snapshots
     */
    Collection<DreamChestSnapshot> snapshots();
}
