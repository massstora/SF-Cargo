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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CargoTransporter {
    private final CargoScheduler scheduler;
    private final Logger logger;
    private final boolean deleteExcessItems;
    private final CoreProtectHook coreProtect;
    private final ConcurrentHashMap<BlockKey, Integer> roundRobin = new ConcurrentHashMap<>();
    private final CargoMoveJournal journal = new CargoMoveJournal();

    public CargoTransporter(CargoScheduler scheduler, Logger logger, boolean deleteExcessItems, CoreProtectHook coreProtect) {
        this.scheduler = scheduler;
        this.logger = logger;
        this.deleteExcessItems = deleteExcessItems;
        this.coreProtect = coreProtect;
    }

    public CompletableFuture<Void> route(CargoNetworkSnapshot network) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (CargoBlockRecord input : network.inputs()) {
            chain = chain.thenCompose(ignored -> routeInput(input, network.outputsFor(input.channel())));
        }
        return chain.exceptionally(throwable -> {
            logger.log(Level.WARNING, "Cargo network tick failed", throwable);
            return null;
        });
    }

    private CompletableFuture<Void> routeInput(CargoBlockRecord input, List<CargoBlockRecord> outputs) {
        if (outputs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Location inputLocation = input.attachedKey().toLocation();
        List<CargoBlockRecord> orderedOutputs = orderedOutputs(input, outputs);
        return scheduler.supplyAt(inputLocation, () -> findCandidate(input, inputLocation))
            .thenCompose(candidate -> {
                if (candidate == null || candidate.stack() == null) {
                    return CompletableFuture.completedFuture(null);
                }
                return availableCapacity(orderedOutputs, candidate.stack(), input.smartFill())
                    .thenCompose(capacity -> {
                        if (capacity <= 0) {
                            return CompletableFuture.completedFuture(null);
                        }
                        CargoMoveJournal.Move move = journal.planned(input.attachedKey(), candidate.slot(), candidate.stack());
                        return scheduler.supplyAt(inputLocation, () -> withdraw(inputLocation, candidate, capacity))
                            .thenApply(withdrawal -> {
                                if (withdrawal == null) {
                                    journal.aborted(move);
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

    public long completedJournalMoves() {
        return journal.completedMoves();
    }

    public long rollbackQueuedJournalMoves() {
        return journal.rollbackQueuedMoves();
    }

    private Candidate findCandidate(CargoBlockRecord input, Location location) {
        Inventory inv = inventoryAt(location);
        if (inv == null) {
            return null;
        }
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (CargoFilter.matches(input, stack)) {
                return new Candidate(slot, stack.clone());
            }
        }
        return null;
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

    private record Candidate(int slot, ItemStack stack) {
    }

    private record Withdrawal(int slot, ItemStack stack, CargoMoveJournal.Move move) {
        Withdrawal(int slot, ItemStack stack) {
            this(slot, stack, null);
        }

        Withdrawal withMove(CargoMoveJournal.Move move) {
            return new Withdrawal(slot, stack, move);
        }
    }
}
