package cn.mythicland.dreamchest.api;

import org.bukkit.inventory.ItemStack;

/**
 * External item refresh callback used by DreamChest without coupling it to a specific item
 * provider.
 */
@FunctionalInterface
public interface DreamChestItemReconciler {

    /**
     * Inspects one existing stored stack. The implementation must not mutate the input stack.
     *
     * @param item item stored in DreamChest
     * @param mode amount policy
     * @return detached synchronization decision
     * @throws RuntimeException if the external item source cannot determine a decision
     */
    DreamChestItemSyncDecision reconcile(ItemStack item, DreamChestItemSyncMode mode);
}
