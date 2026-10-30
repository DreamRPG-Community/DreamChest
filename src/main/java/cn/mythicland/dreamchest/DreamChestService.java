package cn.mythicland.dreamchest;

import cn.mythicland.dreamchest.api.*;
import cn.mythicland.dreamlib.admin.AdminPanelProvider;
import cn.mythicland.dreamlib.admin.AdminPanelRegistration;
import cn.mythicland.dreamlib.admin.AdminPanelService;
import cn.mythicland.dreamlib.bootstrap.DreamLibPluginLifecycle;
import cn.mythicland.dreamlib.bootstrap.PluginTaskScope;
import cn.mythicland.dreamlib.bootstrap.annotation.LifecycleComponent;
import cn.mythicland.dreamlib.bootstrap.annotation.ListenerComponent;
import cn.mythicland.dreamlib.bootstrap.annotation.ServiceComponent;
import cn.mythicland.dreamlib.container.ContainerAnimationService;
import cn.mythicland.dreamlib.container.ContainerAnimationSpec;
import cn.mythicland.dreamlib.menu.MenuService;
import cn.mythicland.dreamlib.text.FloatingTextHandle;
import cn.mythicland.dreamlib.text.FloatingTextService;
import cn.mythicland.dreamlib.text.FloatingTextSpec;
import cn.mythicland.dreamlib.text.LegacyText;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main-thread DreamChest domain service.
 */
@LifecycleComponent
@ListenerComponent
@ServiceComponent(DreamChestApi.class)
public final class DreamChestService implements DreamChestApi, Listener, DreamLibPluginLifecycle {

    private static final String ADMIN_PERMISSION = "dreamchest.admin";
    private static final String RELOAD_PERMISSION = "dreamchest.reload";

    private final PluginTaskScope tasks;
    private final MenuService menus;
    private final AdminPanelService adminPanels;
    private final ContainerAnimationService animations;
    private final FloatingTextService floatingText;
    private final DreamChestSettings settings;
    private final DreamChestStore store;
    private final Logger logger;
    private final Map<UUID, DreamChestKey> openSessions = new HashMap<>();
    private final Map<UUID, DreamChestInventorySession> nativeInventorySessions = new HashMap<>();
    private final Map<DreamChestKey, Set<UUID>> managementViewers = new HashMap<>();
    private final Map<DreamChestKey, Set<UUID>> claimViewers = new HashMap<>();
    private final Map<DreamChestKey, FloatingTextHandle> holograms = new HashMap<>();
    private final Map<DreamChestKey, FloatingTextHandle> emptyHolograms = new HashMap<>();
    private final Map<DreamChestKey, FloatingTextSpec> hologramSpecifications = new HashMap<>();
    private final Map<DreamChestKey, Location> hologramLocations = new HashMap<>();
    private final Set<DreamChestInventorySession> pendingNativeMutations =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private BukkitTask tickTask;
    private BukkitTask particleTask;
    private BukkitTask nativeFlushTask;
    private AdminPanelRegistration adminPanelRegistration;

