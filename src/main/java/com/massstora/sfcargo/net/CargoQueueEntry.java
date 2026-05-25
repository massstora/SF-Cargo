package com.massstora.sfcargo.net;

import com.massstora.sfcargo.storage.BlockKey;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public record CargoQueueEntry(
    long id,
    UUID managerId,
    BlockKey input,
    int inputSlot,
    int channel,
    ItemStack item,
    long queuedAtMillis,
    long updatedAtMillis,
    QueueReason reason,
    String detail
) {
    public CargoQueueEntry {
        item = item == null ? null : item.clone();
        detail = detail == null ? "" : detail;
    }

    public CargoQueueEntry withUpdate(ItemStack item, QueueReason reason, String detail) {
        return new CargoQueueEntry(id, managerId, input, inputSlot, channel, item, queuedAtMillis, System.currentTimeMillis(), reason, detail);
    }

    public enum QueueReason {
        OUTPUT_NO_SPACE("output has no space"),
        OUTPUT_UNLOADED("output unloaded"),
        OUTPUT_UNREACHABLE("output unreachable"),
        INPUT_UNLOADED("input unloaded"),
        INPUT_UNREACHABLE("input unreachable"),
        NO_OUTPUTS("no output on channel"),
        UNKNOWN("unknown");

        private final String display;

        QueueReason(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }
}
