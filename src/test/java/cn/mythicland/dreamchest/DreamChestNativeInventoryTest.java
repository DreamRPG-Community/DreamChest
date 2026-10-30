package cn.mythicland.dreamchest;

import cn.mythicland.dreamchest.api.DreamChestKey;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers private claim views and per-view inventory identity.
 */
class DreamChestNativeInventoryTest {

    @Test
    void eachClaimViewerReceivesAnIndependentContentsCopy() {
        ItemStack[] source = DreamChestRecord.emptyContents();
        source[0] = new ItemStack(Material.DIAMOND, 10);

        ItemStack[] firstViewer = DreamChestService.viewContents(source, 27);
        ItemStack[] secondViewer = DreamChestService.viewContents(source, 27);
        firstViewer[0].setAmount(1);
        firstViewer[1] = new ItemStack(Material.GOLD_INGOT, 1);

        assertEquals(10, source[0].getAmount());
        assertEquals(10, secondViewer[0].getAmount());
        assertEquals(Material.GOLD_INGOT, firstViewer[1].getType());
        assertNull(secondViewer[1]);
    }

    @Test
    void eachViewerGetsASeparateInventoryHolder() {
        DreamChestKey key = new DreamChestKey(UUID.randomUUID(), 1, 64, 2);
        DreamChestInventoryHolder first = new DreamChestInventoryHolder(key, UUID.randomUUID(), false);
        DreamChestInventoryHolder second = new DreamChestInventoryHolder(key, UUID.randomUUID(), false);

        assertNotSame(first, second);
        assertNotSame(first.viewerUniqueId(), second.viewerUniqueId());
    }
}
