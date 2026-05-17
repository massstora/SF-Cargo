package com.massstora.sfcargo.net;

import com.massstora.sfcargo.storage.CargoBlockRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record CargoNetworkSnapshot(CargoBlockRecord manager, List<CargoBlockRecord> inputs, Map<Integer, List<CargoBlockRecord>> outputs, List<CargoBlockRecord> managers, boolean multipleManagers, boolean ownerConflict) {
    CargoNetworkSnapshot(CargoBlockRecord manager, boolean multipleManagers) {
        this(manager, new ArrayList<>(), new HashMap<>(), new ArrayList<>(List.of(manager)), multipleManagers, false);
    }

    List<CargoBlockRecord> outputsFor(int channel) {
        return outputs.getOrDefault(channel, List.of());
    }

    boolean activeManager() {
        CargoBlockRecord oldest = manager;
        for (CargoBlockRecord candidate : managers) {
            if (compareAge(candidate, oldest) < 0) {
                oldest = candidate;
            }
        }
        return oldest.key().equals(manager.key());
    }

    private static int compareAge(CargoBlockRecord left, CargoBlockRecord right) {
        int created = Long.compare(left.createdAtMillis(), right.createdAtMillis());
        if (created != 0) {
            return created;
        }
        return left.key().path().compareTo(right.key().path());
    }
}
