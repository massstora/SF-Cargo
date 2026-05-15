package com.massstora.sfcargo.config;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;

public final class RecipeDiscoveryListener implements Listener {
    private final Plugin plugin;
    private List<NamespacedKey> recipeKeys;

    public RecipeDiscoveryListener(Plugin plugin, List<NamespacedKey> recipeKeys) {
        this.plugin = plugin;
        this.recipeKeys = List.copyOf(recipeKeys);
    }

    public void recipeKeys(List<NamespacedKey> recipeKeys) {
        this.recipeKeys = List.copyOf(recipeKeys);
    }

    public void discoverForOnlinePlayers(Collection<? extends Player> players) {
        for (Player player : players) {
            discover(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        discover(event.getPlayer());
    }

    private void discover(Player player) {
        if (recipeKeys.isEmpty()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> player.discoverRecipes(recipeKeys), null);
    }
}
