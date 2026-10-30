package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.database.SqlDatabase;
import cn.mythicland.dreamlib.database.SqlWork;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DreamChestClaimRepositoryTest {
    @Test
    void roundTripsPersonalContentsAndRollsBackFailedReplacement() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var resource = getClass().getResourceAsStream("/db/sqlite/V1__create_player_chest_contents.sql");
                 var statement = connection.createStatement()) {
                assertNotNull(resource);
                statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
            SqlDatabase database = new SqlDatabase() {
                public <T> T query(SqlWork<T> work) throws SQLException { return work.execute(connection); }
                public <T> T transaction(SqlWork<T> work) throws SQLException {
                    connection.setAutoCommit(false);
                    try {
                        T result = work.execute(connection);
                        connection.commit();
                        return result;
                    } catch (SQLException exception) {
                        connection.rollback();
                        throw exception;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                }
                public void close() { }
            };
            var repository = new DreamChestClaimRepository(database);
            UUID player = UUID.randomUUID();
            Map<String, Map<UUID, String>> contents = Map.of("new-world:1:2:3", Map.of(player, "empty-inventory-encoding"));
            repository.save(contents);
            assertEquals(contents, new DreamChestClaimRepository(database).load(), "A fresh repository must retain consumed slots");
            Map<UUID, String> invalidPlayer = new HashMap<>();
            invalidPlayer.put(player, null);
            assertThrows(SQLException.class, () -> repository.save(Map.of("new-world:1:2:3", invalidPlayer)));
            assertEquals(contents, repository.load(), "Failed replacement must preserve prior claims");
            repository.save(Map.of());
            assertTrue(repository.load().isEmpty(), "Explicit snapshot removal must remove stale claims");
        }
    }
}
