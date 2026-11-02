package cn.mythicland.dreamchest;

import cn.mythicland.dreamchest.api.DreamChestKey;
import cn.mythicland.dreamchest.api.DreamChestSize;
import cn.mythicland.framework.bukkit.admin.AdminPanelService;
import cn.mythicland.framework.bukkit.container.ContainerAnimationHandle;
import cn.mythicland.framework.bukkit.container.ContainerAnimationService;
import cn.mythicland.framework.bukkit.hologram.FloatingTextService;
import cn.mythicland.framework.bukkit.menu.MenuManager;
import cn.mythicland.framework.bukkit.task.PluginTaskScope;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers private claim views and per-view inventory identity.
 */
class DreamChestNativeInventoryTest {

    @Test
    void enteringEditorKeepsScreenOpenAndRetainsTheSessionAfterManagementCloses() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("dreamchest.admin")).thenReturn(true);
        DreamChestKey key = new DreamChestKey(UUID.randomUUID(), 1, 64, 2);
        DreamChestStore store = mock(DreamChestStore.class);
        DreamChestRecord record = mock(DreamChestRecord.class);
        when(store.find(key)).thenReturn(record);
        when(record.refreshEnabled()).thenReturn(true);
        when(record.editing()).thenReturn(true);
        when(record.size()).thenReturn(DreamChestSize.SMALL);
        when(record.draftCopy()).thenReturn(DreamChestRecord.emptyContents());
        MenuManager menus = mock(MenuManager.class);
        when(menus.hasOpenMenu(playerId)).thenReturn(true);
        ContainerAnimationService animations = mock(ContainerAnimationService.class);
        when(animations.open(any(), eq(player), any())).thenReturn(mock(ContainerAnimationHandle.class));
        DreamChestService service = new DreamChestService(
                mock(PluginTaskScope.class), menus, mock(AdminPanelService.class), animations,
                mock(FloatingTextService.class), mock(DreamChestSettings.class), store, Logger.getAnonymousLogger()
        );
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(27);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        doAnswer(call -> {
            service.onManagementClosed(player, key);
            return view;
        }).when(player).openInventory(inventory);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), anyString()))
                    .thenReturn(inventory);

            service.openEditor(player, key, mock(Block.class));

            verify(player).openInventory(inventory);
            verify(player, never()).closeInventory();
            verify(menus, never()).close(player);
            PlayerTeleportEvent teleport = mock(PlayerTeleportEvent.class);
            when(teleport.getPlayer()).thenReturn(player);
            service.onTeleport(teleport);
            verify(menus).close(player);
            verify(player).closeInventory();
        }
    }

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
