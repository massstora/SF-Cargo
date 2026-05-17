package com.massstora.sfcargo.storage;

import com.massstora.sfcargo.block.CargoBlockType;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class CargoBlockRecord {
    private final BlockKey key;
    private final CargoBlockType type;
    private final UUID managerId;
    private final BlockFace attachedFace;
    private final UUID ownerId;
    private final long createdAtMillis;
    private int channel;
    private boolean roundRobin;
    private boolean smartFill;
    private boolean whitelist = true;
    private boolean includeLore = true;
    private boolean filterDurability;
    private ItemStack[] filters = new ItemStack[9];

    public CargoBlockRecord(BlockKey key, CargoBlockType type, UUID managerId, BlockFace attachedFace) {
        this(key, type, managerId, attachedFace, null);
    }

    public CargoBlockRecord(BlockKey key, CargoBlockType type, UUID managerId, BlockFace attachedFace, UUID ownerId) {
        this(key, type, managerId, attachedFace, ownerId, System.currentTimeMillis());
    }

    public CargoBlockRecord(BlockKey key, CargoBlockType type, UUID managerId, BlockFace attachedFace, UUID ownerId, long createdAtMillis) {
        this.key = key;
        this.type = type;
        this.managerId = managerId;
        this.attachedFace = attachedFace;
        this.ownerId = ownerId;
        this.createdAtMillis = createdAtMillis;
    }

    public BlockKey key() {
        return key;
    }

    public CargoBlockType type() {
        return type;
    }

    public UUID managerId() {
        return managerId;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public BlockFace attachedFace() {
        return attachedFace;
    }

    public BlockKey attachedKey() {
        return key.offset(attachedFace.getModX(), attachedFace.getModY(), attachedFace.getModZ());
    }

    public int channel() {
        return channel;
    }

    public void channel(int channel) {
        this.channel = Math.floorMod(channel, 16);
    }

    public boolean roundRobin() {
        return roundRobin;
    }

    public void roundRobin(boolean roundRobin) {
        this.roundRobin = roundRobin;
    }

    public boolean smartFill() {
        return smartFill;
    }

    public void smartFill(boolean smartFill) {
        this.smartFill = smartFill;
    }

    public boolean whitelist() {
        return whitelist;
    }

    public void whitelist(boolean whitelist) {
        this.whitelist = whitelist;
    }

    public boolean includeLore() {
        return includeLore;
    }

    public void includeLore(boolean includeLore) {
        this.includeLore = includeLore;
    }

    public boolean filterDurability() {
        return filterDurability;
    }

    public void filterDurability(boolean filterDurability) {
        this.filterDurability = filterDurability;
    }

    public ItemStack[] filters() {
        return filters;
    }

    public void filters(ItemStack[] filters) {
        this.filters = new ItemStack[9];
        if (filters == null) {
            return;
        }
        System.arraycopy(filters, 0, this.filters, 0, Math.min(filters.length, this.filters.length));
    }
}
