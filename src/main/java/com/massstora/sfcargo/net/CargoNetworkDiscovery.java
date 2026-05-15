package com.massstora.sfcargo.net;

import com.massstora.sfcargo.block.CargoBlockType;
import com.massstora.sfcargo.storage.BlockKey;
import com.massstora.sfcargo.storage.CargoBlockRecord;
import com.massstora.sfcargo.storage.CargoStorage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CargoNetworkDiscovery {
    private static final int RANGE = 5;
    private static final int[][] DIRECTIONS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final CargoStorage storage;
    private final int maxNodes;

    public CargoNetworkDiscovery(CargoStorage storage, int maxNodes) {
        this.storage = storage;
        this.maxNodes = Math.max(1, maxNodes);
    }

    public CargoNetworkSnapshot discover(CargoBlockRecord manager) {
        CargoNetworkSnapshot snapshot = new CargoNetworkSnapshot(manager, false);
        ArrayDeque<BlockKey> queue = new ArrayDeque<>();
        Set<BlockKey> visited = new HashSet<>();
        queue.add(manager.key());
        visited.add(manager.key());

        boolean multipleManagers = false;
        boolean ownerConflict = false;
        int steps = 0;
        while (!queue.isEmpty() && steps++ < maxNodes) {
            BlockKey current = queue.poll();
            CargoBlockRecord record = storage.get(current);
            if (record == null) {
                continue;
            }

            if (differentKnownOwners(manager, record)) {
                ownerConflict = true;
                continue;
            }

            if (record.type() == CargoBlockType.MANAGER && !record.key().equals(manager.key())) {
                multipleManagers = true;
                continue;
            }

            if (record.type() == CargoBlockType.INPUT) {
                snapshot.inputs().add(record);
                continue;
            }

            if (record.type() == CargoBlockType.OUTPUT) {
                snapshot.outputs().computeIfAbsent(record.channel(), ignored -> new ArrayList<>()).add(record);
                continue;
            }

            discoverNeighbors(current, queue, visited);
        }

        return new CargoNetworkSnapshot(manager, snapshot.inputs(), snapshot.outputs(), multipleManagers, ownerConflict);
    }

    public CargoNetworkSummary summarize(CargoBlockRecord manager) {
        SummaryResult result = discoverSummary(manager);
        CargoNetworkSnapshot snapshot = result.snapshot();
        int[] inputsByChannel = new int[16];
        int[] outputsByChannel = new int[16];

        for (CargoBlockRecord input : snapshot.inputs()) {
            inputsByChannel[input.channel()]++;
        }

        int outputs = 0;
        for (int channel = 0; channel < outputsByChannel.length; channel++) {
            int count = snapshot.outputsFor(channel).size();
            outputsByChannel[channel] = count;
            outputs += count;
        }

        return new CargoNetworkSummary(snapshot.inputs().size(), outputs, result.connectors(), result.managers(), result.usedNodes(), maxNodes, snapshot.multipleManagers() || snapshot.ownerConflict(), inputsByChannel, outputsByChannel);
    }

    private SummaryResult discoverSummary(CargoBlockRecord manager) {
        CargoNetworkSnapshot snapshot = new CargoNetworkSnapshot(manager, false);
        ArrayDeque<BlockKey> queue = new ArrayDeque<>();
        Set<BlockKey> visited = new HashSet<>();
        queue.add(manager.key());
        visited.add(manager.key());

        boolean multipleManagers = false;
        boolean ownerConflict = false;
        int connectors = 0;
        int managers = 0;
        int steps = 0;
        while (!queue.isEmpty() && steps++ < maxNodes) {
            BlockKey current = queue.poll();
            CargoBlockRecord record = storage.get(current);
            if (record == null) {
                continue;
            }

            if (differentKnownOwners(manager, record)) {
                ownerConflict = true;
                continue;
            }

            if (record.type() == CargoBlockType.MANAGER) {
                managers++;
                if (!record.key().equals(manager.key())) {
                    multipleManagers = true;
                    continue;
                }
            }

            if (record.type() == CargoBlockType.INPUT) {
                snapshot.inputs().add(record);
                continue;
            }

            if (record.type() == CargoBlockType.OUTPUT) {
                snapshot.outputs().computeIfAbsent(record.channel(), ignored -> new ArrayList<>()).add(record);
                continue;
            }

            if (record.type() == CargoBlockType.CONNECTOR) {
                connectors++;
            }

            discoverNeighbors(current, queue, visited);
        }

        return new SummaryResult(new CargoNetworkSnapshot(manager, snapshot.inputs(), snapshot.outputs(), multipleManagers, ownerConflict), connectors, managers, visited.size());
    }

    public List<CargoBlockRecord> managersConnectedTo(BlockKey target) {
        List<CargoBlockRecord> managers = new ArrayList<>();
        for (CargoBlockRecord record : storage.managers()) {
            if (connects(record, target)) {
                managers.add(record);
            }
        }
        return managers;
    }

    private boolean connects(CargoBlockRecord manager, BlockKey target) {
        ArrayDeque<BlockKey> queue = new ArrayDeque<>();
        Set<BlockKey> visited = new HashSet<>();
        queue.add(manager.key());
        visited.add(manager.key());

        int steps = 0;
        while (!queue.isEmpty() && steps++ < maxNodes) {
            BlockKey current = queue.poll();
            if (current.equals(target)) {
                return true;
            }

            CargoBlockRecord record = storage.get(current);
            if (record == null) {
                continue;
            }

            if (differentKnownOwners(manager, record)) {
                continue;
            }

            if (record.type() == CargoBlockType.MANAGER && !record.key().equals(manager.key())) {
                continue;
            }

            if (record.type() == CargoBlockType.INPUT || record.type() == CargoBlockType.OUTPUT) {
                continue;
            }

            discoverNeighbors(current, queue, visited);
        }

        return false;
    }

    private void discoverNeighbors(BlockKey source, ArrayDeque<BlockKey> queue, Set<BlockKey> visited) {
        for (int[] dir : DIRECTIONS) {
            for (int i = RANGE + 1; i > 0; i--) {
                BlockKey key = source.offset(dir[0] * i, dir[1] * i, dir[2] * i);
                CargoBlockRecord record = storage.get(key);
                if (record != null && visited.add(key)) {
                    queue.add(key);
                }
            }
        }
    }

    private boolean differentKnownOwners(CargoBlockRecord manager, CargoBlockRecord record) {
        return manager.ownerId() != null && record.ownerId() != null && !manager.ownerId().equals(record.ownerId());
    }

    private record SummaryResult(CargoNetworkSnapshot snapshot, int connectors, int managers, int usedNodes) {
    }
}
