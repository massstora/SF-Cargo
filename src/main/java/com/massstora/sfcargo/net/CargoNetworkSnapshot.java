package com.massstora.sfcargo.net;

import com.massstora.sfcargo.storage.CargoBlockRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record CargoNetworkSnapshot(CargoBlockRecord manager, List<CargoBlockRecord> inputs, Map<Integer, List<CargoBlockRecord>> outputs, boolean multipleManagers, boolean ownerConflict) {
    CargoNetworkSnapshot(CargoBlockRecord manager, boolean multipleManagers) {
        this(manager, new ArrayList<>(), new HashMap<>(), multipleManagers, false);
    }

    List<CargoBlockRecord> outputsFor(int channel) {
        return outputs.getOrDefault(channel, List.of());
    }
}
