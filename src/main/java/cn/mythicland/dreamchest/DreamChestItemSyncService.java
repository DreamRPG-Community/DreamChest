package cn.mythicland.dreamchest;

import cn.mythicland.dreamchest.api.DreamChestItemReconciler;
import cn.mythicland.dreamchest.api.DreamChestItemSyncApi;
import cn.mythicland.dreamchest.api.DreamChestItemSyncReport;
import cn.mythicland.framework.bukkit.component.ServiceComponent;

import java.util.Objects;

/**
 * Service facade that keeps DreamChest state mutation inside DreamChestService.
 */
@ServiceComponent(DreamChestItemSyncApi.class)
public final class DreamChestItemSyncService implements DreamChestItemSyncApi {

    private final DreamChestService service;

    DreamChestItemSyncService(DreamChestService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * Delegates synchronization to DreamChest's main-thread state owner.
     *
     * @param reconciler external item reconciler
     * @return synchronization statistics
     */
    @Override
    public DreamChestItemSyncReport synchronize(DreamChestItemReconciler reconciler) {
        return service.synchronizeItems(reconciler);
    }
}
