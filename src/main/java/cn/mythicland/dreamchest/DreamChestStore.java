package cn.mythicland.dreamchest;

import cn.mythicland.dreamlib.api.DreamLibApi;
import cn.mythicland.dreamlib.bootstrap.annotation.InjectComponent;
import cn.mythicland.dreamlib.item.ItemStackArrayCodec;
import cn.mythicland.dreamlib.item.ItemMarker;
import cn.mythicland.dreamlib.storage.AtomicYamlStore;
import cn.mythicland.dreamchest.api.DreamChestKey;
import cn.mythicland.dreamchest.api.DreamChestSize;
import cn.mythicland.dreamchest.api.RefreshMode;
import io.lumine.xikage.mythicmobs.MythicMobs;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns chest template YAML and database-backed personal contents.
 */
@InjectComponent
final class DreamChestStore implements AutoCloseable {

    private static final int VERSION = 3;

    private final DreamLibApi lib;
    private final Logger logger;
    private final Path file;
    private final DreamChestClaimRepository claims;
    private volatile Throwable storageFailure;
    private final Map<DreamChestKey, DreamChestRecord> records = new LinkedHashMap<>();
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    DreamChestStore(JavaPlugin plugin, DreamLibApi lib, Logger logger, DreamChestClaimDatabase database) {
        Objects.requireNonNull(plugin, "plugin");
        this.lib = Objects.requireNonNull(lib, "lib");
        this.logger = Objects.requireNonNull(logger, "logger");
        Path dataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        this.file = dataDirectory.resolve("chests.yml").normalize();
        this.claims = database.repository();
        load();
    }

