package com.massstora.sfcargo.block;

import com.massstora.sfcargo.net.CargoNetworkDiscovery;
import com.massstora.sfcargo.net.CargoNetworkSummary;
import com.massstora.sfcargo.storage.CargoBlockRecord;
import com.massstora.sfcargo.storage.CargoStorage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

public final class CargoMenu implements InventoryHolder {
    public static final int[] FILTER_SLOTS = {19, 20, 21, 28, 29, 30, 37, 38, 39};
    private static final int[] INPUT_BORDER = {0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 18, 22, 23, 26, 27, 31, 32, 33, 34, 35, 36, 40, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53};
    private static final int[] OUTPUT_BORDER = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26};

    private final CargoBlockRecord record;
    private final Inventory inventory;
    private final CargoStorage storage;
    private final int maxNodes;

    public CargoMenu(CargoBlockRecord record, CargoStorage storage) {
        this(record, storage, 512);
    }

    public CargoMenu(CargoBlockRecord record, CargoStorage storage, int maxNodes) {
        this.record = record;
        this.storage = storage;
        this.maxNodes = Math.max(1, maxNodes);
        int size = record.type() == CargoBlockType.OUTPUT ? 27 : 54;
        this.inventory = Bukkit.createInventory(this, size, ChatColor.DARK_AQUA + record.type().displayName());
        render();
    }

    public CargoBlockRecord record() {
        return record;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void render() {
        inventory.clear();
        if (record.type() == CargoBlockType.INPUT) {
            renderInput();
        } else if (record.type() == CargoBlockType.OUTPUT) {
            renderOutput();
        } else if (record.type() == CargoBlockType.MANAGER) {
            renderManager();
        } else {
            renderConnector();
        }
    }

    public boolean isFilterSlot(int rawSlot) {
        return record.type() == CargoBlockType.INPUT && Arrays.stream(FILTER_SLOTS).anyMatch(slot -> slot == rawSlot);
    }

    public void saveFilters() {
        ItemStack[] filters = new ItemStack[FILTER_SLOTS.length];
        for (int i = 0; i < FILTER_SLOTS.length; i++) {
            ItemStack item = inventory.getItem(FILTER_SLOTS[i]);
            filters[i] = item == null || item.getType().isAir() ? null : item.clone();
        }
        record.filters(filters);
    }

    private void renderInput() {
        for (int slot : INPUT_BORDER) {
            setItem(slot, item(Material.CYAN_STAINED_GLASS_PANE, " "));
        }

        setItem(2, item(Material.PAPER, ChatColor.DARK_AQUA + "Items", ChatColor.AQUA + "Put in all items you want to", ChatColor.AQUA + "blacklist/whitelist"));
        setItem(15, item(record.whitelist() ? Material.WHITE_WOOL : Material.BLACK_WOOL,
            ChatColor.GRAY + "Type: " + (record.whitelist() ? ChatColor.WHITE + "Whitelist" : ChatColor.DARK_GRAY + "Blacklist"),
            ChatColor.YELLOW + "Click to change it to " + (record.whitelist() ? "Blacklist" : "Whitelist")));
        setItem(16, item(record.smartFill() ? Material.WRITTEN_BOOK : Material.WRITABLE_BOOK,
            ChatColor.GRAY + "\"Smart-Filling\" Mode: " + enabled(record.smartFill()),
            ChatColor.YELLOW + "Click to toggle smart filling"));
        setItem(24, item(Material.COMPASS,
            ChatColor.GRAY + "Round-Robin Mode: " + enabled(record.roundRobin()),
            ChatColor.YELLOW + "Click to toggle round-robin"));
        setItem(25, item(Material.MAP,
            ChatColor.GRAY + "Include Lore: " + enabled(record.includeLore()),
            ChatColor.YELLOW + "Click to toggle lore matching"));
        setItem(34, item(Material.ANVIL,
            ChatColor.GRAY + "Include Durability: " + enabled(record.filterDurability()),
            ChatColor.YELLOW + "Click to toggle durability matching"));

        addChannelSelector(41, 42, 43);
        loadFilterItems();
    }

    private void renderOutput() {
        for (int slot : OUTPUT_BORDER) {
            setItem(slot, item(Material.CYAN_STAINED_GLASS_PANE, " "));
        }
        addChannelSelector(12, 13, 14);
    }

    private void renderManager() {
        setItem(4, item(Material.LODESTONE, ChatColor.GOLD + "Cargo Manager", ChatColor.WHITE + "UUID:", ChatColor.AQUA + record.managerId().toString(), ownerLine()));
        if (storage == null) {
            return;
        }

        CargoNetworkSummary summary = new CargoNetworkDiscovery(storage, maxNodes).summarize(record);
        setItem(11, item(Material.HOPPER, ChatColor.AQUA + "Discovered Inputs", ChatColor.WHITE + String.valueOf(summary.inputs())));
        setItem(15, item(Material.DROPPER, ChatColor.AQUA + "Discovered Outputs", ChatColor.WHITE + String.valueOf(summary.outputs())));
        setItem(20, item(Material.LIGHTNING_ROD, ChatColor.AQUA + "Discovered Connectors", ChatColor.WHITE + String.valueOf(summary.connectors())));
        setItem(24, item(Material.LODESTONE, ChatColor.AQUA + "Discovered Managers", ChatColor.WHITE + String.valueOf(summary.managers())));
        setItem(31, item(Material.COMPARATOR, ChatColor.AQUA + "Network Capacity",
            ChatColor.GRAY + "Used: " + ChatColor.WHITE + summary.usedNodes() + ChatColor.GRAY + " / " + ChatColor.WHITE + summary.maxNodes(),
            ChatColor.GRAY + "Remaining: " + ChatColor.WHITE + summary.remainingNodes()));
        setItem(13, item(summary.multipleManagers() ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK,
            ChatColor.AQUA + "Network Status",
            summary.multipleManagers() ? ChatColor.RED + "Multiple managers connected" : ChatColor.GREEN + "No manager conflict",
            ChatColor.GRAY + "Matched channels: " + summary.matchedChannels()));

        for (int channel = 0; channel < 16; channel++) {
            int slot = channel < 8 ? 36 + channel : 37 + channel;
            setItem(slot, item(woolFor(channel),
                ChatColor.AQUA + "Channel ID: " + ChatColor.DARK_AQUA + (channel + 1),
                ChatColor.GRAY + "Inputs: " + summary.inputsByChannel()[channel],
                ChatColor.GRAY + "Outputs: " + summary.outputsByChannel()[channel]));
        }
    }

    private void renderConnector() {
        setItem(4, item(Material.LIGHTNING_ROD, ChatColor.GOLD + "Cargo Connector", ChatColor.GRAY + "Connects cargo networks.", ownerLine()));
        if (storage == null) {
            setItem(13, item(Material.YELLOW_CONCRETE, ChatColor.YELLOW + "Connection Status", ChatColor.GRAY + "Storage unavailable."));
            return;
        }

        List<CargoBlockRecord> managers = new CargoNetworkDiscovery(storage, 512).managersConnectedTo(record.key());
        if (managers.isEmpty()) {
            setItem(13, item(Material.RED_CONCRETE, ChatColor.RED + "Not Connected", ChatColor.GRAY + "No manager can reach this connector."));
            return;
        }

        if (managers.size() == 1) {
            setItem(13, item(Material.EMERALD_BLOCK, ChatColor.GREEN + "Connected", ChatColor.WHITE + "Manager UUID:", ChatColor.AQUA + managers.get(0).managerId().toString()));
            return;
        }

        setItem(13, item(Material.REDSTONE_BLOCK, ChatColor.RED + "Multiple Managers", ChatColor.GRAY + "This connector crosses storage networks."));
        for (int i = 0; i < managers.size() && i < 9; i++) {
            setItem(18 + i, item(Material.PAPER, ChatColor.YELLOW + "Manager " + (i + 1), ChatColor.AQUA + managers.get(i).managerId().toString()));
        }
    }

    private void addChannelSelector(int prev, int current, int next) {
        setItem(prev, item(Material.ARROW, ChatColor.AQUA + "Previous Channel", ChatColor.YELLOW + "Click to decrease the Channel ID by 1"));
        setItem(current, item(woolFor(record.channel()), ChatColor.AQUA + "Channel ID: " + ChatColor.DARK_AQUA + (record.channel() + 1)));
        setItem(next, item(Material.ARROW, ChatColor.AQUA + "Next Channel", ChatColor.YELLOW + "Click to increase the Channel ID by 1"));
    }

    private void loadFilterItems() {
        ItemStack[] filters = record.filters();
        for (int i = 0; i < FILTER_SLOTS.length && i < filters.length; i++) {
            setItem(FILTER_SLOTS[i], filters[i]);
        }
    }

    private void setItem(int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private String enabled(boolean value) {
        return value ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled";
    }

    private String ownerLine() {
        return record.ownerId() == null ? ChatColor.GRAY + "Owner: legacy/unclaimed" : ChatColor.GRAY + "Owner: " + record.ownerId();
    }

    private Material woolFor(int channel) {
        Material[] colors = {
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
            Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
            Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL
        };
        return colors[Math.floorMod(channel, colors.length)];
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(List.of(lore));
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
