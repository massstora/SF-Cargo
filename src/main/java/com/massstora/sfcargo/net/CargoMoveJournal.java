package com.massstora.sfcargo.net;

import com.massstora.sfcargo.storage.BlockKey;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class CargoMoveJournal {
    private final ConcurrentHashMap<UUID, Move> active = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<RestoreRequest> pendingRestores = new ConcurrentLinkedQueue<>();

    Move planned(BlockKey source, int sourceSlot, ItemStack item) {
        Move move = new Move(UUID.randomUUID(), source, sourceSlot, item.clone(), MoveState.PLANNED);
        active.put(move.id(), move);
        return move;
    }

    void withdrawn(Move move) {
        active.computeIfPresent(move.id(), (ignored, current) -> current.withState(MoveState.WITHDRAWN));
    }

    void completed(Move move) {
        active.remove(move.id());
    }

    void aborted(Move move) {
        active.remove(move.id());
    }

    void queueRestore(Move move, Location location, ItemStack item) {
        active.computeIfPresent(move.id(), (ignored, current) -> current.withState(MoveState.ROLLBACK_PENDING));
        pendingRestores.add(new RestoreRequest(move.id(), location, move.sourceSlot(), item.clone()));
    }

    RestoreRequest pollRestore() {
        return pendingRestores.poll();
    }

    void restoreCompleted(UUID moveId) {
        active.remove(moveId);
    }

    int activeMoves() {
        return active.size();
    }

    int pendingRestores() {
        return pendingRestores.size();
    }

    enum MoveState {
        PLANNED,
        WITHDRAWN,
        ROLLBACK_PENDING
    }

    record Move(UUID id, BlockKey source, int sourceSlot, ItemStack item, MoveState state) {
        Move withState(MoveState state) {
            return new Move(id, source, sourceSlot, item, state);
        }
    }

    record RestoreRequest(UUID moveId, Location location, int slot, ItemStack item) {
    }
}
