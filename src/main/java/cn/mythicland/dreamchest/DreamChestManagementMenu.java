package cn.mythicland.dreamchest;

import cn.mythicland.dreamchest.api.DreamChestKey;
import cn.mythicland.dreamchest.api.RefreshMode;
import cn.mythicland.dreamchest.api.RefreshPolicy;
import cn.mythicland.framework.bukkit.menu.*;
import cn.mythicland.framework.bukkit.menu.ClickType;
import cn.mythicland.framework.bukkit.text.LegacyText;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Administrator configuration menu for one DreamChest.
 *
 * <p>The layout follows MythicThePit's settings convention: each setting is one item, and its
 * lore contains the complete option list. The selected option is green with a triangle marker;
 * other options remain gray. Empty slots intentionally stay empty.</p>
 */
final class DreamChestManagementMenu extends AbstractMenu {

    private static final int REFRESH_ENABLED_SLOT = 10;
    private static final int SIZE_SLOT = 11;
    private static final int MODE_SLOT = 12;
    private static final int TIME_SLOT = 13;
    private static final int PARTICLE_SLOT = 14;
    private static final int HOLOGRAM_SLOT = 15;
    private static final int EDIT_SLOT = 16;
    private static final int BACK_SLOT = 26;
    private static final short GRAY_DYE_DATA = 8;
    private static final short LIME_DYE_DATA = 10;

    private final DreamChestService service;
    private final DreamChestKey key;
    private boolean closed;

    DreamChestManagementMenu(
            DreamChestService service,
            DreamChestKey key
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.key = Objects.requireNonNull(key, "key");
    }

    private static int selectedIndex(List<String> options, String selected) {
        int index = options.indexOf(selected);
        return Math.max(index, 0);
    }

    private static List<String> displayOptions(List<String> options) {
        return options.stream().map(DreamChestManagementMenu::displayOption).toList();
    }

    private static String displayOption(String option) {
        if ("custom".equalsIgnoreCase(option)) return "自定义";
        if ("NONE".equalsIgnoreCase(option)) return "无";
        return option;
    }

    @Override
    public String buildTitle(Player player) {
        return LegacyText.colorize("&8DreamChest 管理");
    }

    @Override
    protected int resolveRowCount(Player player) {
        return 3;
    }

    @MenuSlot(slot = REFRESH_ENABLED_SLOT)
    private AbstractButton refreshEnabledButton(Player viewer) {
        ItemStack icon = renderRefreshEnabledButton(viewer);
        return MenuButtons.selection(icon, (player, click, menus) -> {
            toggleRefreshEnabled(player);
            return true;
        });
    }

    private ItemStack renderRefreshEnabledButton(Player player) {
        DreamChestRecord record = service.recordForMenu(key);
        return MenuSelection.item(
                Material.INK_SACK,
                record.refreshEnabled() ? LIME_DYE_DATA : GRAY_DYE_DATA,
                "&a箱子刷新",
                "选择是否启用该箱子的刷新功能",
                List.of("启用", "关闭"),
                record.refreshEnabled() ? 0 : 1,
                List.of()
        );
    }

    @MenuSlot(slot = SIZE_SLOT)
    private AbstractButton sizeButton(Player viewer) {
        ItemStack icon = renderSizeButton(viewer);
        return MenuButtons.selection(icon, (player, click, menus) -> toggleSize(player));
    }

    private ItemStack renderSizeButton(Player player) {
        DreamChestRecord record = service.recordForMenu(key);
        return MenuSelection.item(
                Material.CHEST,
                "&a箱子大小",
                "选择虚拟箱子的容量",
                List.of("小箱子", "大箱子"),
                record.size().slots() == 27 ? 0 : 1,
                List.of()
        );
    }

    @MenuSlot(slot = MODE_SLOT)
    private AbstractButton refreshModeButton(Player viewer) {
        ItemStack icon = renderRefreshModeButton(viewer);
        return MenuButtons.selection(icon, (player, click, menus) -> {
            cycleRefreshMode(player, click);
            return true;
        });
    }

    private ItemStack renderRefreshModeButton(Player player) {
        DreamChestRecord record = service.recordForMenu(key);
        return MenuSelection.item(
                Material.WATCH,
                "&a刷新模式",
                "选择箱子的刷新方式",
                List.of("定时刷新", "固定时间刷新", "始终刷新", "永不刷新"),
                record.refreshMode().ordinal(),
                List.of()
        );
    }

    @MenuSlot(slot = TIME_SLOT)
    private Optional<AbstractButton> refreshTimeButton(Player viewer) {
        ItemStack icon = renderRefreshTimeButton(viewer);
        if (icon == null) return Optional.empty();
        return Optional.of(MenuButtons.selection(icon, (player, click, menus) -> cycleRefreshTime(player, click)));
    }

