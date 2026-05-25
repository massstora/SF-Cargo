package com.massstora.sfcargo.net;

import com.massstora.sfcargo.block.CargoContainers;
import com.massstora.sfcargo.integration.CoreProtectHook;
import com.massstora.sfcargo.scheduler.CargoScheduler;
import com.massstora.sfcargo.storage.BlockKey;
import com.massstora.sfcargo.storage.CargoBlockRecord;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CargoTransporter {
    private final CargoScheduler scheduler;
    private final Logger logger;
    private final boolean deleteExcessItems;
    private final CoreProtectHook coreProtect;
    private final int maxBlockedInputSlotsPerManagerChannel;
    private final ConcurrentHashMap<BlockKey, Integer> roundRobin = new ConcurrentHashMap<>();
    private final CargoMoveJournal journal = new CargoMoveJournal();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<QueueKey, CargoQueueEntry>> blockedQueue = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, CargoQueueCap>> cappedChannels = new ConcurrentHashMap<>();

    public CargoTransporter(CargoScheduler scheduler, Logger logger, boolean deleteExcessItems, CoreProtectHook coreProtect, int maxBlockedInputSlotsPerManagerChannel) {
        this.scheduler = scheduler;
        this.logger = logger;
        this.deleteExcessItems = deleteExcessItems;
        this.coreProtect = coreProtect;
        this.maxBlockedInputSlotsPerManagerChannel = Math.max(0, maxBlockedInputSlotsPerManagerChannel);
    }

    public CompletableFuture<Void> route(CargoNetworkSnapshot network, boolean rescanCappedChannels) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        UUID managerId = network.manager().managerId();
        if (rescanCappedChannels) {
            cappedChannels.remove(managerId);
        }
        for (CargoBlockRecord input : network.inputs()) {
            chain = chain.thenCompose(ignored -> {
                if (blockedChannelFull(managerId, input.channel()) && !rescanCappedChannels) {
                    markChannelCapped(managerId, input.channel());
                    return CompletableFuture.completedFuture(null);
                }
                if (blockedChannelFull(managerId, input.channel()) && blockedInputCount(managerId, input) == 0) {
                    markChannelCapped(managerId, input.channel());
                    return CompletableFuture.completedFuture(null);
                }
                return routeInput(managerId, input, network.outputsFor(input.channel()));
            });
        }
        return chain.exceptionally(throwable -> {
            logger.log(Level.WARNING, "Cargo network tick failed", throwable);
            return null;
        });
    }

    private CompletableFuture<Void> routeInput(UUID managerId, CargoBlockRecord input, List<CargoBlockRecord> outputs) {
        Location inputLocation = input.attachedKey().toLocation();
        CargoQueueEntry.QueueReason inputLocationReason = blockedInputReason(inputLocation);
        if (inputLocationReason != null) {
            updateBlockedInput(managerId, input, inputLocationReason, inputLocationReason.display());
            return CompletableFuture.completedFuture(null);
        }

        List<CargoBlockRecord> orderedOutputs = orderedOutputs(input, outputs);
        return scheduler.supplyAt(inputLocation, () -> findCandidates(input, inputLocation, 1))
            .thenCompose(result -> {
                if (result == null || !result.reachable()) {
                    updateBlockedInput(managerId, input, CargoQueueEntry.QueueReason.INPUT_UNREACHABLE, CargoQueueEntry.QueueReason.INPUT_UNREACHABLE.display());
                    return CompletableFuture.<Withdrawal>completedFuture(null);
                }
                Candidate candidate = result.first();
                if (candidate == null || candidate.stack() == null) {
                    clearBlockedInput(managerId, input);
                    return CompletableFuture.<Withdrawal>completedFuture(null);
                }
                if (outputs.isEmpty()) {
                    return recordBlockedCandidates(managerId, input, inputLocation, CargoQueueEntry.QueueReason.NO_OUTPUTS, "No output is connected on channel " + input.channel() + ".")
                        .thenApply(ignored -> null);
                }
                return availableCapacity(orderedOutputs, candidate.stack(), input.smartFill())
                    .thenCompose(capacity -> {
                        if (capacity <= 0) {
                            return diagnoseOutputs(orderedOutputs, candidate.stack(), input.smartFill())
                                .thenCompose(reason -> recordBlockedCandidates(managerId, input, inputLocation, reason.reason(), reason.detail())
                                .thenApply(ignored -> {
                                    return null;
                                }));
                        }
                        clearBlocked(managerId, input, candidate);
                        CargoMoveJournal.Move move = journal.planned(input.attachedKey(), candidate.slot(), candidate.stack());
                        return scheduler.supplyAt(inputLocation, () -> withdraw(inputLocation, candidate, capacity))
                            .thenApply(withdrawal -> {
                                if (withdrawal == null) {
                                    journal.aborted(move);
                                    recordBlocked(managerId, input, candidate, CargoQueueEntry.QueueReason.INPUT_UNREACHABLE, "The input inventory changed before withdrawal.");
                                    return null;
                                }
                                journal.withdrawn(move);
                                return withdrawal.withMove(move);
                            });
                    });
            })
            .thenCompose(withdrawal -> {
                if (withdrawal == null || withdrawal.stack() == null || withdrawal.stack().getAmount() <= 0) {
                    return CompletableFuture.completedFuture(null);
                }
                return distribute(input, orderedOutputs, withdrawal.stack())
                    .thenCompose(leftover -> finishMove(withdrawal.move(), inputLocation, withdrawal.slot(), leftover));
            });
    }

    private List<CargoBlockRecord> orderedOutputs(CargoBlockRecord input, List<CargoBlockRecord> outputs) {
        if (!input.roundRobin() || outputs.size() < 2) {
            return outputs;
        }

        int start = Math.floorMod(roundRobin.getOrDefault(input.key(), 0), outputs.size());
        ArrayList<CargoBlockRecord> ordered = new ArrayList<>(outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            ordered.add(outputs.get((start + i) % outputs.size()));
        }
        return ordered;
    }

    private CompletableFuture<ItemStack> distribute(CargoBlockRecord input, List<CargoBlockRecord> outputs, ItemStack stack) {
        CompletableFuture<ItemStack> chain = CompletableFuture.completedFuture(stack);
        ArrayDeque<CargoBlockRecord> remainingOutputs = new ArrayDeque<>(outputs);

        while (!remainingOutputs.isEmpty()) {
            CargoBlockRecord output = remainingOutputs.poll();
            chain = chain.thenCompose(item -> {
                if (item == null || item.getAmount() <= 0) {
                    return CompletableFuture.completedFuture(null);
                }
                Location outputLocation = output.attachedKey().toLocation();
                return scheduler.supplyAt(outputLocation, () -> insert(outputLocation, item, input.smartFill()))
                    .thenApply(leftover -> {
                        if (leftover == null && input.roundRobin()) {
                            int index = outputs.indexOf(output);
                            roundRobin.put(input.key(), (index + 1) % outputs.size());
                        }
                        return leftover;
                    });
            });
        }

        return chain;
    }

    private CompletableFuture<Integer> availableCapacity(List<CargoBlockRecord> outputs, ItemStack stack, boolean smartFill) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (CargoBlockRecord output : outputs) {
            chain = chain.thenCompose(total -> {
                if (total >= stack.getAmount()) {
                    return CompletableFuture.completedFuture(total);
                }
                Location outputLocation = output.attachedKey().toLocation();
                return scheduler.supplyAt(outputLocation, () -> capacityAt(outputLocation, stack, smartFill))
                    .thenApply(capacity -> total + Math.min(stack.getAmount() - total, capacity == null ? 0 : capacity));
            });
        }
        return chain;
    }

    private CompletableFuture<Void> finishMove(CargoMoveJournal.Move move, Location inputLocation, int slot, ItemStack leftover) {
        if (leftover == null || leftover.getAmount() <= 0) {
            journal.completed(move);
            return CompletableFuture.completedFuture(null);
        }

        return restoreLeftover(inputLocation, slot, leftover, false).thenAccept(restored -> {
            if (restored) {
                journal.completed(move);
            } else {
                journal.queueRestore(move, inputLocation, leftover);
            }
        });
    }

    public CompletableFuture<Void> retryPendingRestores() {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        CargoMoveJournal.RestoreRequest request;
        while ((request = journal.pollRestore()) != null) {
            CargoMoveJournal.RestoreRequest current = request;
            chain = chain.thenCompose(ignored -> restoreLeftover(current.location(), current.slot(), current.item(), true)
                .thenAccept(restored -> {
                    if (restored) {
                        journal.restoreCompleted(current.moveId());
                    } else {
                        journal.queueRestore(new CargoMoveJournal.Move(current.moveId(), BlockKey.of(current.location()), current.slot(), current.item(), CargoMoveJournal.MoveState.ROLLBACK_PENDING), current.location(), current.item());
                    }
                }));
        }
        return chain;
    }

    public boolean hasJournalBacklog() {
        return journal.activeMoves() > 0 || journal.pendingRestores() > 0;
    }

    private CompletableFuture<Boolean> restoreLeftover(Location inputLocation, int slot, ItemStack leftover, boolean forceLoad) {
        if (leftover == null || leftover.getAmount() <= 0) {
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> restore = (forceLoad ? scheduler.supplyAtForceLoaded(inputLocation, () -> restoreToInventory(inputLocation, slot, leftover)) : scheduler.supplyAt(inputLocation, () -> restoreToInventory(inputLocation, slot, leftover)));
        return restore.thenApply(restored -> restored != null && restored);
    }

    private boolean restoreToInventory(Location inputLocation, int slot, ItemStack leftover) {
            Inventory inv = inventoryAt(inputLocation);
            if (inv == null) {
                return false;
            }
            ItemStack current = inv.getItem(slot);
            coreProtect.logContainerTransaction(inputLocation);
            if (current == null || current.getType().isAir()) {
                inv.setItem(slot, leftover);
            } else {
                ItemStack rest = inv.addItem(leftover).values().stream().findFirst().orElse(null);
                if (rest != null && !deleteExcessItems) {
                    inputLocation.getWorld().dropItemNaturally(inputLocation.clone().add(0.5, 1.0, 0.5), rest);
                }
            }
            return true;
    }

    public int activeJournalMoves() {
        return journal.activeMoves();
    }

    public int pendingJournalRestores() {
        return journal.pendingRestores();
    }

    public int rollbackQueuedJournalMoves() {
        return journal.pendingRestores();
    }

    public List<CargoQueueEntry> queuedMoves(UUID managerId) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null) {
            return List.of();
        }
        return managerQueue.values().stream()
            .sorted(Comparator.comparingLong(CargoQueueEntry::queuedAtMillis).thenComparingLong(CargoQueueEntry::id))
            .toList();
    }

    public int queuedMoveCount() {
        int total = 0;
        for (ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue : blockedQueue.values()) {
            total += managerQueue.size();
        }
        return total;
    }

    public List<CargoQueueCap> queueCaps(UUID managerId) {
        ConcurrentHashMap<Integer, CargoQueueCap> managerCaps = cappedChannels.get(managerId);
        if (managerCaps == null) {
            return List.of();
        }
        return managerCaps.values().stream()
            .sorted(Comparator.comparingInt(CargoQueueCap::channel))
            .toList();
    }

    public Optional<CargoQueueEntry> queuedMove(UUID managerId, long id) {
        return queuedMoves(managerId).stream()
            .filter(entry -> entry.id() == id)
            .findFirst();
    }

    public int purgeQueuedMoves(UUID managerId) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> removed = blockedQueue.remove(managerId);
        cappedChannels.remove(managerId);
        return removed == null ? 0 : removed.size();
    }

    public boolean purgeQueuedMove(UUID managerId, long id) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null) {
            return false;
        }

        for (Map.Entry<QueueKey, CargoQueueEntry> entry : managerQueue.entrySet()) {
            if (entry.getValue().id() == id) {
                int channel = entry.getValue().channel();
                boolean removed = managerQueue.remove(entry.getKey(), entry.getValue());
                if (managerQueue.isEmpty()) {
                    blockedQueue.remove(managerId, managerQueue);
                }
                clearCapIfBelowLimit(managerId, channel);
                return removed;
            }
        }
        return false;
    }

    private CandidateSearch findCandidates(CargoBlockRecord input, Location location, int limit) {
        Inventory inv = inventoryAt(location);
        if (inv == null) {
            return new CandidateSearch(List.of(), false);
        }
        ArrayList<Candidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (CargoFilter.matches(input, stack)) {
                candidates.add(new Candidate(slot, stack.clone()));
                if (candidates.size() >= limit) {
                    break;
                }
            }
        }
        return new CandidateSearch(candidates, true);
    }

    private Withdrawal withdraw(Location location, Candidate candidate, int requestedAmount) {
        Inventory inv = inventoryAt(location);
        if (inv == null || requestedAmount <= 0) {
            return null;
        }

        ItemStack current = inv.getItem(candidate.slot());
        if (current == null || current.getType().isAir() || !current.isSimilar(candidate.stack())) {
            return null;
        }

        int amount = Math.min(requestedAmount, current.getAmount());
        ItemStack moving = current.clone();
        moving.setAmount(amount);
        coreProtect.logContainerTransaction(location);
        if (current.getAmount() == amount) {
            inv.setItem(candidate.slot(), null);
        } else {
            current.setAmount(current.getAmount() - amount);
            inv.setItem(candidate.slot(), current);
        }
        return new Withdrawal(candidate.slot(), moving);
    }

    private ItemStack insert(Location location, ItemStack item, boolean smartFill) {
        Inventory inv = outputInventoryAt(location);
        if (inv == null) {
            return item;
        }
        if (!canInsert(inv, item)) {
            return item;
        }

        ItemStack moving = item.clone();
        if (smartFill) {
            for (int slot = 0; slot < inv.getSize() && moving != null; slot++) {
                ItemStack current = inv.getItem(slot);
                if (current != null && current.isSimilar(moving) && current.getAmount() < current.getMaxStackSize()) {
                    coreProtect.logContainerTransaction(location);
                    int transfer = Math.min(moving.getAmount(), current.getMaxStackSize() - current.getAmount());
                    current.setAmount(current.getAmount() + transfer);
                    moving.setAmount(moving.getAmount() - transfer);
                    if (moving.getAmount() <= 0) {
                        moving = null;
                    }
                }
            }
            return moving;
        }

        coreProtect.logContainerTransaction(location);
        return inv.addItem(moving).values().stream().findFirst().orElse(null);
    }

    private int capacityAt(Location location, ItemStack item, boolean smartFill) {
        Inventory inv = outputInventoryAt(location);
        if (inv == null || !canInsert(inv, item)) {
            return 0;
        }

        int capacity = 0;
        int maxStackSize = Math.min(item.getMaxStackSize(), inv.getMaxStackSize());
        for (int slot = 0; slot < inv.getSize() && capacity < item.getAmount(); slot++) {
            ItemStack current = inv.getItem(slot);
            if (current == null || current.getType().isAir()) {
                if (!smartFill) {
                    capacity += maxStackSize;
                }
                continue;
            }
            if (current.isSimilar(item) && current.getAmount() < maxStackSize) {
                capacity += maxStackSize - current.getAmount();
            }
        }
        return Math.min(capacity, item.getAmount());
    }

    private boolean canInsert(Inventory inventory, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (inventory.getHolder() instanceof ShulkerBox && CargoContainers.isShulkerBox(item.getType())) {
            return false;
        }
        return true;
    }

    private Inventory outputInventoryAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Block block = location.getBlock();
        if (!CargoContainers.isSupportedOutput(block.getType())) {
            return null;
        }
        return inventoryAt(location);
    }

    private Inventory inventoryAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Block block = location.getBlock();
        BlockState state = block.getState();
        if (state instanceof InventoryHolder holder) {
            return holder.getInventory();
        }
        return null;
    }

    private CargoQueueEntry.QueueReason blockedInputReason(Location location) {
        if (location == null || location.getWorld() == null) {
            return CargoQueueEntry.QueueReason.INPUT_UNLOADED;
        }
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return CargoQueueEntry.QueueReason.INPUT_UNLOADED;
        }
        return null;
    }

    private CargoQueueEntry.QueueReason blockedOutputReason(Location location) {
        if (location == null || location.getWorld() == null) {
            return CargoQueueEntry.QueueReason.OUTPUT_UNLOADED;
        }
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return CargoQueueEntry.QueueReason.OUTPUT_UNLOADED;
        }
        return null;
    }

    private CompletableFuture<OutputDiagnosis> diagnoseOutputs(List<CargoBlockRecord> outputs, ItemStack stack, boolean smartFill) {
        CompletableFuture<OutputDiagnosis> chain = CompletableFuture.completedFuture(
            new OutputDiagnosis(CargoQueueEntry.QueueReason.OUTPUT_NO_SPACE, "All reachable outputs on this channel are full or cannot accept the item.")
        );

        for (CargoBlockRecord output : outputs) {
            chain = chain.thenCompose(current -> {
                if (current.reason() == CargoQueueEntry.QueueReason.OUTPUT_UNLOADED) {
                    return CompletableFuture.completedFuture(current);
                }

                Location outputLocation = output.attachedKey().toLocation();
                CargoQueueEntry.QueueReason locationReason = blockedOutputReason(outputLocation);
                if (locationReason != null) {
                    return CompletableFuture.completedFuture(new OutputDiagnosis(locationReason, locationReason.display() + " at " + output.attachedKey() + "."));
                }

                return scheduler.supplyAt(outputLocation, () -> outputStatus(outputLocation, stack, smartFill))
                    .thenApply(status -> chooseOutputDiagnosis(current, status, output));
            });
        }

        return chain;
    }

    private OutputDiagnosis chooseOutputDiagnosis(OutputDiagnosis current, OutputDiagnosis candidate, CargoBlockRecord output) {
        if (candidate == null) {
            return current;
        }
        if (candidate.reason() == CargoQueueEntry.QueueReason.OUTPUT_UNREACHABLE) {
            candidate = new OutputDiagnosis(candidate.reason(), candidate.detail() + " at " + output.attachedKey() + ".");
        }
        if (severity(candidate.reason()) > severity(current.reason())) {
            return candidate;
        }
        return current;
    }

    private int severity(CargoQueueEntry.QueueReason reason) {
        return switch (reason) {
            case OUTPUT_UNLOADED -> 3;
            case OUTPUT_UNREACHABLE -> 2;
            case OUTPUT_NO_SPACE -> 1;
            default -> 0;
        };
    }

    private OutputDiagnosis outputStatus(Location location, ItemStack stack, boolean smartFill) {
        Inventory inv = outputInventoryAt(location);
        if (inv == null || !canInsert(inv, stack)) {
            return new OutputDiagnosis(CargoQueueEntry.QueueReason.OUTPUT_UNREACHABLE, CargoQueueEntry.QueueReason.OUTPUT_UNREACHABLE.display());
        }
        return capacityAt(location, stack, smartFill) <= 0
            ? new OutputDiagnosis(CargoQueueEntry.QueueReason.OUTPUT_NO_SPACE, "All reachable outputs on this channel are full or cannot accept the item.")
            : null;
    }

    private CompletableFuture<Void> recordBlockedCandidates(UUID managerId, CargoBlockRecord input, Location inputLocation, CargoQueueEntry.QueueReason reason, String detail) {
        int candidateLimit = blockedCandidateLimit(managerId, input);
        return scheduler.supplyAt(inputLocation, () -> findCandidates(input, inputLocation, candidateLimit))
            .thenAccept(result -> {
                if (result == null || !result.reachable()) {
                    updateBlockedInput(managerId, input, CargoQueueEntry.QueueReason.INPUT_UNREACHABLE, CargoQueueEntry.QueueReason.INPUT_UNREACHABLE.display());
                    return;
                }
                recordBlocked(managerId, input, result.candidates(), reason, detail);
                reconcileBlockedInput(managerId, input, result.candidates());
            });
    }

    private void recordBlocked(UUID managerId, CargoBlockRecord input, List<Candidate> candidates, CargoQueueEntry.QueueReason reason, String detail) {
        for (Candidate candidate : candidates) {
            recordBlocked(managerId, input, candidate, reason, detail);
        }
    }

    private void recordBlocked(UUID managerId, CargoBlockRecord input, Candidate candidate, CargoQueueEntry.QueueReason reason, String detail) {
        QueueKey key = new QueueKey(input.key(), input.attachedKey(), candidate.slot(), input.channel(), candidate.stack().getType().name());
        blockedQueue.computeIfAbsent(managerId, ignored -> new ConcurrentHashMap<>())
            .compute(key, (ignored, current) -> {
                if (current == null) {
                    int id = nextManagerQueueId(managerId);
                    if (id <= 0) {
                        markChannelCapped(managerId, input.channel());
                        return null;
                    }
                    return new CargoQueueEntry(id, managerId, input.attachedKey(), candidate.slot(), input.channel(), candidate.stack(), System.currentTimeMillis(), System.currentTimeMillis(), reason, detail);
                }
                return current.withUpdate(candidate.stack(), reason, detail);
            });
    }

    private int nextManagerQueueId(UUID managerId) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null || managerQueue.isEmpty()) {
            return 1;
        }

        boolean[] used = new boolean[10_000];
        for (CargoQueueEntry entry : managerQueue.values()) {
            if (entry.id() > 0 && entry.id() < used.length) {
                used[(int) entry.id()] = true;
            }
        }
        for (int id = 1; id < used.length; id++) {
            if (!used[id]) {
                return id;
            }
        }
        return -1;
    }

    private int blockedCandidateLimit(UUID managerId, CargoBlockRecord input) {
        if (maxBlockedInputSlotsPerManagerChannel <= 0) {
            return Integer.MAX_VALUE;
        }
        int existingInput = blockedInputCount(managerId, input);
        int remainingChannel = Math.max(1, maxBlockedInputSlotsPerManagerChannel - blockedChannelCount(managerId, input.channel()));
        return existingInput + remainingChannel;
    }

    private boolean blockedChannelFull(UUID managerId, int channel) {
        return maxBlockedInputSlotsPerManagerChannel > 0 && blockedChannelCount(managerId, channel) >= maxBlockedInputSlotsPerManagerChannel;
    }

    private int blockedChannelCount(UUID managerId, int channel) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null) {
            return 0;
        }
        int count = 0;
        for (CargoQueueEntry entry : managerQueue.values()) {
            if (entry.channel() == channel) {
                count++;
            }
        }
        return count;
    }

    private int blockedInputCount(UUID managerId, CargoBlockRecord input) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null) {
            return 0;
        }
        int count = 0;
        for (QueueKey key : managerQueue.keySet()) {
            if (key.inputBlock().equals(input.key())) {
                count++;
            }
        }
        return count;
    }

    private void markChannelCapped(UUID managerId, int channel) {
        int count = blockedChannelCount(managerId, channel);
        cappedChannels.computeIfAbsent(managerId, ignored -> new ConcurrentHashMap<>())
            .put(channel, new CargoQueueCap(managerId, channel, count, maxBlockedInputSlotsPerManagerChannel));
    }

    private void clearCapIfBelowLimit(UUID managerId, int channel) {
        if (!blockedChannelFull(managerId, channel)) {
            ConcurrentHashMap<Integer, CargoQueueCap> managerCaps = cappedChannels.get(managerId);
            if (managerCaps != null) {
                managerCaps.remove(channel);
                if (managerCaps.isEmpty()) {
                    cappedChannels.remove(managerId, managerCaps);
                }
            }
        }
    }

    private void updateBlockedInput(UUID managerId, CargoBlockRecord input, CargoQueueEntry.QueueReason reason, String detail) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null) {
            return;
        }
        managerQueue.replaceAll((key, entry) -> key.inputBlock().equals(input.key()) ? entry.withUpdate(entry.item(), reason, detail) : entry);
    }

    private void clearBlocked(UUID managerId, CargoBlockRecord input, Candidate candidate) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null) {
            return;
        }
        managerQueue.remove(new QueueKey(input.key(), input.attachedKey(), candidate.slot(), input.channel(), candidate.stack().getType().name()));
        if (managerQueue.isEmpty()) {
            blockedQueue.remove(managerId, managerQueue);
        }
    }

    private void reconcileBlockedInput(UUID managerId, CargoBlockRecord input, List<Candidate> candidates) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null) {
            return;
        }
        java.util.HashSet<QueueKey> currentKeys = new java.util.HashSet<>();
        for (Candidate candidate : candidates) {
            currentKeys.add(new QueueKey(input.key(), input.attachedKey(), candidate.slot(), input.channel(), candidate.stack().getType().name()));
        }
        managerQueue.entrySet().removeIf(entry -> entry.getKey().inputBlock().equals(input.key()) && !currentKeys.contains(entry.getKey()));
        if (managerQueue.isEmpty()) {
            blockedQueue.remove(managerId, managerQueue);
        }
        clearCapIfBelowLimit(managerId, input.channel());
    }

    private void clearBlockedInput(UUID managerId, CargoBlockRecord input) {
        ConcurrentHashMap<QueueKey, CargoQueueEntry> managerQueue = blockedQueue.get(managerId);
        if (managerQueue == null) {
            return;
        }
        managerQueue.entrySet().removeIf(entry -> entry.getKey().inputBlock().equals(input.key()));
        if (managerQueue.isEmpty()) {
            blockedQueue.remove(managerId, managerQueue);
        }
    }

    private record Candidate(int slot, ItemStack stack) {
    }

    private record CandidateSearch(List<Candidate> candidates, boolean reachable) {
        Candidate first() {
            return candidates.isEmpty() ? null : candidates.get(0);
        }
    }

    private record Withdrawal(int slot, ItemStack stack, CargoMoveJournal.Move move) {
        Withdrawal(int slot, ItemStack stack) {
            this(slot, stack, null);
        }

        Withdrawal withMove(CargoMoveJournal.Move move) {
            return new Withdrawal(slot, stack, move);
        }
    }

    private record QueueKey(BlockKey inputBlock, BlockKey inventoryBlock, int slot, int channel, String itemType) {
    }

    private record OutputDiagnosis(CargoQueueEntry.QueueReason reason, String detail) {
    }
}
