package cn.mythicland.dreamchest.api;

/**
 * Virtual inventory sizes supported by DreamChest.
 */
public enum DreamChestSize {
    /**
     * 27-slot small chest.
     */
    SMALL(27),
    /**
     * 54-slot large chest.
     */
    LARGE(54);

    private final int slots;

    DreamChestSize(int slots) {
        this.slots = slots;
    }

    /**
     * Returns the visible virtual inventory size.
     *
     * @return slot count
     */
    public int slots() {
        return slots;
    }
}
