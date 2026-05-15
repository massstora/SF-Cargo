package com.massstora.sfcargo.net;

import com.massstora.sfcargo.storage.CargoBlockRecord;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

final class CargoFilter {
    private CargoFilter() {
    }

    static boolean matches(CargoBlockRecord record, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        ItemStack[] filters = record.filters();
        boolean hasFilter = false;
        for (ItemStack filter : filters) {
            if (filter != null && filter.getType() != Material.AIR) {
                hasFilter = true;
                break;
            }
        }

        if (!hasFilter) {
            return !record.whitelist();
        }

        for (ItemStack filter : filters) {
            if (filter != null && filter.getType() != Material.AIR && similar(filter, item, record.includeLore(), record.filterDurability())) {
                return record.whitelist();
            }
        }

        return !record.whitelist();
    }

    private static boolean similar(ItemStack filter, ItemStack item, boolean includeLore, boolean includeDurability) {
        ItemStack left = normalized(filter, includeLore, includeDurability);
        ItemStack right = normalized(item, includeLore, includeDurability);
        return left.isSimilar(right);
    }

    private static ItemStack normalized(ItemStack source, boolean includeLore, boolean includeDurability) {
        ItemStack clone = source.clone();
        clone.setAmount(1);
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            if (!includeLore) {
                meta.setLore(null);
            }
            if (!includeDurability && meta instanceof Damageable damageable) {
                damageable.setDamage(0);
            }
            clone.setItemMeta(meta);
        }
        return clone;
    }
}
