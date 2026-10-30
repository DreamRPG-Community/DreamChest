package cn.mythicland.dreamchest;

import cn.mythicland.dreamchest.api.DreamChestItemSyncDecision;
import cn.mythicland.dreamchest.api.DreamChestItemSyncStatus;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DreamChestItemSyncModelTest {

    @Test
    void syncDecisionDetachesReplacement() {
        ItemStack replacement = new ItemStack(Material.DIAMOND, 4);
        DreamChestItemSyncDecision decision = new DreamChestItemSyncDecision(
                DreamChestItemSyncStatus.UPDATED,
                replacement
        );

        assertTrue(decision.changed());
        assertNotSame(replacement, decision.replacement());

        decision.replacement().setAmount(1);
        assertTrue(decision.replacement().getAmount() == 4);
    }

    @Test
    void updatedDecisionCannotOmitReplacement() {
        assertThrows(IllegalArgumentException.class, () -> new DreamChestItemSyncDecision(
                DreamChestItemSyncStatus.UPDATED,
                null
        ));
    }

    @Test
    void unchangedDecisionCannotCarryReplacement() {
        assertThrows(IllegalArgumentException.class, () -> new DreamChestItemSyncDecision(
                DreamChestItemSyncStatus.CURRENT,
                new ItemStack(Material.STONE)
        ));
    }
}
