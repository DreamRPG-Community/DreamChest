package cn.mythicland.dreamchest.api;

/**
 * Main-thread service for reconciling stored DreamChest items with an external item source.
 */
public interface DreamChestItemSyncApi {

    /**
     * Reconciles templates, drafts, live contents, and open native interfaces atomically from
     * DreamChest's point of view.
     *
     * @param reconciler external item identity and refresh logic
     * @return synchronization statistics
     * @throws NullPointerException  if reconciler is null
     * @throws IllegalStateException if called off the Bukkit primary thread or the DreamChest state
     *                               cannot be updated atomically
     */
    DreamChestItemSyncReport synchronize(DreamChestItemReconciler reconciler);
}