    private static String requiredString(ConfigurationSection section, String path) {
        Object value = section.get(path);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("DreamChest data requires a non-empty string: " + path);
        }
        return text.trim();
    }

    private static boolean requiredBoolean(ConfigurationSection section, String path) {
        Object value = section.get(path);
        if (!(value instanceof Boolean result))
            throw new IllegalStateException("DreamChest data requires boolean: " + path);
        return result;
    }

    private static boolean optionalBoolean(ConfigurationSection section) {
        Object value = section.get("refresh.enabled");
        if (value == null) return false;
        if (!(value instanceof Boolean result)) {
            throw new IllegalStateException("DreamChest data requires boolean: refresh.enabled");
        }
        return result;
    }

    private static long requiredLong(ConfigurationSection section, String path) {
        Object value = section.get(path);
        if (!(value instanceof Number number))
            throw new IllegalStateException("DreamChest data requires number: " + path);
        long result = number.longValue();
        if (result < 0L || number.doubleValue() != result) {
            throw new IllegalStateException("DreamChest data requires non-negative integer: " + path);
        }
        return result;
    }

    private static <T extends Enum<T>> T enumValue(
            ConfigurationSection section,
            String path,
            Class<T> type
    ) {
        String value = requiredString(section, path);
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown DreamChest value at " + path + ": " + value, exception);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    Collection<DreamChestRecord> all() {
        requireHealthy();
        return List.copyOf(records.values());
    }

    DreamChestRecord find(DreamChestKey key) {
        requireHealthy();
        return records.get(key);
    }

    void put(DreamChestRecord record) {
        Objects.requireNonNull(record, "record");
        records.put(record.key(), record);
    }

    void remove(DreamChestKey key) {
        records.remove(key);
    }

    void save(Collection<DreamChestRecord> currentRecords) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("DreamChest storage snapshots require the main thread");
        Map<String, Object> snapshot = serialize(currentRecords);
        Map<String, Map<UUID, String>> playerSnapshot = new LinkedHashMap<>();
        for (DreamChestRecord record : currentRecords) {
            Map<UUID, String> contents = new LinkedHashMap<>();
            record.playerContentsCopy().forEach((player, items) -> contents.put(player, ItemStackArrayCodec.serialize(items)));
            playerSnapshot.put(record.key().encoded(), Map.copyOf(contents));
        }
        synchronized (this) {
            lastWrite = lastWrite.thenCompose(ignored -> lib.runAsync(() -> {
                        try {
                            claims.save(playerSnapshot);
                        } catch (SQLException exception) {
                            throw new CompletionException(exception);
                        }
                        writeSnapshot(snapshot);
                    }))
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            storageFailure = unwrap(failure);
                            logger.log(Level.SEVERE, "Failed to save DreamChest data", unwrap(failure));
                        }
                    });
        }
    }

    private void requireHealthy() {
        if (storageFailure != null) {
            throw new IllegalStateException("DreamChest storage failed; restart after repairing storage", storageFailure);
        }
    }

    @Override
    public void close() {
        CompletableFuture<Void> pending;
        synchronized (this) {
            pending = lastWrite;
        }
        try {
            pending.join();
        } catch (CompletionException exception) {
            logger.log(Level.SEVERE, "DreamChest data did not finish saving", unwrap(exception));
        }
    }

    private void load() {
        try {
            Files.createDirectories(Objects.requireNonNull(file.getParent(), "file.parent"));
            validateFilePath();
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) writeInitialFile();
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.load(file.toFile());
            Object version = configuration.get("version");
            if (!(version instanceof Number number) || number.intValue() < 1 || number.intValue() > VERSION) {
                throw new IllegalStateException("DreamChest chests.yml requires a supported version between 1 and " + VERSION);
            }
            ConfigurationSection section = configuration.getConfigurationSection("chests");
            if (section == null) {
                if (configuration.getKeys(false).size() == 1 && configuration.isConfigurationSection("version")) {
                    section = configuration.createSection("chests");
                } else {
                    throw new IllegalStateException("DreamChest chests.yml requires a chests section");
                }
            }
            Map<DreamChestKey, DreamChestRecord> loaded = new LinkedHashMap<>();
            for (String identifier : section.getKeys(false)) {
                ConfigurationSection chest = section.getConfigurationSection(identifier);
                if (chest == null) throw new IllegalStateException("Chest entry is not an object: " + identifier);
                DreamChestKey key = DreamChestKey.parse(identifier);
                DreamChestRecord record = readRecord(key, chest);
                if (loaded.putIfAbsent(key, record) != null) {
                    throw new IllegalStateException("Duplicate DreamChest key: " + identifier);
                }
            }
            records.clear();
            loaded.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(DreamChestKey::encoded)))
                    .forEach(entry -> records.put(entry.getKey(), entry.getValue()));
            Map<String, Map<UUID, String>> personal = new LinkedHashMap<>();
            lib.runAsync(() -> {
                try {
                    personal.putAll(claims.load());
                } catch (SQLException exception) {
                    throw new CompletionException(exception);
                }
            }).join();
            for (var entry : personal.entrySet()) {
                DreamChestRecord record = records.get(DreamChestKey.parse(entry.getKey()));
                if (record == null) {
                    throw new IllegalStateException("Claim database references unknown chest: " + entry.getKey());
                }
                for (var player : entry.getValue().entrySet()) {
                    record.setPlayerContents(player.getKey(), ItemStackArrayCodec.deserialize(player.getValue(), DreamChestRecord.STORAGE_SIZE));
                }
            }
            if (number.intValue() < VERSION) writeSnapshot(serialize(records.values()));
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not load DreamChest data: " + file, exception);
        }
    }

    private DreamChestRecord readRecord(DreamChestKey key, ConfigurationSection chest) {
        DreamChestSize size = enumValue(chest, "size", DreamChestSize.class);
        RefreshMode mode = enumValue(chest, "refresh.mode", RefreshMode.class);
        String particle = requiredString(chest, "particle").toUpperCase(java.util.Locale.ROOT);
        if (!particle.equals("NONE")) {
            try {
                Particle.valueOf(particle);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Unknown DreamChest particle: " + particle, exception);
            }
        }
        DreamChestRecord record = new DreamChestRecord(
                key,
                optionalBoolean(chest),
                size,
                mode,
                requiredString(chest, "refresh.interval-option"),
                requiredString(chest, "refresh.daily-option"),
                particle,
                requiredBoolean(chest, "hologram"),
                requiredBoolean(chest, "editing"),
                requiredLong(chest, "refresh.next-epoch-second"),
                decode(chest, "template"),
                decode(chest, "draft"),
                decode(chest, "live")
        );
        ConfigurationSection playerContents = chest.getConfigurationSection("player-contents");
        if (playerContents != null && !playerContents.getKeys(false).isEmpty()) {
            throw new IllegalStateException("Legacy YAML claims require an explicit migration; original chests.yml was not modified");
        }
        return record;
    }

    private Map<String, Object> serialize(Collection<DreamChestRecord> currentRecords) {
        Objects.requireNonNull(currentRecords, "currentRecords");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        Map<String, Object> chests = new LinkedHashMap<>();
        currentRecords.stream()
                .sorted(Comparator.comparing(record -> record.key().encoded()))
                .forEach(record -> chests.put(record.key().encoded(), serializeRecord(record)));
        root.put("chests", chests);
        return root;
    }

    private Map<String, Object> serializeRecord(DreamChestRecord record) {
        Map<String, Object> chest = new LinkedHashMap<>();
        chest.put("size", record.size().name());
        Map<String, Object> refresh = new LinkedHashMap<>();
        refresh.put("enabled", record.refreshEnabled());
        refresh.put("mode", record.refreshMode().name());
        refresh.put("interval-option", record.intervalOption());
        refresh.put("daily-option", record.dailyOption());
        refresh.put("next-epoch-second", record.nextRefreshEpochSecond());
        chest.put("refresh", refresh);
        chest.put("particle", record.particle());
        chest.put("hologram", record.hologramEnabled());
        chest.put("editing", record.editing());
        chest.put("template", encodeReferences(record.templateCopy()));
        chest.put("draft", encodeReferences(record.draftCopy()));
        chest.put("live", encodeReferences(record.liveCopy()));
        return chest;
    }

    private ItemStack[] decode(ConfigurationSection section, String path) {
        if (section.isString(path)) return ItemStackArrayCodec.deserialize(requiredString(section, path), DreamChestRecord.STORAGE_SIZE);
        ConfigurationSection slots = section.getConfigurationSection(path);
        if (slots == null) throw new IllegalStateException("Chest requires item reference slots: " + path);
        ItemStack[] contents = new ItemStack[DreamChestRecord.STORAGE_SIZE];
        for (String key : slots.getKeys(false)) {
            int slot = Integer.parseInt(key);
            if (slot < 0 || slot >= contents.length) throw new IllegalArgumentException("Invalid chest slot: " + key);
            ConfigurationSection entry = slots.getConfigurationSection(key);
            if (entry == null) throw new IllegalArgumentException("Chest slot must contain item and amount: " + key);
            String id = requiredString(entry, "item");
            Object configuredAmount = entry.get("amount", 1);
            if (!(configuredAmount instanceof Number count) || count.doubleValue() != count.intValue()) {
                throw new IllegalArgumentException("Chest amount must be an integer: " + key);
            }
            int amount = count.intValue();
            if (amount < 1 || amount > 64) throw new IllegalArgumentException("Chest amount must be between 1 and 64: " + key);
            ItemStack item = MythicMobs.inst().getItemManager().getItemStack(id);
            if (item == null) throw new IllegalStateException("Unknown chest item library key: " + id);
            item.setAmount(amount);
            // DreamRPG normalizes bootstrap items against its catalog on the first server tick.
            contents[slot] = lib.itemMarkerCodec().write(item, new ItemMarker(
                    "DreamRPG", 1, Map.of("item-id", id, "revision", "pending-catalog")
            ));
        }
        return contents;
    }

    private Map<String, Object> encodeReferences(ItemStack[] contents) {
        Map<String, Object> slots = new LinkedHashMap<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (DreamChestRecord.isEmpty(item)) continue;
            var marker = lib.itemMarkerCodec().read(item, "DreamRPG")
                    .orElseThrow(() -> new IllegalArgumentException("Chest rewards must reference the RPG item library"));
            String id = marker.values().get("item-id");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Chest reward has no RPG item ID");
            slots.put(String.valueOf(slot), Map.of("item", id, "amount", item.getAmount()));
        }
        return slots;
    }

    private void writeInitialFile() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("chests", new LinkedHashMap<>());
        AtomicYamlStore.write(file, root);
    }

    private void writeSnapshot(Map<String, Object> snapshot) {
        try {
            AtomicYamlStore.write(file, snapshot);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private void validateFilePath() {
        if (Files.isSymbolicLink(file))
            throw new IllegalStateException("DreamChest data file is a symbolic link: " + file);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("DreamChest data path is not a regular file: " + file);
        }
    }
}
