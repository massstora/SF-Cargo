package com.massstora.sfcargo.block;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class CargoItems {
    private final NamespacedKey typeKey;

    public CargoItems(JavaPlugin plugin) {
        this.typeKey = new NamespacedKey(plugin, "cargo_type");
    }

    public ItemStack create(CargoBlockType type) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + type.displayName());
        meta.setLore(List.of(ChatColor.GRAY + "SF-Cargo network component"));
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setPlayerProfile(createProfile(type));
        }
        item.setItemMeta(meta);
        return item;
    }

    public CargoBlockType readType(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : CargoBlockType.fromId(id);
    }

    public void markBlock(Block block, CargoBlockType type) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tileState)) {
            return;
        }
        tileState.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        tileState.update(false, false);
    }

    public CargoBlockType readBlockType(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tileState)) {
            return null;
        }
        String id = tileState.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : CargoBlockType.fromId(id);
    }

    private PlayerProfile createProfile(CargoBlockType type) {
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(("sf-cargo:" + type.id()).getBytes(StandardCharsets.UTF_8)));
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + type.textureHash() + "\"}}}";
        String texture = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        profile.setProperty(new ProfileProperty("textures", texture));
        return profile;
    }
}
