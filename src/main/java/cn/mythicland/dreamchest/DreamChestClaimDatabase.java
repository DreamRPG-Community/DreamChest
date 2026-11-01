package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.api.DreamLibApi;
import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.database.MigrationSpec;
import cn.mythicland.framework.database.SqlDatabase;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies chest claim migrations to the shared database.
 */
@InjectComponent
final class DreamChestClaimDatabase implements AutoCloseable {
    private final SqlDatabase database;

    DreamChestClaimDatabase(JavaPlugin plugin, DreamLibApi lib) {
        database = lib.sharedDatabase().database();
        try {
            lib.sharedDatabase().migrate(plugin, "dreamchest_schema_history",
                    List.of(new MigrationSpec(1, "V1__create_player_chest_contents.sql")));
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot initialize DreamChest claim database", e);
        }
    }

    DreamChestClaimRepository repository() {
        return new DreamChestClaimRepository(database);
    }

    @Override
    public void close() {
        // DreamLib owns the shared database lifecycle.
    }
}
