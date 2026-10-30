package cn.mythicland.dreamchest.api;

/**
 * Amount policy requested by an external DreamChest item synchronizer.
 */
public enum DreamChestItemSyncMode {
    /**
     * The item is a template and should use the amount from the current source definition.
     */
    TEMPLATE,
    /**
     * The item is an existing stored instance; preserve its current amount.
     */
    EXISTING_INSTANCE
}
