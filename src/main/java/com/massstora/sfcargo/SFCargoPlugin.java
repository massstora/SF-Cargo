package com.massstora.sfcargo;

import com.massstora.sfcargo.block.CargoBlockListener;
import com.massstora.sfcargo.block.CargoBlockType;
import com.massstora.sfcargo.block.CargoItems;
import com.massstora.sfcargo.config.RecipeDiscoveryListener;
import com.massstora.sfcargo.config.RecipeRegistrar;
import com.massstora.sfcargo.integration.CoreProtectHook;
import com.massstora.sfcargo.net.CargoNetworkDiscovery;
import com.massstora.sfcargo.net.CargoTransporter;
import com.massstora.sfcargo.net.CargoWorker;
import com.massstora.sfcargo.scheduler.CargoScheduler;
import com.massstora.sfcargo.storage.BlockKey;
import com.massstora.sfcargo.storage.CargoBlockRecord;
import com.massstora.sfcargo.storage.CargoStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class SFCargoPlugin extends JavaPlugin {
    private CargoStorage storage;
    private CargoItems items;
    private CargoWorker worker;
    private RecipeDiscoveryListener recipeDiscoveryListener;
    private List<NamespacedKey> recipeKeys = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        migrateOldDefaultManagerRecipe();
        saveConfig();

        storage = new CargoStorage(this);
        storage.load();
        items = new CargoItems(this);

        recipeKeys = new RecipeRegistrar(this, items).registerAll();
        recipeDiscoveryListener = new RecipeDiscoveryListener(this, recipeKeys);
        int maxNodes = getConfig().getInt("max-nodes-per-network", 512);
        getServer().getPluginManager().registerEvents(new CargoBlockListener(storage, items, maxNodes), this);
        getServer().getPluginManager().registerEvents(recipeDiscoveryListener, this);
        recipeDiscoveryListener.discoverForOnlinePlayers(getServer().getOnlinePlayers());

        CargoScheduler scheduler = new CargoScheduler(this);
        CargoNetworkDiscovery discovery = new CargoNetworkDiscovery(storage, maxNodes);
        CargoTransporter transporter = new CargoTransporter(scheduler, getLogger(), getConfig().getBoolean("delete-excess-items", false), CoreProtectHook.create(this));
        worker = new CargoWorker(this, storage, discovery, transporter, getConfig().getLong("tick-interval-ms", 100L));
        worker.start();

        getLogger().info("SF-Cargo enabled using " + (scheduler.folia() ? "Folia region scheduling." : "Paper main-thread scheduling."));
    }

    private void migrateOldDefaultManagerRecipe() {
        java.util.List<String> shape = getConfig().getStringList("recipes.manager.shape");
        String i = getConfig().getString("recipes.manager.ingredients.I");
        String r = getConfig().getString("recipes.manager.ingredients.R");
        String e = getConfig().getString("recipes.manager.ingredients.E");
        String n = getConfig().getString("recipes.manager.ingredients.N");

        boolean veryOldDefault = shape.equals(java.util.List.of("IRI", "RER", "IRI"))
            && "IRON_INGOT".equalsIgnoreCase(String.valueOf(i))
            && "REDSTONE".equalsIgnoreCase(String.valueOf(r))
            && "ENDER_PEARL".equalsIgnoreCase(String.valueOf(e));

        boolean expensiveDefault = shape.equals(java.util.List.of("NRN", " R ", "NRN"))
            && "NETHERITE_BLOCK".equalsIgnoreCase(String.valueOf(n))
            && "REDSTONE_BLOCK".equalsIgnoreCase(String.valueOf(r));

        if (!veryOldDefault && !expensiveDefault) {
            return;
        }

        getConfig().set("recipes.manager.shape", java.util.List.of("RHR", "DND", "RCR"));
        getConfig().set("recipes.manager.ingredients.I", null);
        getConfig().set("recipes.manager.ingredients.E", null);
        getConfig().set("recipes.manager.ingredients.H", "HOPPER");
        getConfig().set("recipes.manager.ingredients.D", "DROPPER");
        getConfig().set("recipes.manager.ingredients.C", "COMPARATOR");
        getConfig().set("recipes.manager.ingredients.N", "NETHERITE_BLOCK");
        getConfig().set("recipes.manager.ingredients.R", "REDSTONE_BLOCK");
        getLogger().info("Migrated old default Cargo Manager recipe to the cheaper vanilla crafting recipe.");
    }

    @Override
    public void onDisable() {
        if (worker != null) {
            worker.stop();
        }
        if (storage != null) {
            storage.save();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/sfcargo give <manager|connector|input|output>");
            sender.sendMessage(ChatColor.YELLOW + "/sfcargo reload");
            sender.sendMessage(ChatColor.YELLOW + "/sfcargo list");
            sender.sendMessage(ChatColor.YELLOW + "/sfcargo tps");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            recipeKeys = new RecipeRegistrar(this, items).registerAll();
            recipeDiscoveryListener.recipeKeys(recipeKeys);
            recipeDiscoveryListener.discoverForOnlinePlayers(getServer().getOnlinePlayers());
            sender.sendMessage(ChatColor.GREEN + "SF-Cargo config and recipes reloaded.");
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            sendManagerList(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("tps")) {
            sender.sendMessage(ChatColor.GREEN + "SF-Cargo TPS: " + String.format(java.util.Locale.ROOT, "%.0f", worker.pluginTps()) + " / " + worker.expectedLoops());
            sender.sendMessage(ChatColor.GRAY + "Plugin loop time: avg " + String.format(java.util.Locale.ROOT, "%.2f", worker.averagePlanningDurationMs()) + "ms, last " + String.format(java.util.Locale.ROOT, "%.2f", worker.lastPlanningDurationMs()) + "ms");
            sender.sendMessage(ChatColor.GRAY + "Queued inventory moves: " + worker.queuedInventoryMoves());
            sender.sendMessage(ChatColor.DARK_GRAY + "Transport wait: avg " + String.format(java.util.Locale.ROOT, "%.2f", worker.averageTransportWaitMs()) + "ms, last " + String.format(java.util.Locale.ROOT, "%.2f", worker.lastTransportWaitMs()) + "ms");
            sender.sendMessage(ChatColor.GRAY + "Configured interval: " + worker.intervalMs() + "ms");
            sender.sendMessage(ChatColor.GRAY + "Tracked blocks: " + storage.all().size() + ", managers: " + storage.managers().size());
            return true;
        }

        if (args[0].equalsIgnoreCase("tp")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use cargo teleport links.");
                return true;
            }
            if (args.length != 5) {
                sender.sendMessage(ChatColor.RED + "Usage: /sfcargo tp <world-uuid> <x> <y> <z>");
                return true;
            }
            teleportToCargoLocation(player, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can receive cargo items.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Choose manager, connector, input, or output.");
                return true;
            }
            CargoBlockType type = CargoBlockType.fromId(args[1]);
            if (type == null) {
                sender.sendMessage(ChatColor.RED + "Unknown cargo block type.");
                return true;
            }
            player.getInventory().addItem(items.create(type));
            sender.sendMessage(ChatColor.GREEN + "Given " + type.displayName() + ".");
            return true;
        }

        return false;
    }

    private void sendManagerList(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "Tracked cargo blocks: " + storage.all().size());
        sender.sendMessage(ChatColor.GREEN + "Cargo managers: " + storage.managers().size());

        for (CargoBlockRecord manager : storage.managers()) {
            BlockKey key = manager.key();
            Location location = key.toLocation();
            String worldName = location == null || location.getWorld() == null ? key.worldId().toString() : location.getWorld().getName();
            String locationText = worldName + " " + key.x() + " " + key.y() + " " + key.z();
            String owner = ownerDisplayName(manager.ownerId());
            String tpCommand = "/sfcargo tp " + key.worldId() + " " + key.x() + " " + key.y() + " " + key.z();

            if (sender instanceof Player) {
                sender.sendMessage(Component.text("[TP]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(tpCommand))
                    .hoverEvent(HoverEvent.showText(Component.text(tpCommand)))
                    .append(Component.text(" " + locationText, NamedTextColor.GREEN))
                    .append(Component.text(" UUID: " + manager.managerId(), NamedTextColor.GRAY))
                    .append(Component.text(" Owner: " + owner, NamedTextColor.DARK_GRAY)));
            } else {
                sender.sendMessage(ChatColor.GREEN + locationText + ChatColor.GRAY + " UUID: " + manager.managerId() + " Owner: " + owner + " TP: " + tpCommand);
            }
        }
    }

    private void teleportToCargoLocation(Player player, String[] args) {
        try {
            java.util.UUID worldId = java.util.UUID.fromString(args[1]);
            int x = Integer.parseInt(args[2]);
            int y = Integer.parseInt(args[3]);
            int z = Integer.parseInt(args[4]);
            org.bukkit.World world = getServer().getWorld(worldId);
            if (world == null) {
                player.sendMessage(ChatColor.RED + "That cargo manager's world is not loaded.");
                return;
            }
            Location location = new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
            player.teleportAsync(location).thenAccept(success -> {
                if (!success) {
                    player.sendMessage(ChatColor.RED + "Teleport failed.");
                }
            });
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ChatColor.RED + "Invalid cargo teleport link.");
        }
    }

    private String ownerDisplayName(java.util.UUID ownerId) {
        if (ownerId == null) {
            return "legacy/unclaimed";
        }

        org.bukkit.OfflinePlayer offlinePlayer = getServer().getOfflinePlayer(ownerId);
        String name = offlinePlayer.getName();
        return name == null || name.isBlank() ? ownerId.toString() : name;
    }
}
