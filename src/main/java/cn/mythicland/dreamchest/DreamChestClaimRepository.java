package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.database.SqlDatabase;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stores encoded personal chest contents without using Bukkit objects on database threads. */
final class DreamChestClaimRepository {
    private final SqlDatabase database;

    DreamChestClaimRepository(SqlDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    Map<String, Map<UUID, String>> load() throws SQLException {
        return database.query(connection -> {
            Map<String, Map<UUID, String>> result = new LinkedHashMap<>();
            try (var statement = connection.prepareStatement("SELECT chest_key, player_uuid, contents FROM player_chest_contents");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getString(1), ignored -> new LinkedHashMap<>())
                            .put(UUID.fromString(rows.getString(2)), rows.getString(3));
                }
            }
            return result;
        });
    }

    void save(Map<String, Map<UUID, String>> snapshot) throws SQLException {
        database.transaction(connection -> {
            // Preserve the existing full-snapshot semantics; deletion and insertion commit together.
            try (var clear = connection.prepareStatement("DELETE FROM player_chest_contents")) {
                clear.executeUpdate();
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO player_chest_contents(chest_key, player_uuid, contents) VALUES (?, ?, ?)")) {
                for (var chest : snapshot.entrySet()) {
                    for (var player : chest.getValue().entrySet()) {
                        insert.setString(1, chest.getKey());
                        insert.setString(2, player.getKey().toString());
                        insert.setString(3, player.getValue());
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
            }
            return null;
        });
    }
}
