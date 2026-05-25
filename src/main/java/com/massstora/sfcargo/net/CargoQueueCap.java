package com.massstora.sfcargo.net;

import java.util.UUID;

public record CargoQueueCap(UUID managerId, int channel, int blockedSlots, int maxBlockedSlots) {
}
