package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.api.DreamLibApi;
import cn.mythicland.dreamlib.bootstrap.annotation.InjectComponent;
import cn.mythicland.dreamlib.database.MigrationRunner;
import cn.mythicland.dreamlib.database.MigrationSpec;
import cn.mythicland.dreamlib.database.SqlDatabase;
import cn.mythicland.dreamlib.library.LibraryLoadResult;
import cn.mythicland.dreamlib.library.LibraryService;
import cn.mythicland.dreamlib.library.LibrarySpec;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/** Owns the personal-content database; chest templates remain in chests.yml. */
@InjectComponent
final class DreamChestClaimDatabase implements AutoCloseable {
    private final LibraryLoadResult libraries;
    private final SqlDatabase database;

    DreamChestClaimDatabase(JavaPlugin plugin, DreamLibApi lib) {
        libraries = lib.libraryService().load(plugin, List.of(new LibrarySpec(
                "org.xerial:sqlite-jdbc:3.46.1.3", "sqlite-jdbc-3.46.1.3.jar",
                "4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa"
        )), LibraryService.DEFAULT_REPOSITORY);
        SqlDatabase opened = null;
        try {
            opened = lib.databaseService().openSqlite(plugin,
                    plugin.getDataFolder().toPath().resolve("claims.db"), "org.sqlite.JDBC", libraries.classLoader());
            new MigrationRunner().migrate(opened, plugin,
                    List.of(new MigrationSpec(1, "db/sqlite/V1__create_player_chest_contents.sql")));
            database = opened;
        } catch (IOException | SQLException | RuntimeException exception) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException failure) { exception.addSuppressed(failure); }
            }
            try { libraries.close(); } catch (IOException failure) { exception.addSuppressed(failure); }
            throw new IllegalStateException("Cannot initialize DreamChest claim database", exception);
        }
    }

    DreamChestClaimRepository repository() {
        return new DreamChestClaimRepository(database);
    }

    @Override
    public void close() throws SQLException, IOException {
        try {
            database.close();
        } finally {
            libraries.close();
        }
    }
}
