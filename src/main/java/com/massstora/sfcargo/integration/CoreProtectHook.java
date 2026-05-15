package com.massstora.sfcargo.integration;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

public final class CoreProtectHook {
    private final JavaPlugin plugin;
    private final Object api;
    private final Method logContainerTransaction;
    private final String user;

    private CoreProtectHook(JavaPlugin plugin, Object api, Method logContainerTransaction, String user) {
        this.plugin = plugin;
        this.api = api;
        this.logContainerTransaction = logContainerTransaction;
        this.user = user;
    }

    public static CoreProtectHook create(JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("coreprotect.enabled", true)) {
            return disabled(plugin);
        }

        Plugin coreProtect = plugin.getServer().getPluginManager().getPlugin("CoreProtect");
        if (coreProtect == null || !coreProtect.isEnabled()) {
            plugin.getLogger().info("CoreProtect not found; cargo container transactions will not be logged to CoreProtect.");
            return disabled(plugin);
        }

        try {
            Object api = coreProtect.getClass().getMethod("getAPI").invoke(coreProtect);
            Method isEnabled = api.getClass().getMethod("isEnabled");
            Method apiVersion = api.getClass().getMethod("APIVersion");
            if (!Boolean.TRUE.equals(isEnabled.invoke(api))) {
                plugin.getLogger().info("CoreProtect API is disabled; cargo transactions will not be logged to CoreProtect.");
                return disabled(plugin);
            }
            int version = ((Number) apiVersion.invoke(api)).intValue();
            if (version < 9) {
                plugin.getLogger().warning("CoreProtect API v" + version + " is too old for container transaction logging.");
                return disabled(plugin);
            }

            Method logContainerTransaction = api.getClass().getMethod("logContainerTransaction", String.class, Location.class);
            String user = plugin.getConfig().getString("coreprotect.user", "#sfcargo");
            plugin.getLogger().info("CoreProtect container transaction logging enabled as " + user + ".");
            return new CoreProtectHook(plugin, api, logContainerTransaction, user);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not hook CoreProtect API; cargo transactions will not be logged.", ex);
            return disabled(plugin);
        }
    }

    public void logContainerTransaction(Location location) {
        if (api == null || location == null) {
            return;
        }

        try {
            logContainerTransaction.invoke(api, user, location);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not log CoreProtect cargo transaction at " + location, ex);
        }
    }

    private static CoreProtectHook disabled(JavaPlugin plugin) {
        return new CoreProtectHook(plugin, null, null, "#sfcargo");
    }
}
