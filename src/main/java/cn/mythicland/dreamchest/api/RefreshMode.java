package cn.mythicland.dreamchest.api;

/**
 * Scheduling mode for one DreamChest.
 */
public enum RefreshMode {
    /**
     * Refreshes after a configured interval.
     */
    INTERVAL,
    /**
     * Refreshes at a configured local time every day.
     */
    DAILY,
    /**
     * Keeps refreshing from the template whenever the one-second service tick is due.
     */
    ALWAYS,
    /**
     * Never refreshes automatically.
     */
    NEVER
}
