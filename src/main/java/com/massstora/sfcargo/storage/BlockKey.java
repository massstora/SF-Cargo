package com.massstora.sfcargo.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.UUID;

public record BlockKey(UUID worldId, int x, int y, int z) {
    public static BlockKey of(Location location) {
        return new BlockKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Location toLocation() {
        World world = Bukkit.getWorld(worldId);
        return world == null ? null : new Location(world, x, y, z);
    }

    public BlockKey offset(int dx, int dy, int dz) {
        return new BlockKey(worldId, x + dx, y + dy, z + dz);
    }

    public String path() {
        return worldId + "," + x + "," + y + "," + z;
    }

    public static BlockKey parse(String value) {
        String[] parts = value.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid block key: " + value);
        }
        return new BlockKey(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    @Override
    public String toString() {
        return path();
    }

    public boolean sameWorld(BlockKey other) {
        return other != null && Objects.equals(worldId, other.worldId);
    }
}
