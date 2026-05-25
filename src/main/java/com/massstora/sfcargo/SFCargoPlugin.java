package com.massstora.sfcargo;

import com.massstora.sfcargo.block.CargoBlockListener;
import com.massstora.sfcargo.block.CargoBlockType;
import com.massstora.sfcargo.block.CargoItems;
import com.massstora.sfcargo.config.RecipeDiscoveryListener;
import com.massstora.sfcargo.config.RecipeRegistrar;
import com.massstora.sfcargo.integration.CoreProtectHook;
import com.massstora.sfcargo.net.CargoNetworkDiscovery;
import com.massstora.sfcargo.net.CargoQueueCap;
import com.massstora.sfcargo.net.CargoQueueEntry;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

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
        CargoTransporter transporter = new CargoTransporter(scheduler, getLogger(), getConfig().getBoolean("delete-excess-items", false), CoreProtectHook.create(this), getConfig().getInt("max-blocked-input-slots-per-manager-channel", 520));
        worker = new CargoWorker(this, storage, discovery, transporter, getConfig().getLong("tick-interval-ms", 100L), getConfig().getInt("discovery-interval-loops", 10));
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
            storage.close();
        }
        for (org.bukkit.World world : getServer().getWorlds()) {
            world.removePluginChunkTickets(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/sfcargo give <manager|connector|input|output>");
            sender.sendMessage(ChatColor.YELLOW + "/sfcargo reload");
            sender.sendMessage(ChatColor.YELLOW + "/sfcargo list");
            sender.sendMessage(ChatColor.YELLOW + "/sfcargo manager <uuid> <queued|purge|detail>");
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
            sender.sendMessage(ChatColor.GRAY + "Blocked transfer queue entries: " + worker.queuedMoveCount());
            sender.sendMessage(ChatColor.GRAY + "Deferred transport ticks: " + worker.queuedInventoryMoves());
            sender.sendMessage(ChatColor.GRAY + "Journal: active " + worker.activeJournalMoves() + ", pending rollback " + worker.pendingJournalRestores() + ", rollback queued " + worker.rollbackQueuedJournalMoves());
            sender.sendMessage(ChatColor.DARK_GRAY + "Transport wait: avg " + String.format(java.util.Locale.ROOT, "%.2f", worker.averageTransportWaitMs()) + "ms, last " + String.format(java.util.Locale.ROOT, "%.2f", worker.lastTransportWaitMs()) + "ms");
            sender.sendMessage(ChatColor.GRAY + "Configured interval: " + worker.intervalMs() + "ms, discovery every " + worker.discoveryIntervalLoops() + " loop(s)");
            sender.sendMessage(ChatColor.GRAY + "Tracked blocks: " + storage.all().size() + ", managers: " + storage.managers().size());
            return true;
        }

        if (args[0].equalsIgnoreCase("manager")) {
            handleManagerCommand(sender, args);
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
            boolean hasIssues = worker.hasManagerQueueIssues(manager.managerId());

            if (sender instanceof Player) {
                sender.sendMessage(Component.text("[TP]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand(tpCommand))
                    .hoverEvent(HoverEvent.showText(Component.text(tpCommand)))
                    .append(Component.text(" " + locationText, NamedTextColor.GREEN))
                    .append(Component.text(" [COPY UUID]", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.copyToClipboard(manager.managerId().toString()))
                        .hoverEvent(HoverEvent.showText(Component.text("Copy " + manager.managerId()))))
                    .append(Component.text(" UUID: " + manager.managerId(), NamedTextColor.GRAY))
                    .append(Component.text(" Owner: " + owner, NamedTextColor.DARK_GRAY))
                    .append(hasIssues ? Component.text(" ☠", NamedTextColor.RED)
                        .hoverEvent(HoverEvent.showText(Component.text("This manager has blocked transfer queue issues."))) : Component.empty()));
            } else {
                sender.sendMessage(ChatColor.GREEN + locationText + ChatColor.GRAY + " UUID: " + manager.managerId() + " Owner: " + owner + " TP: " + tpCommand + (hasIssues ? ChatColor.RED + " ☠" : ""));
            }
        }
    }

    private void handleManagerCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /sfcargo manager <uuid> <queued|purge|detail>");
            return;
        }

        UUID managerId;
        try {
            managerId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + "Invalid manager UUID.");
            return;
        }

        if (findManager(managerId).isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No cargo manager is tracked with UUID " + managerId + ".");
            return;
        }

        if (args[2].equalsIgnoreCase("queued")) {
            sendManagerQueue(sender, managerId);
            return;
        }

        if (args[2].equalsIgnoreCase("purge")) {
            purgeManagerQueue(sender, managerId, args);
            return;
        }

        if (args[2].equalsIgnoreCase("detail")) {
            sendManagerQueueDetail(sender, managerId, args);
            return;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /sfcargo manager <uuid> <queued|purge|detail>");
    }

    private void sendManagerQueue(CommandSender sender, UUID managerId) {
        List<CargoQueueEntry> entries = worker.queuedMoves(managerId);
        List<CargoQueueCap> caps = worker.queueCaps(managerId);
        sender.sendMessage(ChatColor.GREEN + "Queued cargo moves for " + managerId + ": " + entries.size());
        if (entries.isEmpty() && caps.isEmpty()) {
            return;
        }

        for (CargoQueueEntry entry : entries) {
            sender.sendMessage(ChatColor.YELLOW + "#" + entry.id()
                + ChatColor.GRAY + " age " + age(entry.queuedAtMillis())
                + " channel " + entry.channel()
                + " " + itemText(entry.item())
                + " reason: " + entry.reason().display());
        }
        for (CargoQueueCap cap : caps) {
            sender.sendMessage(ChatColor.RED + "☠ channel " + cap.channel() + " blocked scan capped at " + cap.maxBlockedSlots() + " slots; more blocked inputs may exist");
        }
    }

    private void purgeManagerQueue(CommandSender sender, UUID managerId, String[] args) {
        if (args.length == 3) {
            int removed = worker.purgeQueuedMoves(managerId);
            sender.sendMessage(ChatColor.GREEN + "Purged " + removed + " queued move(s) for " + managerId + ".");
            return;
        }

        Long id = parseQueueId(sender, args[3]);
        if (id == null) {
            return;
        }
        if (worker.purgeQueuedMove(managerId, id)) {
            sender.sendMessage(ChatColor.GREEN + "Purged queued move #" + id + " for " + managerId + ".");
        } else {
            sender.sendMessage(ChatColor.RED + "No queued move #" + id + " exists for " + managerId + ".");
        }
    }

    private void sendManagerQueueDetail(CommandSender sender, UUID managerId, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /sfcargo manager <uuid> detail <id>");
            return;
        }

        Long id = parseQueueId(sender, args[3]);
        if (id == null) {
            return;
        }
        Optional<CargoQueueEntry> entry = worker.queuedMove(managerId, id);
        if (entry.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No queued move #" + id + " exists for " + managerId + ".");
            return;
        }

        CargoQueueEntry move = entry.get();
        sender.sendMessage(ChatColor.GREEN + "Queued move #" + move.id());
        sender.sendMessage(ChatColor.GRAY + "Age: " + age(move.queuedAtMillis()) + ", updated " + age(move.updatedAtMillis()) + " ago");
        sender.sendMessage(ChatColor.GRAY + "Item: " + itemText(move.item()));
        sender.sendMessage(ChatColor.GRAY + "Channel: " + move.channel() + ", input slot: " + move.inputSlot());
        sender.sendMessage(ChatColor.GRAY + "Input inventory: " + move.input());
        sender.sendMessage(ChatColor.YELLOW + "Reason: " + move.reason().display());
        if (!move.detail().isBlank()) {
            sender.sendMessage(ChatColor.DARK_GRAY + move.detail());
        }
    }

    private Optional<CargoBlockRecord> findManager(UUID managerId) {
        for (CargoBlockRecord manager : storage.managers()) {
            if (manager.managerId().equals(managerId)) {
                return Optional.of(manager);
            }
        }
        return Optional.empty();
    }

    private Long parseQueueId(CommandSender sender, String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0L) {
                sender.sendMessage(ChatColor.RED + "Queue ID must be a positive number.");
                return null;
            }
            return id;
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Invalid queue ID.");
            return null;
        }
    }

    private String itemText(ItemStack item) {
        if (item == null) {
            return "unknown item";
        }
        return item.getAmount() + "x " + item.getType().name().toLowerCase(Locale.ROOT);
    }

    private String age(long timestamp) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        if (hours > 0L) {
            return hours + "h " + (minutes % 60L) + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + (seconds % 60L) + "s";
        }
        return seconds + "s";
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