    /**
     * Creates the injected DreamChest service.
     */
    DreamChestService(
            PluginTaskScope tasks,
            MenuService menus,
            AdminPanelService adminPanels,
            ContainerAnimationService animations,
            FloatingTextService floatingText,
            DreamChestSettings settings,
            DreamChestStore store,
            Logger logger
    ) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.adminPanels = Objects.requireNonNull(adminPanels, "adminPanels");
        this.animations = Objects.requireNonNull(animations, "animations");
        this.floatingText = Objects.requireNonNull(floatingText, "floatingText");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        if (DreamChestRecord.isEmpty(first) || DreamChestRecord.isEmpty(second)) {
            return DreamChestRecord.isEmpty(first) && DreamChestRecord.isEmpty(second);
        }
        return first.getAmount() == second.getAmount() && first.isSimilar(second);
    }

    private static boolean sameAnchor(Location first, Location second) {
        if (first == null || second == null || first.getWorld() == null || second.getWorld() == null) {
            return false;
        }
        return first.getWorld().getUID().equals(second.getWorld().getUID())
                && Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    private static String formatRemaining(DreamChestRecord record, Instant now) {
        if (record.refreshMode() == RefreshMode.ALWAYS) return "始终刷新";
        if (record.refreshMode() == RefreshMode.NEVER) return "永不刷新";
        long seconds = Math.max(0L, record.nextRefreshEpochSecond() - now.getEpochSecond());
        Duration duration = Duration.ofSeconds(seconds);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long remainingSeconds = duration.toSecondsPart();
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
    }

    private static String cycle(List<String> options, String current, boolean next) {
        int index = options.indexOf(current);
        if (index < 0) index = 0;
        int direction = next ? 1 : options.size() - 1;
        return options.get((index + direction) % options.size());
    }

    private static ItemStack[] capture(Inventory inventory) {
        ItemStack[] result = DreamChestRecord.emptyContents();
        ItemStack[] contents = inventory.getContents();
        if (contents.length > DreamChestRecord.STORAGE_SIZE) {
            throw new IllegalStateException("DreamChest editor inventory exceeds 54 slots");
        }
        for (int index = 0; index < contents.length; index++)
            result[index] = contents[index] == null ? null : contents[index].clone();
        return result;
    }

    static ItemStack[] viewContents(ItemStack[] storageContents, int size) {
        Objects.requireNonNull(storageContents, "storageContents");
        if (storageContents.length != DreamChestRecord.STORAGE_SIZE) {
            throw new IllegalArgumentException("storageContents must contain exactly 54 slots");
        }
        if (size < 0 || size > DreamChestRecord.STORAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 0 and 54");
        }
        ItemStack[] result = new ItemStack[size];
        for (int index = 0; index < size; index++) {
            result[index] = storageContents[index] == null ? null : storageContents[index].clone();
        }
        return result;
    }

    private static boolean sameContents(ItemStack[] first, ItemStack[] second) {
        if (first.length != second.length) return false;
        for (int index = 0; index < first.length; index++) {
            if (!sameStack(first[index], second[index])) return false;
        }
        return true;
    }

    static boolean isChest(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    /**
     * Returns the reload permission used by the command component.
     */
    static String reloadPermission() {
        return RELOAD_PERMISSION;
    }

    private static boolean clickCanChangeTop(InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        return rawSlot >= 0 && rawSlot < topSize
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR;
    }

    private static boolean dragCanChangeTop(InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        return event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < topSize);
    }

    @Override
    public void enable() {
        animations.verifyCompatibility();
        floatingText.verifyCompatibility();
        cleanupMissingAnchors();
        adminPanelRegistration = adminPanels.register(new AdminPanelProvider() {
            @Override
            public String id() {
                return "dreamchest";
            }

            @Override
            public String displayName() {
                return "&aDreamChest 箱子";
            }

            @Override
            public Material icon() {
                return Material.CHEST;
            }

            @Override
            public List<String> description() {
                return List.of("&7创建或管理一个魔法箱奖励", "", "&e点击创建或管理!");
            }

            @Override
            public boolean supports(Player player, Block block) {
                return player.hasPermission(ADMIN_PERMISSION) && isChest(block);
            }

            @Override
            public void open(Player player, Block block) {
                openManagement(player, block);
            }
        });
        for (DreamChestRecord record : store.all()) {
            record.refreshPolicy(settings.snapshot());
            if (record.nextRefreshEpochSecond() == 0L) {
                record.recalculateNextRefresh(settings.snapshot(), Instant.now());
            }
        }
        tickTask = tasks.runTimer(1L, 20L, this::tick);
        particleTask = tasks.runTimer(1L, settings.snapshot().particleIntervalTicks(), this::renderParticles);
        tick();
        renderParticles();
    }

    @Override
    public void reload() {
        for (DreamChestRecord record : store.all()) record.refreshPolicy(settings.snapshot());
        tasks.cancel(particleTask);
        particleTask = tasks.runTimer(1L, settings.snapshot().particleIntervalTicks(), this::renderParticles);
        tick();
        renderParticles();
    }

    @Override
    public void disable() {
        if (adminPanelRegistration != null) {
            adminPanelRegistration.close();
            adminPanelRegistration = null;
        }
        tasks.cancel(tickTask);
        tickTask = null;
        tasks.cancel(particleTask);
        particleTask = null;
        cancelNativeFlush();
        flushPendingNativeMutation();
        for (DreamChestInventorySession session : List.copyOf(nativeInventorySessions.values())) {
            finishNativeSession(session);
        }
        for (UUID playerUniqueId : List.copyOf(openSessions.keySet())) {
            Player player = Bukkit.getPlayer(playerUniqueId);
            if (player != null && menus.hasOpenMenu(playerUniqueId)) menus.close(player);
        }
        openSessions.clear();
        managementViewers.clear();
        claimViewers.clear();
        for (FloatingTextHandle handle : List.copyOf(holograms.values())) handle.close();
        for (FloatingTextHandle handle : List.copyOf(emptyHolograms.values())) handle.close();
        holograms.clear();
        emptyHolograms.clear();
        hologramSpecifications.clear();
        hologramLocations.clear();
        persist();
        store.close();
    }

    @Override
    public Optional<DreamChestSnapshot> find(DreamChestKey key) {
        Objects.requireNonNull(key, "key");
        DreamChestRecord record = store.find(key);
        return record == null
                ? Optional.empty()
                : Optional.of(record.snapshot(settings.snapshot(), claimViewerCount(key)));
    }

    @Override
    public Collection<DreamChestSnapshot> snapshots() {
        return store.all().stream()
                .sorted(Comparator.comparing(record -> record.key().encoded()))
                .map(record -> record.snapshot(settings.snapshot(), claimViewerCount(record.key())))
                .toList();
    }

    /**
     * Applies one external item reconciler while keeping template, draft, live contents, and
     * open native inventories consistent. This method is intentionally package-private; callers
     * use DreamChestItemSyncApi through Bukkit's ServicesManager.
     */
    DreamChestItemSyncReport synchronizeItems(DreamChestItemReconciler reconciler) {
        requirePrimaryThread();
        Objects.requireNonNull(reconciler, "reconciler");
        flushPendingNativeMutation();

        List<RecordSync> updates = new ArrayList<>();
        int updatedTemplates = 0;
        int updatedDrafts = 0;
        int updatedLive = 0;
        int updatedOpenInventories = 0;
        int skippedUnmanaged = 0;
        int stale = 0;

        for (DreamChestRecord record : store.all()) {
            ItemStack[] oldTemplate = record.templateCopy();
            ItemStack[] draft = record.draftCopy();
            ItemStack[] live = record.liveCopy();
            ItemStack[] oldDraft = record.draftCopy();
            ItemStack[] oldLive = record.liveCopy();
            Map<UUID, ItemStack[]> oldPlayerContents = record.playerContentsCopy();
            ItemStack[] template = record.templateCopy();
            boolean recordChanged = false;

            for (int slot = 0; slot < DreamChestRecord.STORAGE_SIZE; slot++) {
                ItemStack templateItem = oldTemplate[slot];
                if (!DreamChestRecord.isEmpty(templateItem)) {
                    DreamChestItemSyncDecision decision = Objects.requireNonNull(
                            reconciler.reconcile(templateItem.clone(), DreamChestItemSyncMode.TEMPLATE),
                            "reconciler decision"
                    );
                    if (decision.status() == DreamChestItemSyncStatus.UPDATED && decision.replacement() != null) {
                        template[slot] = decision.replacement();
                        updatedTemplates++;
                        recordChanged = true;
                    } else {
                        SkipCounts counters = countSkipped(decision.status());
                        skippedUnmanaged += counters.unmanaged();
                        stale += counters.stale();
                    }
                }

                // A draft slot is eligible only while it still equals the old template. Any
                // administrator edit, including amount or visible metadata, wins over sync.
                if (!DreamChestRecord.isEmpty(draft[slot]) && sameStack(oldTemplate[slot], draft[slot])) {
                    DreamChestItemSyncDecision decision = Objects.requireNonNull(
                            reconciler.reconcile(draft[slot].clone(), DreamChestItemSyncMode.TEMPLATE),
                            "reconciler decision"
                    );
                    if (decision.status() == DreamChestItemSyncStatus.UPDATED && decision.replacement() != null) {
                        draft[slot] = decision.replacement();
                        updatedDrafts++;
                        recordChanged = true;
                    } else {
                        SkipCounts counters = countSkipped(decision.status());
                        skippedUnmanaged += counters.unmanaged();
                        stale += counters.stale();
                    }
                }

                if (!DreamChestRecord.isEmpty(live[slot])) {
                    DreamChestItemSyncDecision decision = Objects.requireNonNull(
                            reconciler.reconcile(live[slot].clone(), DreamChestItemSyncMode.EXISTING_INSTANCE),
                            "reconciler decision"
                    );
                    if (decision.status() == DreamChestItemSyncStatus.UPDATED && decision.replacement() != null) {
                        live[slot] = decision.replacement();
                        updatedLive++;
                        recordChanged = true;
                    } else {
                        SkipCounts counters = countSkipped(decision.status());
                        skippedUnmanaged += counters.unmanaged();
                        stale += counters.stale();
                    }
                }
            }

            updates.add(new RecordSync(record, oldTemplate, oldDraft, oldLive, oldPlayerContents,
                    template, draft, live, recordChanged));
        }

        try {
            for (RecordSync update : updates) {
                update.record().setTemplate(update.template());
                update.record().setDraft(update.draft());
                update.record().setLiveContents(update.live());
                update.record().reconcilePlayerContents(update.oldLive(), update.live());
            }
            for (RecordSync update : updates) {
                if (update.changed()) {
                    updatedOpenInventories += updateOpenInventories(
                            update.record(), update.live(), update.draft()
                    );
                }
            }

            if (updatedTemplates > 0 || updatedDrafts > 0 || updatedLive > 0 || updatedOpenInventories > 0) {
                persist();
            }
        } catch (RuntimeException exception) {
            rollback(updates, exception);
            throw exception;
        }
        return new DreamChestItemSyncReport(
                updates.size(), updatedTemplates, updatedDrafts, updatedLive,
                updatedOpenInventories, skippedUnmanaged, stale
        );
    }

    private void rollback(List<RecordSync> updates, RuntimeException failure) {
        try {
            for (RecordSync update : updates) {
                update.record().setTemplate(update.oldTemplate());
                update.record().setDraft(update.oldDraft());
                update.record().setLiveContents(update.oldLive());
                update.record().setPlayerContents(update.oldPlayerContents());
            }
            for (RecordSync update : updates) {
                if (update.changed()) updateOpenInventories(
                        update.record(), update.oldLive(), update.oldDraft()
                );
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private SkipCounts countSkipped(DreamChestItemSyncStatus status) {
        return switch (status) {
            case UNMANAGED -> new SkipCounts(1, 0);
            case STALE -> new SkipCounts(0, 1);
            case CURRENT, UPDATED -> new SkipCounts(0, 0);
        };
    }

    private int updateOpenInventories(DreamChestRecord record, ItemStack[] live, ItemStack[] draft) {
        int updated = 0;
        for (DreamChestInventorySession session : List.copyOf(nativeInventorySessions.values())) {
            if (!record.key().equals(session.holder().key())) continue;
            ItemStack[] contents = session.editor()
                    ? draft
                    : Optional.ofNullable(record.playerContentsCopy(session.holder().viewerUniqueId()))
                    .orElse(live);
            Inventory inventory = session.inventory();
            if (inventory.getSize() != record.size().slots()) continue;
            inventory.setContents(viewContents(contents, inventory.getSize()));
            updated++;
            Player player = Bukkit.getPlayer(session.holder().viewerUniqueId());
            if (player != null && player.getOpenInventory().getTopInventory() == inventory) {
                player.updateInventory();
            }
        }
        return updated;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (!isChest(block)) return;
        Player player = event.getPlayer();
        boolean admin = player.hasPermission(ADMIN_PERMISSION);

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (admin && player.isSneaking()) {
            event.setCancelled(true);
            openManagement(player, block);
            return;
        }
        DreamChestRecord record = store.find(DreamChestKey.from(block));
        if (record == null || !record.refreshEnabled()) return;
        event.setCancelled(true);
        if (record.editing()) {
            if (!admin) {
                player.sendMessage(LegacyText.colorize("&c该箱子正在编辑, 暂时无法打开。"));
                return;
            }
            openEditor(player, record.key(), block);
            return;
        }
        openClaim(player, block, record);
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        if (openSessions.containsKey(event.getPlayer().getUniqueId())) closeManagedSession(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNativeInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.isCancelled()) return;
        DreamChestInventorySession session = nativeSession(event.getView().getTopInventory(), player.getUniqueId());
        if (session == null || !clickCanChangeTop(event)) return;
        if (!beginNativeMutation(session)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNativeInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.isCancelled()) return;
        DreamChestInventorySession session = nativeSession(event.getView().getTopInventory(), player.getUniqueId());
        if (session == null || !dragCanChangeTop(event)) return;
        if (!beginNativeMutation(session)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNativeInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        DreamChestInventorySession session = nativeSession(event.getInventory(), player.getUniqueId());
        if (session != null) finishNativeSession(session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNativePlayerQuit(PlayerQuitEvent event) {
        DreamChestInventorySession session = nativeInventorySessions.get(event.getPlayer().getUniqueId());
        if (session != null) finishNativeSession(session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        DreamChestKey key = DreamChestKey.from(event.getBlock());
        if (store.find(key) == null) return;
        if (event.getPlayer().isSneaking() && event.getPlayer().hasPermission(ADMIN_PERMISSION)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent event) {
        if (!event.getPlayer().isSneaking() || !event.getPlayer().hasPermission(ADMIN_PERMISSION)) return;
        Block block = event.getBlock();
        DreamChestKey key = DreamChestKey.from(block);
        DreamChestRecord record = store.find(key);
        if (record == null) return;
        tasks.runLater(1L, () -> {
            if (isChest(block) || store.find(key) != record) return;
            removeRecord(key, "箱子已破坏, DreamChest 管理记录已删除。", event.getPlayer());
        });
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onExplode(EntityExplodeEvent event) {
        for (Block block : List.copyOf(event.blockList())) {
            removeIfManaged(DreamChestKey.from(block), "箱子被爆炸破坏, DreamChest 管理记录已删除。", null);
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            removeIfManaged(DreamChestKey.from(block), "箱子被活塞移动, DreamChest 管理记录已删除。", null);
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            removeIfManaged(DreamChestKey.from(block), "箱子被活塞移动, DreamChest 管理记录已删除。", null);
        }
    }

    void openManagement(Player player, Block sourceBlock) {
        requireAdmin(player);
        closeNativeInventory(player);
        DreamChestKey key = DreamChestKey.from(sourceBlock);
        DreamChestRecord record = store.find(key);
        if (record == null) {
            record = DreamChestRecord.createDefault(key, settings.snapshot(), Instant.now());
            store.put(record);
            persist();
        }
        DreamChestManagementMenu menu = new DreamChestManagementMenu(this, key);
        try {
            menus.open(player, menu);
            openSessions.put(player.getUniqueId(), key);
            managementViewers.computeIfAbsent(key, ignored -> new HashSet<>()).add(player.getUniqueId());
        } catch (RuntimeException exception) {
            menus.close(player);
            logger.log(Level.SEVERE, "Failed to open DreamChest management menu", exception);
            player.sendMessage(LegacyText.colorize("&c箱子管理面板打开失败, 请查看服务端日志。"));
        }
    }

    boolean hasMultipleAdminPanels(Player player, DreamChestKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) return false;
        return adminPanels.hasMultiplePanels(player, world.getBlockAt(key.x(), key.y(), key.z()));
    }

    void openAdminOverview(Player player, DreamChestKey key) {
        requireAdmin(player);
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) {
            menus.close(player);
            player.sendMessage(LegacyText.colorize("&c箱子所在世界当前不可用。"));
            return;
        }
        adminPanels.openOverview(player, world.getBlockAt(key.x(), key.y(), key.z()));
    }

    private void openClaim(Player player, Block sourceBlock, DreamChestRecord record) {
        if (!record.hasClaimableItems(player.getUniqueId())) {
            player.sendMessage(LegacyText.colorize("&c暂无物品"));
            return;
        }
        DreamChestKey key = record.key();
        try {
            ItemStack[] contents = record.playerContentsCopy(player.getUniqueId());
            if (contents == null) contents = record.liveCopy();
            openNativeInventory(player, key, sourceBlock, false, contents);
        } catch (RuntimeException exception) {
            closeNativeInventory(player);
            logger.log(Level.SEVERE, "Failed to open DreamChest claim menu", exception);
            player.sendMessage(LegacyText.colorize("&c箱子打开失败, 请查看服务端日志。"));
        }
    }

    void openEditor(Player player, DreamChestKey key, Block sourceBlock) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        if (!record.refreshEnabled()) {
            player.sendMessage(LegacyText.colorize("&c请先启用箱子刷新, 再编辑虚拟箱子内容。"));
            return;
        }
        if (!record.editing()) {
            player.sendMessage(LegacyText.colorize("&c请先在箱子管理面板中进入编辑模式。"));
            return;
        }
        if (!activeClaimViewers(key).isEmpty()) {
            player.sendMessage(LegacyText.colorize("&c还有玩家正在领取, 请等待他们关闭箱子后再编辑。"));
            return;
        }
        if (record.editor() != null && !record.editor().equals(player.getUniqueId())) {
            player.sendMessage(LegacyText.colorize("&c另一名管理员正在编辑这个箱子。"));
            return;
        }
        record.setEditor(player.getUniqueId());
        persist();
        try {
            openNativeInventory(player, key, sourceBlock, true, record.draftCopy());
        } catch (RuntimeException exception) {
            closeNativeInventory(player);
            record.setEditor(null);
            persist();
            logger.log(Level.SEVERE, "Failed to open DreamChest editor", exception);
            player.sendMessage(LegacyText.colorize("&c箱子编辑器打开失败, 请查看服务端日志。"));
        }
    }

    boolean enterEditing(Player player, DreamChestKey key) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        if (!record.refreshEnabled()) {
            player.sendMessage(LegacyText.colorize("&c请先启用箱子刷新, 再编辑虚拟箱子内容。"));
            return false;
        }
        if (record.editing()) return false;
        if (!activeClaimViewers(key).isEmpty()) {
            player.sendMessage(LegacyText.colorize("&c还有玩家正在领取, 请等待他们关闭箱子后再编辑。"));
            return false;
        }
        record.setDraft(record.templateCopy());
        record.setEditing(true);
        persist();
        refreshManagementMenus(key);
        player.sendMessage(LegacyText.colorize("&a已进入编辑模式。"));
        return true;
    }

    boolean exitEditing(Player player, DreamChestKey key) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        if (!record.editing()) return false;
        if (record.editor() != null) {
            player.sendMessage(LegacyText.colorize("&c请先关闭编辑库存, 再退出编辑模式。"));
            return false;
        }
        if (!activeClaimViewers(key).isEmpty()) {
            player.sendMessage(LegacyText.colorize("&c还有玩家正在领取, 无法完成编辑。"));
            return false;
        }
        record.setTemplate(record.draftCopy());
        record.setEditing(false);
        record.refreshNow(settings.snapshot(), Instant.now());
        persist();
        updateDisplay(record, Instant.now());
        refreshManagementMenus(key);
        player.sendMessage(LegacyText.colorize("&a已退出编辑模式。"));
        return true;
    }

    boolean toggleSize(Player player, DreamChestKey key) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        if (!activeClaimViewers(key).isEmpty() || record.editor() != null) {
            player.sendMessage(LegacyText.colorize("&c请先关闭该箱子的其他库存界面, 再切换箱子大小。"));
            return false;
        }
        DreamChestSize candidate = record.size() == DreamChestSize.SMALL
                ? DreamChestSize.LARGE
                : DreamChestSize.SMALL;
        if (candidate == DreamChestSize.SMALL && record.hasItemsOutside(candidate)) {
            player.sendMessage(LegacyText.colorize("&c大型箱子内第 28-54 格仍有物品, 无法切换。"));
            return false;
        }
        record.setSize(candidate);
        persist();
        menus.refresh(player);
        return true;
    }

    void cycleRefreshMode(Player player, DreamChestKey key, boolean next) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        RefreshMode[] modes = RefreshMode.values();
        int index = (record.refreshMode().ordinal() + (next ? 1 : modes.length - 1)) % modes.length;
        record.setRefreshMode(modes[index]);
        record.recalculateNextRefresh(settings.snapshot(), Instant.now());
        persist();
        refreshManagementMenus(key);
    }

    void toggleRefreshEnabled(Player player, DreamChestKey key) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        boolean enabled = !record.refreshEnabled();
        Instant now = Instant.now();
        record.setRefreshEnabled(enabled);
        if (!enabled) {
            for (UUID playerUniqueId : List.copyOf(activeClaimViewers(key))) {
                closeNativeInventory(playerUniqueId);
            }
            claimViewers.remove(key);
            UUID editor = record.editor();
            if (editor != null) closeNativeInventory(editor);
            record.setEditing(false);
            record.setEditor(null);
            record.setNextRefreshEpochSecond(0L);
            closeHologram(key);
        } else {
            record.recalculateNextRefresh(settings.snapshot(), now);
            if (record.refreshMode() == RefreshMode.ALWAYS) record.refreshNow(settings.snapshot(), now);
        }
        persist();
        updateDisplay(record, now);
        refreshManagementMenus(key);
    }

    void cycleInterval(Player player, DreamChestKey key, boolean next) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        DreamChestSettings.Snapshot configuration = settings.snapshot();
        String option = cycle(configuration.intervalOptions(), record.intervalOption(), next);
        record.setIntervalOption(option);
        record.recalculateNextRefresh(configuration, Instant.now());
        persist();
        refreshManagementMenus(key);
    }

    void cycleDaily(Player player, DreamChestKey key, boolean next) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        DreamChestSettings.Snapshot configuration = settings.snapshot();
        String option = cycle(configuration.dailyOptions(), record.dailyOption(), next);
        record.setDailyOption(option);
        record.recalculateNextRefresh(configuration, Instant.now());
        persist();
        refreshManagementMenus(key);
    }

    void cycleParticle(Player player, DreamChestKey key, boolean next) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        List<String> options = settings.snapshot().particleOptions();
        int current = Math.max(0, options.indexOf(record.particle()));
        int index = (current + (next ? 1 : options.size() - 1)) % options.size();
        record.setParticle(options.get(index));
        persist();
        refreshManagementMenus(key);
    }

    void toggleHologram(Player player, DreamChestKey key) {
        requireAdmin(player);
        DreamChestRecord record = requireRecord(key);
        record.setHologramEnabled(!record.hologramEnabled());
        persist();
        updateDisplay(record, Instant.now());
        refreshManagementMenus(key);
    }

    DreamChestRecord recordForMenu(DreamChestKey key) {
        return requireRecord(key);
    }

    DreamChestSettings.Snapshot settingsForMenu() {
        return settings.snapshot();
    }

    int claimViewerCountForMenu(DreamChestKey key) {
        return claimViewerCount(key);
    }

    private void openNativeInventory(
            Player player,
            DreamChestKey key,
            Block sourceBlock,
            boolean editor,
            ItemStack[] contents
    ) {
        closeManagedSession(player);
        DreamChestRecord record = requireRecord(key);
        DreamChestInventoryHolder holder = new DreamChestInventoryHolder(
                key,
                player.getUniqueId(),
                editor
        );
        Inventory inventory = Bukkit.createInventory(
                holder,
                record.size().slots(),
                LegacyText.colorize(editor ? "&8箱子(编辑状态)" : "&8箱子")
        );
        holder.attach(inventory);
        // Claim views are private snapshots; editor changes go to the draft and claim changes go to player state.
        inventory.setContents(viewContents(contents, inventory.getSize()));
        DreamChestInventorySession session = new DreamChestInventorySession(holder);
        nativeInventorySessions.put(player.getUniqueId(), session);
        openSessions.put(player.getUniqueId(), key);
        if (!editor) claimViewers.computeIfAbsent(key, ignored -> new HashSet<>()).add(player.getUniqueId());
        try {
            player.openInventory(inventory);
            session.attachAnimation(animations.open(sourceBlock, player, ContainerAnimationSpec.enderChest()));
        } catch (RuntimeException exception) {
            nativeInventorySessions.remove(player.getUniqueId(), session);
            openSessions.remove(player.getUniqueId(), key);
            claimViewers.computeIfPresent(key, (ignored, viewers) -> {
                viewers.remove(player.getUniqueId());
                return viewers.isEmpty() ? null : viewers;
            });
            session.closeAnimation();
            throw exception;
        }
    }

    private DreamChestInventorySession nativeSession(Inventory inventory, UUID viewerUniqueId) {
        if (inventory == null || viewerUniqueId == null) return null;
        if (!(inventory.getHolder() instanceof DreamChestInventoryHolder holder)) return null;
        if (!viewerUniqueId.equals(holder.viewerUniqueId())) return null;
        DreamChestInventorySession session = nativeInventorySessions.get(viewerUniqueId);
        return session != null && session.holder() == holder ? session : null;
    }

    private boolean beginNativeMutation(DreamChestInventorySession session) {
        pendingNativeMutations.add(session);
        if (nativeFlushTask == null) {
            nativeFlushTask = tasks.runLater(1L, () -> {
                nativeFlushTask = null;
                flushPendingNativeMutation();
            });
        }
        return true;
    }

    private void flushPendingNativeMutation() {
        Set<DreamChestInventorySession> pending = Set.copyOf(pendingNativeMutations);
        pendingNativeMutations.clear();
        if (pending.isEmpty()) return;
        boolean changed = false;
        Set<DreamChestKey> managementMenusToRefresh = new HashSet<>();
        for (DreamChestInventorySession session : pending) {
            if (nativeInventorySessions.get(session.holder().viewerUniqueId()) != session) continue;
            DreamChestRecord record = store.find(session.holder().key());
            if (record == null) continue;
            if (!captureNativeSession(session, record)) continue;
            changed = true;
            if (session.editor()) managementMenusToRefresh.add(record.key());
        }
        if (!changed) return;
        persist();
        managementMenusToRefresh.forEach(this::refreshManagementMenus);
    }

    private boolean captureNativeSession(DreamChestInventorySession session, DreamChestRecord record) {
        if (!session.editor()) {
            UUID playerUniqueId = session.holder().viewerUniqueId();
            ItemStack[] previous = record.playerContentsCopy(playerUniqueId);
            ItemStack[] current = capture(session.inventory());
            boolean matchesSharedContents = sameContents(record.liveCopy(), current);
            if (matchesSharedContents) record.removePlayerContents(playerUniqueId);
            else record.setPlayerContents(playerUniqueId, current);
            return previous == null
                    ? !matchesSharedContents
                    : matchesSharedContents || !sameContents(previous, current);
        }
        ItemStack[] current = capture(session.inventory());
        ItemStack[] previous = record.draftCopy();
        record.setDraft(current);
        return !sameContents(previous, current);
    }

    private void cancelNativeFlush() {
        tasks.cancel(nativeFlushTask);
        nativeFlushTask = null;
    }

    private void finishNativeSession(DreamChestInventorySession session) {
        UUID viewerUniqueId = session.holder().viewerUniqueId();
        if (!nativeInventorySessions.remove(viewerUniqueId, session)) return;
        if (pendingNativeMutations.remove(session) && pendingNativeMutations.isEmpty()) {
            cancelNativeFlush();
        }
        DreamChestRecord record = store.find(session.holder().key());
        boolean changed = record != null && captureNativeSession(session, record);
        session.closeAnimation();
        openSessions.remove(viewerUniqueId, session.holder().key());
        if (session.editor()) {
            if (record != null && viewerUniqueId.equals(record.editor())) {
                record.setEditor(null);
                persist();
                refreshManagementMenus(record.key());
            } else if (changed) {
                persist();
            }
            return;
        }
        DreamChestKey key = session.holder().key();
        Set<UUID> viewers = claimViewers.get(key);
        if (viewers != null) {
            viewers.remove(viewerUniqueId);
            if (viewers.isEmpty()) claimViewers.remove(key);
        }
        record = store.find(key);
        if (record == null) return;
        Instant now = Instant.now();
        boolean refreshed = viewersAreEmpty(key) && !record.editing() && record.isDue(now);
        if (refreshed) record.refreshNow(settings.snapshot(), now);
        if (changed || refreshed) {
            persist();
            updateDisplay(record, now);
        }
    }

    private void closeManagedSession(Player player) {
        if (menus.hasOpenMenu(player.getUniqueId())) menus.close(player);
        closeNativeInventory(player);
    }

    private void closeNativeInventory(Player player) {
        DreamChestInventorySession session = nativeInventorySessions.get(player.getUniqueId());
        if (session == null) return;
        if (player.getOpenInventory().getTopInventory() == session.inventory()) {
            player.closeInventory();
        } else {
            finishNativeSession(session);
        }
    }

    private void closeNativeInventory(UUID viewerUniqueId) {
        Player player = Bukkit.getPlayer(viewerUniqueId);
        if (player != null) {
            closeNativeInventory(player);
            return;
        }
        DreamChestInventorySession session = nativeInventorySessions.get(viewerUniqueId);
        if (session != null) finishNativeSession(session);
    }

    void onManagementClosed(Player player, DreamChestKey key) {
        closeSession(player, key);
        Set<UUID> viewers = managementViewers.get(key);
        if (viewers != null) {
            viewers.remove(player.getUniqueId());
            if (viewers.isEmpty()) managementViewers.remove(key);
        }
    }

    private void closeSession(Player player, DreamChestKey key) {
        openSessions.remove(player.getUniqueId(), key);
    }

    private void refreshManagementMenus(DreamChestKey key) {
        for (UUID playerUniqueId : List.copyOf(managementViewers.getOrDefault(key, Set.of()))) {
            Player player = Bukkit.getPlayer(playerUniqueId);
            if (player != null) menus.refresh(player);
        }
    }

    private Set<UUID> activeClaimViewers(DreamChestKey key) {
        return claimViewers.getOrDefault(key, Set.of());
    }

    private int claimViewerCount(DreamChestKey key) {
        return activeClaimViewers(key).size();
    }

    private boolean viewersAreEmpty(DreamChestKey key) {
        return activeClaimViewers(key).isEmpty();
    }

    private void tick() {
        Instant now = Instant.now();
        boolean changed = false;
        for (DreamChestRecord record : store.all()) {
            if (record.refreshEnabled() && record.isDue(now) && !record.editing() && viewersAreEmpty(record.key())) {
                record.refreshNow(settings.snapshot(), now);
                changed = true;
            }
            updateDisplay(record, now);
        }
        if (changed) persist();
    }

    private void renderParticles() {
        DreamChestSettings.Snapshot configuration = settings.snapshot();
        for (DreamChestRecord record : store.all()) {
            if (!record.refreshEnabled() || record.particle().equalsIgnoreCase("NONE"))
                continue;
            Particle particle;
            try {
                particle = Particle.valueOf(record.particle());
            } catch (IllegalArgumentException exception) {
                logger.log(Level.WARNING, "Ignoring invalid DreamChest particle: " + record.particle(), exception);
                continue;
            }
            World world = Bukkit.getWorld(record.key().worldId());
            if (world == null || !world.isChunkLoaded(record.key().x() >> 4, record.key().z() >> 4)) continue;
            Location center = new Location(
                    world,
                    record.key().x() + 0.5D,
                    record.key().y() + 0.5D,
                    record.key().z() + 0.5D
            );
            List<DreamChestParticleGeometry.ParticlePoint> particlePoints = DreamChestParticleGeometry.randomAroundChest(
                    record.key().x(),
                    record.key().y(),
                    record.key().z(),
                    configuration.particleCount(),
                    ThreadLocalRandom.current()
            );
            double distanceSquared = (double) configuration.particleRenderDistance()
                    * configuration.particleRenderDistance();
            for (Player player : world.getPlayers()) {
                if (!record.hasClaimableItems(player.getUniqueId())) continue;
                if (player.getLocation().distanceSquared(center) <= distanceSquared) {
                    for (DreamChestParticleGeometry.ParticlePoint point : particlePoints) {
                        player.spawnParticle(
                                particle,
                                point.x(),
                                point.y(),
                                point.z(),
                                1,
                                0.0D,
                                0.0D,
                                0.0D,
                                0.0D
                        );
                    }
                }
            }
        }
    }

    private void updateDisplay(DreamChestRecord record, Instant now) {
        DreamChestSettings.Snapshot configuration = settings.snapshot();
        DreamChestKey key = record.key();
        if (!record.refreshEnabled() || !record.hologramEnabled()) {
            closeHologram(key);
            return;
        }
        World world = Bukkit.getWorld(key.worldId());
        if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
            closeHologram(key);
            return;
        }
        String countdown = LegacyText.colorize("&7" + formatRemaining(record, now));
        FloatingTextHandle handle = holograms.get(key);
        Location location = new Location(
                world,
                key.x() + 0.5D,
                key.y() + configuration.hologramHeight(),
                key.z() + 0.5D
        );
        FloatingTextSpec specification = new FloatingTextSpec(
                List.of(
                        countdown,
                        LegacyText.colorize("&a可领取!")
                ),
                configuration.hologramLineSpacing(),
                configuration.hologramViewDistance()
        );
        FloatingTextSpec emptySpecification = new FloatingTextSpec(
                List.of(countdown, LegacyText.colorize("&c暂无物品")),
                configuration.hologramLineSpacing(),
                configuration.hologramViewDistance()
        );
        if (handle == null) {
            FloatingTextHandle claimable = floatingText.show(
                    location, specification, player -> record.hasClaimableItems(player.getUniqueId())
            );
            try {
                emptyHolograms.put(key, floatingText.show(
                        location, emptySpecification, player -> !record.hasClaimableItems(player.getUniqueId())
                ));
            } catch (RuntimeException exception) {
                claimable.close();
                throw exception;
            }
            holograms.put(key, claimable);
        } else {
            Location previousLocation = hologramLocations.get(key);
            FloatingTextHandle empty = emptyHolograms.get(key);
            if (!sameAnchor(previousLocation, location)) {
                handle.move(location);
                empty.move(location);
            }
            if (!specification.equals(hologramSpecifications.get(key))) {
                handle.update(specification);
                empty.update(emptySpecification);
            }
        }
        hologramSpecifications.put(key, specification);
        hologramLocations.put(key, location.clone());
    }

    private void closeHologram(DreamChestKey key) {
        FloatingTextHandle handle = holograms.remove(key);
        if (handle != null) handle.close();
        FloatingTextHandle empty = emptyHolograms.remove(key);
        if (empty != null) empty.close();
        hologramSpecifications.remove(key);
        hologramLocations.remove(key);
    }

    private DreamChestRecord requireRecord(DreamChestKey key) {
        DreamChestRecord record = store.find(Objects.requireNonNull(key, "key"));
        if (record == null) throw new IllegalStateException("DreamChest record disappeared: " + key.encoded());
        return record;
    }

    private void requireAdmin(Player player) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            throw new IllegalStateException("Player lacks " + ADMIN_PERMISSION + ": " + player.getName());
        }
    }

    private void persist() {
        store.save(store.all());
    }

    /**
     * Removes records whose configured anchor no longer contains a real chest block.
     *
     * <p>This runs after the store is loaded and before display/tick state is initialized, so a
     * stale configuration entry cannot recreate a hologram or participate in refresh logic.</p>
     */
    private void cleanupMissingAnchors() {
        List<DreamChestKey> stale = new ArrayList<>();
        for (DreamChestRecord record : store.all()) {
            DreamChestKey key = record.key();
            World world = Bukkit.getWorld(key.worldId());
            // DreamWorld loads restored worlds after plugin startup; absence is not a missing anchor.
            if (world == null) {
                logger.info("Deferring DreamChest anchor check until world is available: " + key.encoded());
                continue;
            }
            if (!isChest(world.getBlockAt(key.x(), key.y(), key.z()))) {
                stale.add(key);
            }
        }
        if (stale.isEmpty()) return;
        for (DreamChestKey key : stale) store.remove(key);
        persist();
        logger.info("Removed " + stale.size() + " DreamChest record(s) without a physical chest anchor.");
    }

    private void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("DreamChest API must run on Bukkit's primary thread");
        }
    }

    private void removeIfManaged(DreamChestKey key, String message, Player notify) {
        if (store.find(key) == null) return;
        removeRecord(key, message, notify);
    }

    private void removeRecord(DreamChestKey key, String message, Player notify) {
        for (DreamChestInventorySession session : List.copyOf(nativeInventorySessions.values())) {
            if (key.equals(session.holder().key())) closeNativeInventory(session.holder().viewerUniqueId());
        }
        for (UUID playerUniqueId : List.copyOf(openSessions.keySet())) {
            if (!key.equals(openSessions.get(playerUniqueId))) continue;
            Player player = Bukkit.getPlayer(playerUniqueId);
            if (player != null && menus.hasOpenMenu(playerUniqueId)) menus.close(player);
        }
        closeHologram(key);
        store.remove(key);
        claimViewers.remove(key);
        persist();
        if (notify != null) notify.sendMessage(LegacyText.colorize("&a" + message));
    }

    private record SkipCounts(int unmanaged, int stale) {
    }

    private record RecordSync(
            DreamChestRecord record,
            ItemStack[] oldTemplate,
            ItemStack[] oldDraft,
            ItemStack[] oldLive,
            Map<UUID, ItemStack[]> oldPlayerContents,
            ItemStack[] template,
            ItemStack[] draft,
            ItemStack[] live,
            boolean changed
    ) {
    }

}
