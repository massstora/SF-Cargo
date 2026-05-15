package com.massstora.sfcargo.config;

import com.massstora.sfcargo.block.CargoBlockType;
import com.massstora.sfcargo.block.CargoItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class RecipeRegistrar {
    private final JavaPlugin plugin;
    private final CargoItems items;

    public RecipeRegistrar(JavaPlugin plugin, CargoItems items) {
        this.plugin = plugin;
        this.items = items;
    }

    public List<NamespacedKey> registerAll() {
        int registered = 0;
        List<NamespacedKey> keys = new ArrayList<>();
        for (CargoBlockType type : CargoBlockType.values()) {
            NamespacedKey key = register(type);
            if (key != null) {
                registered++;
                keys.add(key);
            }
        }
        plugin.getLogger().info("Registered " + registered + " vanilla crafting table cargo recipes.");
        return keys;
    }

    private NamespacedKey register(CargoBlockType type) {
        String base = "recipes." + type.id();
        if (!plugin.getConfig().getBoolean(base + ".enabled", true)) {
            return null;
        }

        NamespacedKey key = new NamespacedKey(plugin, type.id());
        Bukkit.removeRecipe(key, true);
        ShapedRecipe recipe = new ShapedRecipe(key, items.create(type));
        recipe.setCategory(CraftingBookCategory.REDSTONE);
        recipe.setGroup("sf_cargo");
        java.util.List<String> shape = plugin.getConfig().getStringList(base + ".shape");
        if (shape.size() != 3 || shape.stream().anyMatch(row -> row.length() != 3)) {
            plugin.getLogger().warning("Skipping invalid " + type.id() + " recipe: shape must be exactly three rows of three characters.");
            return null;
        }
        recipe.shape(shape.toArray(String[]::new));

        ConfigurationSection ingredients = plugin.getConfig().getConfigurationSection(base + ".ingredients");
        if (ingredients != null) {
            for (String symbol : ingredients.getKeys(false)) {
                Material material = Material.matchMaterial(ingredients.getString(symbol, ""));
                if (material != null && symbol.length() == 1) {
                    recipe.setIngredient(symbol.charAt(0), material);
                } else {
                    plugin.getLogger().warning("Ignoring invalid ingredient '" + symbol + "' in " + type.id() + " recipe.");
                }
            }
        }
        boolean added = Bukkit.addRecipe(recipe, true);
        if (!added) {
            plugin.getLogger().warning("Bukkit rejected cargo recipe " + key + ".");
            return null;
        }
        return key;
    }
}
