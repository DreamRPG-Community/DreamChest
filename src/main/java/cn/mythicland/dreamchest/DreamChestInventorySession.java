package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.container.ContainerAnimationHandle;
import org.bukkit.inventory.Inventory;

import java.util.Objects;

/**
 * Tracks one private native inventory session.
 */
final class DreamChestInventorySession {

    private final DreamChestInventoryHolder holder;
    private ContainerAnimationHandle animation;

    DreamChestInventorySession(DreamChestInventoryHolder holder) {
        this.holder = Objects.requireNonNull(holder, "holder");
        if (holder.getInventory() == null) throw new IllegalArgumentException("holder has no inventory");
    }

    DreamChestInventoryHolder holder() {
        return holder;
    }

    Inventory inventory() {
        return holder.getInventory();
    }

    boolean editor() {
        return holder.editor();
    }

    void attachAnimation(ContainerAnimationHandle animation) {
        if (this.animation != null) throw new IllegalStateException("DreamChest animation already attached");
        this.animation = Objects.requireNonNull(animation, "animation");
    }

    void closeAnimation() {
        if (animation != null) animation.close();
        animation = null;
    }
}
