package com.massstora.sfcargo.storage;

import com.massstora.sfcargo.block.CargoBlockType;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class CargoStorage {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<BlockKey, CargoBlockRecord> blocks = new ConcurrentHashMap<>();

    public CargoStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "blocks.yml");
    }

    public void load() {
        blocks.clear();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("blocks");
        if (section == null) {
            return;
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
                record.filters(section.getList(path + ".filter.items", java.util.List.of()).toArray(org.bukkit.inventory.ItemStack[]::new));
                blocks.put(record.key(), record);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Skipping corrupt cargo block record: " + path, ex);
            }
        }
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (CargoBlockRecord record : blocks.values()) {
            String path = "blocks." + record.key().path();
            config.set(path + ".type", record.type().id());
            config.set(path + ".manager-id", record.managerId().toString());
            config.set(path + ".owner-id", record.ownerId() == null ? null : record.ownerId().toString());
            config.set(path + ".created-at", record.createdAtMillis());
            config.set(path + ".attached-face", record.attachedFace().name());
            config.set(path + ".channel", record.channel());
            config.set(path + ".round-robin", record.roundRobin());
            config.set(path + ".smart-fill", record.smartFill());
            config.set(path + ".filter.whitelist", record.whitelist());
            config.set(path + ".filter.include-lore", record.includeLore());
            config.set(path + ".filter.durability", record.filterDurability());
            config.set(path + ".filter.items", java.util.Arrays.asList(record.filters()));
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save cargo block data", ex);
        }
    }

    public CargoBlockRecord get(BlockKey key) {
        return blocks.get(key);
    }

    public void put(CargoBlockRecord record) {
        blocks.put(record.key(), record);
    }

    public CargoBlockRecord remove(BlockKey key) {
        return blocks.remove(key);
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

    private UUID parseUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
