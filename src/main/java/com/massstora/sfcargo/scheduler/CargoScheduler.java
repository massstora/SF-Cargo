package com.massstora.sfcargo.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class CargoScheduler {
    private final Plugin plugin;
    private final Object regionScheduler;
    private final Method regionExecute;
    private final boolean folia;

    public CargoScheduler(Plugin plugin) {
        this.plugin = plugin;

        Object foundScheduler = null;
        Method foundExecute = null;
        boolean foundFolia = false;
        try {
            Method getter = Bukkit.getServer().getClass().getMethod("getRegionScheduler");
            foundScheduler = getter.invoke(Bukkit.getServer());
            foundExecute = foundScheduler.getClass().getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
            foundFolia = true;
        } catch (ReflectiveOperationException ignored) {
            // Paper fallback.
        }

        this.regionScheduler = foundScheduler;
        this.regionExecute = foundExecute;
        this.folia = foundFolia;
    }

    public boolean folia() {
        return folia;
    }

    public boolean runAt(Location location, Runnable runnable) {
        if (location == null || location.getWorld() == null || !plugin.isEnabled()) {
            return false;
        }

        if (folia) {
            try {
                regionExecute.invoke(regionScheduler, plugin, location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, runnable);
                return true;
            } catch (ReflectiveOperationException ex) {
                plugin.getLogger().log(Level.SEVERE, "Failed to schedule Folia region task", ex);
                return false;
            }
        }

        Bukkit.getScheduler().runTask(plugin, runnable);
        return true;
    }

    public <T> CompletableFuture<T> supplyAt(Location location, RegionSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        boolean scheduled = runAt(location, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        if (!scheduled) {
            future.complete(null);
        }
        return future;
    }

    public <T> CompletableFuture<T> supplyAtForceLoaded(Location location, RegionSupplier<T> supplier) {
        if (location == null || location.getWorld() == null || !plugin.isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return world.getChunkAtAsync(chunkX, chunkZ, true)
            .exceptionally(throwable -> null)
            .thenCompose(chunk -> {
                if (chunk == null) {
                    return CompletableFuture.completedFuture(null);
                }
                addTicket(chunk);
                return supplyAt(location, supplier).whenComplete((ignored, throwable) -> removeTicket(chunk));
            });
    }

    private void addTicket(Chunk chunk) {
        try {
            chunk.addPluginChunkTicket(plugin);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not add temporary SF-Cargo chunk ticket", ex);
        }
    }

    private void removeTicket(Chunk chunk) {
        try {
            chunk.removePluginChunkTicket(plugin);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not remove temporary SF-Cargo chunk ticket", ex);
        }
    }

    @FunctionalInterface
    public interface RegionSupplier<T> {
        T get();
    }
}