    private ItemStack renderRefreshTimeButton(Player player) {
        DreamChestRecord record = service.recordForMenu(key);
        DreamChestSettings.Snapshot settings = service.settingsForMenu();
        if (record.refreshMode() == RefreshMode.INTERVAL) {
            return MenuSelection.item(
                    Material.WATCH,
                    "&a间隔刷新时间",
                    "选择定时刷新的间隔",
                    displayOptions(settings.intervalOptions()),
                    selectedIndex(settings.intervalOptions(), record.intervalOption()),
                    List.of("&7自定义值: &f" + RefreshPolicy.formatInterval(settings.customInterval()))
            );
        }
        if (record.refreshMode() == RefreshMode.DAILY) {
            return MenuSelection.item(
                    Material.REDSTONE,
                    "&a每日刷新时间",
                    "选择每日固定刷新的时间",
                    displayOptions(settings.dailyOptions()),
                    selectedIndex(settings.dailyOptions(), record.dailyOption()),
                    List.of("&7自定义值: &f" + settings.customDailyTime())
            );
        }
        return null;
    }

    @MenuSlot(slot = PARTICLE_SLOT)
    private AbstractButton particleButton(Player viewer) {
        ItemStack icon = renderParticleButton(viewer);
        return MenuButtons.selection(icon, (player, click, menus) -> {
            cycleParticle(player, click);
            return true;
        });
    }

    private ItemStack renderParticleButton(Player player) {
        DreamChestRecord record = service.recordForMenu(key);
        DreamChestSettings.Snapshot settings = service.settingsForMenu();
        return MenuSelection.item(
                Material.BLAZE_POWDER,
                "&a可领取粒子",
                "选择箱子可领取时的粒子效果",
                displayOptions(settings.particleOptions()),
                selectedIndex(settings.particleOptions(), record.particle()),
                List.of()
        );
    }

    @MenuSlot(slot = HOLOGRAM_SLOT)
    private AbstractButton hologramButton(Player viewer) {
        ItemStack icon = renderHologramButton(viewer);
        return MenuButtons.selection(icon, (player, click, menus) -> {
            toggleHologram(player);
            return true;
        });
    }

    private ItemStack renderHologramButton(Player player) {
        DreamChestRecord record = service.recordForMenu(key);
        return MenuSelection.item(
                Material.NAME_TAG,
                "&a悬浮字倒计时",
                "选择是否显示箱子上方的倒计时",
                List.of("开启", "关闭"),
                record.hologramEnabled() ? 0 : 1,
                List.of()
        );
    }

    @MenuSlot(slot = EDIT_SLOT)
    private AbstractButton editButton(Player viewer) {
        ItemStack icon = renderEditButton(viewer);
        return MenuButtons.selection(icon, (player, click, menus) -> toggleEditing(player));
    }

    private ItemStack renderEditButton(Player player) {
        DreamChestRecord record = service.recordForMenu(key);
        return MenuItems.button(
                record.editing() ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK,
                record.editing() ? "&a退出编辑模式" : "&a进入编辑模式",
                record.editing()
                        ? List.of("&7保存当前草稿并立即刷新箱子")
                        : List.of("&7打开虚拟编辑库存")
        );
    }

    @MenuSlot(slot = BACK_SLOT)
    private Optional<AbstractButton> backButton(Player viewer) {
        ItemStack icon = renderBackButton(viewer);
        if (icon == null) return Optional.empty();
        return Optional.of(MenuButtons.action(icon, (player, click, menus) -> {
            back(player);
            return true;
        }));
    }

    private ItemStack renderBackButton(Player player) {
        if (!service.hasMultipleAdminPanels(player, key)) return null;
        return MenuItems.button(Material.ARROW, "&e返回总管理面板", List.of(
                "&7返回总管理面板"
        ));
    }

    private void toggleRefreshEnabled(Player player) {
        service.toggleRefreshEnabled(player, key);
    }

    private boolean toggleSize(Player player) {
        return service.toggleSize(player, key);
    }

    private void cycleRefreshMode(Player player, ClickType click) {
        service.cycleRefreshMode(player, key, click == ClickType.LEFT);
    }

    private boolean cycleRefreshTime(Player player, ClickType click) {
        boolean next = click == ClickType.LEFT;
        RefreshMode mode = service.recordForMenu(key).refreshMode();
        if (mode == RefreshMode.INTERVAL) {
            service.cycleInterval(player, key, next);
            return true;
        }
        if (mode == RefreshMode.DAILY) {
            service.cycleDaily(player, key, next);
            return true;
        }
        return false;
    }

    private void cycleParticle(Player player, ClickType click) {
        service.cycleParticle(player, key, click == ClickType.LEFT);
    }

    private void toggleHologram(Player player) {
        service.toggleHologram(player, key);
    }

    private boolean toggleEditing(Player player) {
        return service.recordForMenu(key).editing()
                ? service.exitEditing(player, key)
                : service.enterEditing(player, key);
    }

    private void back(Player player) {
        if (!service.hasMultipleAdminPanels(player, key)) return;
        service.openAdminOverview(player, key);
    }

    @Override
    public boolean replacesInPlace() {
        return true;
    }

    @Override
    public boolean handlesClicksAtHighestPriority() {
        return true;
    }

    @Override
    public void onClose(Player player, Inventory inventory) {
        finish(player);
    }

    @Override
    public void onQuit(Player player, Inventory inventory) {
        finish(player);
    }

    private void finish(Player player) {
        if (closed) return;
        closed = true;
        service.onManagementClosed(player, key);
    }
}
