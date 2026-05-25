package com.massstora.sfcargo.storage;

import com.massstora.sfcargo.block.CargoBlockType;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class CargoStorage implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;

    private final JavaPlugin plugin;
    private final File databaseFile;
    private final File legacyBlocksYml;
    private final File legacyBlocksYaml;
    private final Map<BlockKey, CargoBlockRecord> blocks = new ConcurrentHashMap<>();
    private final Map<BlockKey, Integer> persistedFingerprints = new ConcurrentHashMap<>();
    private Connection connection;

    public CargoStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "blocks.db");
        this.legacyBlocksYml = new File(plugin.getDataFolder(), "blocks.yml");
        this.legacyBlocksYaml = new File(plugin.getDataFolder(), "blocks.yaml");
    }

    public synchronized void load() {
        blocks.clear();
        persistedFingerprints.clear();
        try {
            openDatabase();
            createSchema();
            migrateLegacyYaml(legacyBlocksYml);
            migrateLegacyYaml(legacyBlocksYaml);
            loadBlocks();
        } catch (SQLException | IOException ex) {
            throw new IllegalStateException("Could not load SF-Cargo block database", ex);
        }
    }

    public synchronized void save() {
        ensureOpen();
        try {
            for (CargoBlockRecord record : blocks.values()) {
                int fingerprint = fingerprint(record);
                if (persistedFingerprints.getOrDefault(record.key(), 0) != fingerprint) {
                    upsert(record);
                    persistedFingerprints.put(record.key(), fingerprint);
                }
            }
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save cargo block data", ex);
        }
    }

    public CargoBlockRecord get(BlockKey key) {
        return blocks.get(key);
    }

    public void put(CargoBlockRecord record) {
        blocks.put(record.key(), record);
        try {
            synchronized (this) {
                ensureOpen();
                upsert(record);
                persistedFingerprints.put(record.key(), fingerprint(record));
            }
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save cargo block record " + record.key(), ex);
        }
    }

    public CargoBlockRecord remove(BlockKey key) {
        CargoBlockRecord removed = blocks.remove(key);
        if (removed == null) {
            return null;
        }
        try {
            synchronized (this) {
                ensureOpen();
                delete(key);
                persistedFingerprints.remove(key);
            }
        } catch (SQLException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not delete cargo block record " + key, ex);
        }
        return removed;
    }

    public Collection<CargoBlockRecord> all() {
        return new ArrayList<>(blocks.values());
    }

    public Collection<CargoBlockRecord> managers() {
        ArrayList<CargoBlockRecord> managers = new ArrayList<>();
        for (CargoBlockRecord record : blocks.values()) {
            if (record.type() == CargoBlockType.MANAGER) {
                managers.add(record);
            }
        }
        return managers;
    }

    @Override
    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not close cargo block database", ex);
        } finally {
            connection = null;
        }
    }

    private void openDatabase() throws SQLException {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new SQLException("Could not create plugin data folder " + plugin.getDataFolder());
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS cargo_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS cargo_blocks (
                    world_id TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    manager_id TEXT NOT NULL,
                    owner_id TEXT,
                    created_at INTEGER NOT NULL,
                    attached_face TEXT NOT NULL,
                    channel INTEGER NOT NULL,
                    round_robin INTEGER NOT NULL,
                    smart_fill INTEGER NOT NULL,
                    whitelist INTEGER NOT NULL,
                    include_lore INTEGER NOT NULL,
                    filter_durability INTEGER NOT NULL,
                    filters BLOB,
                    PRIMARY KEY (world_id, x, y, z)
                )
                """);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cargo_metadata(key, value)
            VALUES('schema-version', ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """)) {
            statement.setString(1, String.valueOf(SCHEMA_VERSION));
            statement.executeUpdate();
        }
    }

    private void loadBlocks() throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT world_id, x, y, z, type, manager_id, owner_id, created_at, attached_face,
                   channel, round_robin, smart_fill, whitelist, include_lore, filter_durability, filters
            FROM cargo_blocks
            """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                CargoBlockType type = CargoBlockType.fromId(result.getString("type"));
                if (type == null) {
                    continue;
                }
                CargoBlockRecord record = new CargoBlockRecord(
                    new BlockKey(UUID.fromString(result.getString("world_id")), result.getInt("x"), result.getInt("y"), result.getInt("z")),
                    type,
                    UUID.fromString(result.getString("manager_id")),
                    BlockFace.valueOf(result.getString("attached_face")),
                    parseUuid(result.getString("owner_id")),
                    result.getLong("created_at")
                );
                record.channel(result.getInt("channel"));
                record.roundRobin(result.getBoolean("round_robin"));
                record.smartFill(result.getBoolean("smart_fill"));
                record.whitelist(result.getBoolean("whitelist"));
                record.includeLore(result.getBoolean("include_lore"));
                record.filterDurability(result.getBoolean("filter_durability"));
                record.filters(deserializeFilters(result.getBytes("filters")));
                blocks.put(record.key(), record);
                persistedFingerprints.put(record.key(), fingerprint(record));
            }
        }
    }

    private void migrateLegacyYaml(File legacyFile) throws SQLException, IOException {
        if (!legacyFile.exists()) {
            return;
        }

        ArrayList<CargoBlockRecord> imported = readLegacyYaml(legacyFile);
        transaction(() -> {
            for (CargoBlockRecord record : imported) {
                upsertRecord(record);
            }
        });

        /*
         * Temporary upgrade bridge:
         * Older SF-Cargo releases stored every tracked block in blocks.yml. That
         * meant each block placement, break, or menu edit rewrote the whole file,
         * which is exactly what the SQLite storage replaces. Import happens before
         * listeners, recipes, and the cargo worker are enabled so runtime code only
         * ever sees the database-backed storage. Once enough released versions have
         * shipped with SQLite, this legacy importer and its YAML parsing helpers can
         * be removed together.
         */
        Files.deleteIfExists(legacyFile.toPath());
        plugin.getLogger().info("Imported " + imported.size() + " legacy cargo block record(s) from " + legacyFile.getName() + " into blocks.db and removed the YAML file.");
    }

    private ArrayList<CargoBlockRecord> readLegacyYaml(File legacyFile) {
        ArrayList<CargoBlockRecord> imported = new ArrayList<>();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(legacyFile);
        ConfigurationSection section = config.getConfigurationSection("blocks");
        if (section == null) {
            return imported;
        }

        for (String path : section.getKeys(false)) {
            try {
                CargoBlockType type = CargoBlockType.fromId(section.getString(path + ".type", ""));
                if (type == null) {
                    continue;
                }
                CargoBlockRecord record = new CargoBlockRecord(
                    BlockKey.parse(path),
                    type,
                    UUID.fromString(section.getString(path + ".manager-id", UUID.randomUUID().toString())),
                    BlockFace.valueOf(section.getString(path + ".attached-face", "SELF")),
                    parseUuid(section.getString(path + ".owner-id")),
                    section.getLong(path + ".created-at", 0L)
                );
                record.channel(section.getInt(path + ".channel", 0));
                record.roundRobin(section.getBoolean(path + ".round-robin", false));
                record.smartFill(section.getBoolean(path + ".smart-fill", false));
                record.whitelist(section.getBoolean(path + ".filter.whitelist", true));
                record.includeLore(section.getBoolean(path + ".filter.include-lore", true));
                record.filterDurability(section.getBoolean(path + ".filter.durability", false));
                record.filters(section.getList(path + ".filter.items", java.util.List.of()).toArray(ItemStack[]::new));
                imported.add(record);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Skipping corrupt legacy cargo block record: " + path, ex);
            }
        }
        return imported;
    }

    private void upsert(CargoBlockRecord record) throws SQLException, IOException {
        transaction(() -> upsertRecord(record));
    }

    private void upsertRecord(CargoBlockRecord record) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cargo_blocks(
                world_id, x, y, z, type, manager_id, owner_id, created_at, attached_face,
                channel, round_robin, smart_fill, whitelist, include_lore, filter_durability, filters
            )
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(world_id, x, y, z) DO UPDATE SET
                type = excluded.type,
                manager_id = excluded.manager_id,
                owner_id = excluded.owner_id,
                created_at = excluded.created_at,
                attached_face = excluded.attached_face,
                channel = excluded.channel,
                round_robin = excluded.round_robin,
                smart_fill = excluded.smart_fill,
                whitelist = excluded.whitelist,
                include_lore = excluded.include_lore,
                filter_durability = excluded.filter_durability,
                filters = excluded.filters
            """)) {
            bindRecord(statement, record);
            statement.executeUpdate();
        }
    }

    private void delete(BlockKey key) throws SQLException, IOException {
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM cargo_blocks
                WHERE world_id = ? AND x = ? AND y = ? AND z = ?
                """)) {
                statement.setString(1, key.worldId().toString());
                statement.setInt(2, key.x());
                statement.setInt(3, key.y());
                statement.setInt(4, key.z());
                statement.executeUpdate();
            }
        });
    }

    private void bindRecord(PreparedStatement statement, CargoBlockRecord record) throws SQLException, IOException {
        statement.setString(1, record.key().worldId().toString());
        statement.setInt(2, record.key().x());
        statement.setInt(3, record.key().y());
        statement.setInt(4, record.key().z());
        statement.setString(5, record.type().id());
        statement.setString(6, record.managerId().toString());
        statement.setString(7, record.ownerId() == null ? null : record.ownerId().toString());
        statement.setLong(8, record.createdAtMillis());
        statement.setString(9, record.attachedFace().name());
        statement.setInt(10, record.channel());
        statement.setBoolean(11, record.roundRobin());
        statement.setBoolean(12, record.smartFill());
        statement.setBoolean(13, record.whitelist());
        statement.setBoolean(14, record.includeLore());
        statement.setBoolean(15, record.filterDurability());
        statement.setBytes(16, serializeFilters(record.filters()));
    }

    private void transaction(SqlRunnable runnable) throws SQLException, IOException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            runnable.run();
            connection.commit();
        } catch (SQLException | IOException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private byte[] serializeFilters(ItemStack[] filters) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeInt(filters == null ? 0 : filters.length);
            if (filters != null) {
                for (ItemStack filter : filters) {
                    output.writeObject(filter);
                }
            }
            return bytes.toByteArray();
        }
    }

    private ItemStack[] deserializeFilters(byte[] bytes) throws IOException {
        ItemStack[] filters = new ItemStack[9];
        if (bytes == null || bytes.length == 0) {
            return filters;
        }
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            int length = Math.min(input.readInt(), filters.length);
            for (int i = 0; i < length; i++) {
                Object value = input.readObject();
                if (value instanceof ItemStack item) {
                    filters[i] = item;
                }
            }
        } catch (ClassNotFoundException ex) {
            throw new IOException("Could not read cargo filter item", ex);
        }
        return filters;
    }

    private int fingerprint(CargoBlockRecord record) {
        int result = record.key().hashCode();
        result = 31 * result + record.type().hashCode();
        result = 31 * result + record.managerId().hashCode();
        result = 31 * result + (record.ownerId() == null ? 0 : record.ownerId().hashCode());
        result = 31 * result + Long.hashCode(record.createdAtMillis());
        result = 31 * result + record.attachedFace().hashCode();
        result = 31 * result + record.channel();
        result = 31 * result + Boolean.hashCode(record.roundRobin());
        result = 31 * result + Boolean.hashCode(record.smartFill());
        result = 31 * result + Boolean.hashCode(record.whitelist());
        result = 31 * result + Boolean.hashCode(record.includeLore());
        result = 31 * result + Boolean.hashCode(record.filterDurability());
        result = 31 * result + java.util.Arrays.deepHashCode(record.filters());
        return result;
    }

    private UUID parseUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private void ensureOpen() {
        if (connection == null) {
            throw new IllegalStateException("Cargo block database is not open");
        }
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException, IOException;
    }
}
