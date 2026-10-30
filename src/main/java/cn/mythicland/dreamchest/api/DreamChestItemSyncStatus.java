package cn.mythicland.dreamchest.api;

/**
 * Result category returned by a DreamChest item reconciler.
 */
public enum DreamChestItemSyncStatus {
    UNMANAGED,
    CURRENT,
    UPDATED,
    STALE
}
