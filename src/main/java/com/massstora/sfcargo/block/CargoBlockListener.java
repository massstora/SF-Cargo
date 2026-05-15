package com.massstora.sfcargo.block;

import com.massstora.sfcargo.storage.BlockKey;
import com.massstora.sfcargo.storage.CargoBlockRecord;
import com.massstora.sfcargo.storage.CargoStorage;
import org.bukkit.ChatColor;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

public final class CargoBlockListener implements Listener {
    private final CargoStorage storage;
    private final CargoItems items;
    private final int maxNodes;

    public CargoBlockListener(CargoStorage storage, CargoItems items, int maxNodes) {
        this.storage = storage;
        this.items = items;
        this.maxNodes = maxNodes;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        CargoBlockType type = items.readType(event.getItemInHand());
        if (type == null) {
            return;
        }

        BlockFace attachedFace = BlockFace.SELF;
        if (type == CargoBlockType.INPUT || type == CargoBlockType.OUTPUT) {
            if (event.getBlockAgainst().getY() != event.getBlockPlaced().getY()) {
                event.getPlayer().sendMessage(ChatColor.RED + "Cargo nodes must be placed on a horizontal side.");
                event.setCancelled(true);
                return;
            }
            attachedFace = event.getBlockPlaced().getFace(event.getBlockAgainst());
            if (attachedFace == null) {
                event.setCancelled(true);
                return;
            }
            if (type == CargoBlockType.OUTPUT && !CargoContainers.isSupportedOutput(event.getBlockAgainst().getType())) {
                event.getPlayer().sendMessage(ChatColor.RED + "Cargo output nodes can only output to chests, barrels, shulker boxes, hoppers, droppers, or dispensers.");
                event.setCancelled(true);
                return;
            }
        }

        CargoBlockRecord record = new CargoBlockRecord(
            BlockKey.of(event.getBlockPlaced().getLocation()),
            type,
            type == CargoBlockType.MANAGER ? UUID.randomUUID() : new UUID(0L, 0L),
            attachedFace,
            event.getPlayer().getUniqueId()
        );
        items.markBlock(event.getBlockPlaced(), type);
        storage.put(record);
        storage.save();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        CargoBlockRecord record = storage.remove(BlockKey.of(event.getBlock().getLocation()));
        if (record == null) {
            CargoBlockType markedType = items.readBlockType(event.getBlock());
            if (markedType == null) {
                return;
            }
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), items.create(markedType));
            return;
        }

        if (!canEdit(event.getPlayer(), record)) {
            event.getPlayer().sendMessage(ChatColor.RED + "You do not own this cargo block.");
            event.setCancelled(true);
            storage.put(record);
            return;
        }

        event.setDropItems(false);
        if (record.type() == CargoBlockType.INPUT) {
            for (org.bukkit.inventory.ItemStack filter : record.filters()) {
                if (filter != null && !filter.getType().isAir()) {
                    event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), filter.clone());
                }
            }
        }
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), items.create(record.type()));
        storage.save();
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        CargoBlockRecord record = storage.get(BlockKey.of(event.getClickedBlock().getLocation()));
        if (record == null) {
            return;
        }

        Player player = event.getPlayer();
        if (!canEdit(player, record)) {
            player.sendMessage(ChatColor.RED + "You do not own this cargo block.");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        player.openInventory(new CargoMenu(record, storage, maxNodes).getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CargoMenu menu)) {
            return;
        }

        CargoBlockRecord record = menu.record();
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        if (menu.isFilterSlot(slot)) {
            return;
        }

        event.setCancelled(true);
        if (record.type() == CargoBlockType.INPUT) {
            switch (slot) {
                case 15 -> record.whitelist(!record.whitelist());
                case 16 -> record.smartFill(!record.smartFill());
                case 24 -> record.roundRobin(!record.roundRobin());
                case 25 -> record.includeLore(!record.includeLore());
                case 34 -> record.filterDurability(!record.filterDurability());
                case 41 -> record.channel(record.channel() - 1);
                case 43 -> record.channel(record.channel() + 1);
                default -> {
                    return;
                }
            }
            menu.saveFilters();
            menu.render();
            storage.save();
            return;
        }

        if (record.type() == CargoBlockType.OUTPUT) {
            if (slot == 12) {
                record.channel(record.channel() - 1);
            } else if (slot == 14) {
                record.channel(record.channel() + 1);
            } else {
                return;
            }
            menu.render();
            storage.save();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof CargoMenu menu) {
            if (menu.record().type() == CargoBlockType.INPUT) {
                menu.saveFilters();
            }
            storage.save();
        }
    }

    private boolean canEdit(Player player, CargoBlockRecord record) {
        return record.ownerId() == null || record.ownerId().equals(player.getUniqueId()) || player.hasPermission("sfcargo.admin");
    }
}
