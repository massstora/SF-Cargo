package com.massstora.sfcargo.block;

import org.bukkit.Material;
import org.bukkit.Tag;

public final class CargoContainers {
    private CargoContainers() {
    }

    public static boolean isSupportedOutput(Material material) {
        return material == Material.CHEST
            || material == Material.TRAPPED_CHEST
            || material == Material.BARREL
            || material == Material.HOPPER
            || material == Material.DROPPER
            || material == Material.DISPENSER
            || isShulkerBox(material);
    }

    public static boolean isShulkerBox(Material material) {
        return Tag.SHULKER_BOXES.isTagged(material);
    }
}
