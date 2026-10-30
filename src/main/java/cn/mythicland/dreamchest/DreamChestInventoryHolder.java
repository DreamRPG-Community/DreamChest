package cn.mythicland.dreamchest;

import cn.mythicland.dreamchest.api.DreamChestKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one player-specific native DreamChest inventory.
 *
 * <p>A new holder and a new Bukkit inventory are created for every viewer. The holder therefore
 * also prevents an inventory instance from being accidentally reused by another player.</p>
 */
final class DreamChestInventoryHolder implements InventoryHolder {

    private final DreamChestKey key;
    private final UUID viewerUniqueId;
    private final boolean editor;
    private Inventory inventory;

    DreamChestInventoryHolder(DreamChestKey key, UUID viewerUniqueId, boolean editor) {
        this.key = Objects.requireNonNull(key, "key");
        this.viewerUniqueId = Objects.requireNonNull(viewerUniqueId, "viewerUniqueId");
        this.editor = editor;
    }

    DreamChestKey key() {
        return key;
    }

    UUID viewerUniqueId() {
        return viewerUniqueId;
    }

    boolean editor() {
        return editor;
    }

    void attach(Inventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        if (this.inventory != null) throw new IllegalStateException("DreamChest inventory already attached");
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
