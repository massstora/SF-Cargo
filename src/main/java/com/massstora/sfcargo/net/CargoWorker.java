package com.massstora.sfcargo.net;

import com.massstora.sfcargo.block.CargoBlockType;
import com.massstora.sfcargo.storage.BlockKey;
import com.massstora.sfcargo.storage.CargoBlockRecord;
import com.massstora.sfcargo.storage.CargoStorage;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class CargoWorker {
    private final JavaPlugin plugin;
    private final CargoStorage storage;
    private final CargoNetworkDiscovery discovery;
    private final CargoTransporter transporter;
    private final long intervalMs;
    private final int discoveryIntervalLoops;
    private final AtomicBoolean planning = new AtomicBoolean();
    private final AtomicBoolean transportInFlight = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private int loopsUntilDiscovery;
    private java.util.List<CargoNetworkSnapshot> cachedSnapshots = java.util.List.of();
    private Map<BlockKey, Integer> cachedEndpointClaims = Map.of();
    private long loopSequence;
    private long tickCounter;
    private long queuedInventoryMoves;
    private long transportWaitAccumulatorNs;
    private long planningDurationAccumulatorNs;
    private long lastSecond = System.currentTimeMillis();
    private volatile int completedLoops;
    private volatile long lastQueuedInventoryMoves;
    private volatile double averageTransportWaitMs;
    private volatile double averagePlanningDurationMs;
    private volatile double lastTransportWaitMs;
    private volatile double lastPlanningDurationMs;

    public CargoWorker(JavaPlugin plugin, CargoStorage storage, CargoNetworkDiscovery discovery, CargoTransporter transporter, long intervalMs, int discoveryIntervalLoops) {
        this.plugin = plugin;
        this.storage = storage;
        this.discovery = discovery;
        this.transporter = transporter;
        this.intervalMs = Math.max(50L, intervalMs);
        this.discoveryIntervalLoops = Math.max(1, discoveryIntervalLoops);
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SF-Cargo-Worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void tick() {
        if (!plugin.isEnabled() || !planning.compareAndSet(false, true)) {
            return;
        }

        long started = System.nanoTime();
        try {
            java.util.ArrayList<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
            NetworkCache networkCache = currentNetworkCache();
            boolean rescanCappedChannels = loopSequence++ % discoveryIntervalLoops == 0L;

            if (transportInFlight.compareAndSet(false, true)) {
                futures.add(transporter.retryPendingRestores());
                if (!transporter.hasJournalBacklog()) {
                    for (CargoNetworkSnapshot snapshot : networkCache.snapshots()) {
                        if (snapshot.activeManager() && !snapshot.ownerConflict() && !hasSharedEndpoint(snapshot, networkCache.endpointClaims())) {
                            futures.add(transporter.route(snapshot, rescanCappedChannels));
                        }
                    }
                }
                long transportStarted = System.nanoTime();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, throwable) -> {
                    recordTransportWait(transportStarted);
                    transportInFlight.set(false);
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "SF-Cargo transport tick failed", throwable);
                    }
                });
            } else {
                queuedInventoryMoves++;
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "SF-Cargo worker tick failed", ex);
        } finally {
            recordPlanningLoop(System.nanoTime() - started);
            planning.set(false);
        }
    }

    private NetworkCache currentNetworkCache() {
        if (loopsUntilDiscovery-- > 0 && !cachedSnapshots.isEmpty()) {
            return new NetworkCache(cachedSnapshots, cachedEndpointClaims);
        }

        java.util.ArrayList<CargoNetworkSnapshot> snapshots = new java.util.ArrayList<>();
        for (CargoBlockRecord manager : storage.managers()) {
            if (manager.type() != CargoBlockType.MANAGER) {
                continue;
            }
            CargoNetworkSnapshot snapshot = discovery.discover(manager);
            snapshots.add(snapshot);
        }

        Map<BlockKey, Integer> endpointClaims = new HashMap<>();
        for (CargoNetworkSnapshot snapshot : snapshots) {
            if (!snapshot.activeManager() || snapshot.ownerConflict()) {
                continue;
            }
            for (CargoBlockRecord input : snapshot.inputs()) {
                endpointClaims.merge(input.key(), 1, Integer::sum);
            }
            for (java.util.List<CargoBlockRecord> outputs : snapshot.outputs().values()) {
                for (CargoBlockRecord output : outputs) {
                    endpointClaims.merge(output.key(), 1, Integer::sum);
                }
            }
        }

        cachedSnapshots = java.util.List.copyOf(snapshots);
        cachedEndpointClaims = Map.copyOf(endpointClaims);
        loopsUntilDiscovery = discoveryIntervalLoops - 1;
        return new NetworkCache(cachedSnapshots, cachedEndpointClaims);
    }

    private void recordPlanningLoop(long planningDurationNs) {
        long now = System.currentTimeMillis();
        lastPlanningDurationMs = planningDurationNs / 1_000_000.0D;
        planningDurationAccumulatorNs += planningDurationNs;
        tickCounter++;
        if (now - lastSecond >= 1000L) {
            completedLoops = (int) tickCounter;
            lastQueuedInventoryMoves = queuedInventoryMoves;
            averagePlanningDurationMs = tickCounter == 0 ? 0.0D : planningDurationAccumulatorNs / 1_000_000.0D / tickCounter;
            averageTransportWaitMs = tickCounter == 0 ? 0.0D : transportWaitAccumulatorNs / 1_000_000.0D / tickCounter;
            tickCounter = 0;
            queuedInventoryMoves = 0L;
            transportWaitAccumulatorNs = 0L;
            planningDurationAccumulatorNs = 0L;
            lastSecond = now;
        }
    }

    private void recordTransportWait(long started) {
        long waitNs = Math.max(0L, System.nanoTime() - started);
        lastTransportWaitMs = waitNs / 1_000_000.0D;
        transportWaitAccumulatorNs += waitNs;
    }

    public int completedLoops() {
        return completedLoops;
    }

    public int expectedLoops() {
        return Math.max(1, (int) Math.round(1000.0D / intervalMs));
    }

    public double pluginTps() {
        return completedLoops;
    }

    public double targetTps() {
        return 1000.0D / intervalMs;
    }

    public long intervalMs() {
        return intervalMs;
    }

    public int discoveryIntervalLoops() {
        return discoveryIntervalLoops;
    }

    public double lastTransportWaitMs() {
        return lastTransportWaitMs;
    }

    public double averageTransportWaitMs() {
        return averageTransportWaitMs;
    }

    public double lastPlanningDurationMs() {
        return lastPlanningDurationMs;
    }

    public double averagePlanningDurationMs() {
        return averagePlanningDurationMs;
    }

    public long queuedInventoryMoves() {
        return lastQueuedInventoryMoves;
    }

    public int activeJournalMoves() {
        return transporter.activeJournalMoves();
    }

    public int pendingJournalRestores() {
        return transporter.pendingJournalRestores();
    }

    public int rollbackQueuedJournalMoves() {
        return transporter.rollbackQueuedJournalMoves();
    }

    public List<CargoQueueEntry> queuedMoves(UUID managerId) {
        return transporter.queuedMoves(managerId);
    }

    public int queuedMoveCount() {
        return transporter.queuedMoveCount();
    }

    public List<CargoQueueCap> queueCaps(UUID managerId) {
        return transporter.queueCaps(managerId);
    }

    public boolean hasManagerQueueIssues(UUID managerId) {
        return !queuedMoves(managerId).isEmpty() || !queueCaps(managerId).isEmpty();
    }

    public Optional<CargoQueueEntry> queuedMove(UUID managerId, long id) {
        return transporter.queuedMove(managerId, id);
    }

    public int purgeQueuedMoves(UUID managerId) {
        return transporter.purgeQueuedMoves(managerId);
    }

    public boolean purgeQueuedMove(UUID managerId, long id) {
        return transporter.purgeQueuedMove(managerId, id);
    }

    private boolean hasSharedEndpoint(CargoNetworkSnapshot snapshot, Map<BlockKey, Integer> endpointClaims) {
        for (CargoBlockRecord input : snapshot.inputs()) {
            if (endpointClaims.getOrDefault(input.key(), 0) > 1) {
                return true;
            }
        }
        for (java.util.List<CargoBlockRecord> outputs : snapshot.outputs().values()) {
            for (CargoBlockRecord output : outputs) {
                if (endpointClaims.getOrDefault(output.key(), 0) > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private record NetworkCache(java.util.List<CargoNetworkSnapshot> snapshots, Map<BlockKey, Integer> endpointClaims) {
    }
}
