package me.kirogr.tradeups;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.*;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public final class TradeUps extends JavaPlugin implements Listener {

    private final Map<UUID, TradeSession> activeTrades = new HashMap<>();
    private final Map<UUID, UUID> tradeRequests = new HashMap<>();
    private final Set<UUID> busyPlayers = new HashSet<>();

    private File tradeLogFile;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        TradeCommand tradeCommand = new TradeCommand();
        getCommand("trade").setExecutor(tradeCommand);
        getCommand("trade").setTabCompleter(tradeCommand);
        Bukkit.getPluginManager().registerEvents(this, this);

        // Initialize trade log file
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        tradeLogFile = new File(getDataFolder(), "trade_log.txt");

        getLogger().info("TradeUps plugin enabled!");
    }

    @Override
    public void onDisable() {
        activeTrades.values().forEach(TradeSession::forceCancel);
        activeTrades.clear();
        tradeRequests.clear();
        busyPlayers.clear();
        getLogger().info("TradeUps plugin disabled!");
    }

    public void sendTradeStaffBroadcast(String message) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("tradeups.admin")) {
                player.sendMessage(ChatColor.GOLD + "[TradeUps] " + ChatColor.YELLOW + message);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1.0f, 1.0f);
            }
        }
    }

        private void logTrade(Player player1, Player player2, List<ItemStack> p1Items, List<ItemStack> p2Items) {
        try {
            FileWriter writer = new FileWriter(tradeLogFile, true);
            writer.write("=== TRADE LOG ===\n");
            writer.write("Date: " + dateFormat.format(new Date()) + "\n");
            writer.write("Player 1: " + player1.getName() + " (UUID: " + player1.getUniqueId() + ")\n");
            writer.write("Player 2: " + player2.getName() + " (UUID: " + player2.getUniqueId() + ")\n");

            writer.write("Items from " + player1.getName() + ":\n");
            if (p1Items.isEmpty()) {
                writer.write("  - No items\n");
            } else {
                for (ItemStack item : p1Items) {
                    writer.write("  - " + item.getAmount() + "x " + item.getType().name() +
                            (item.hasItemMeta() && item.getItemMeta().hasDisplayName() ?
                                    " (" + item.getItemMeta().getDisplayName() + ")" : "") + "\n");
                }
            }

            writer.write("Items from " + player2.getName() + ":\n");
            if (p2Items.isEmpty()) {
                writer.write("  - No items\n");
            } else {
                for (ItemStack item : p2Items) {
                    writer.write("  - " + item.getAmount() + "x " + item.getType().name() +
                            (item.hasItemMeta() && item.getItemMeta().hasDisplayName() ?
                                    " (" + item.getItemMeta().getDisplayName() + ")" : "") + "\n");
                }
            }

            writer.write("Status: COMPLETED\n");
            writer.write("================\n\n");
            writer.close();

            getLogger().info("Trade logged: " + player1.getName() + " <-> " + player2.getName());
        } catch (IOException e) {
            getLogger().severe("Failed to log trade: " + e.getMessage());
        }
    }

    public class TradeCommand implements CommandExecutor, TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!(sender instanceof Player)) {
                return new ArrayList<>();
            }

            Player player = (Player) sender;
            List<String> suggestions = new ArrayList<>();

            if (args.length == 1) {
                String input = args[0].toLowerCase();

                // Add accept/deny suggestions
                if ("accept".startsWith(input)) {
                    suggestions.add("accept");
                }
                if ("deny".startsWith(input)) {
                    suggestions.add("deny");
                }

                // Add all online players (except the sender)
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (!onlinePlayer.equals(player) &&
                            onlinePlayer.getName().toLowerCase().startsWith(input)) {
                        suggestions.add(onlinePlayer.getName());
                    }
                }
            }

            return suggestions;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                return true;
            }

            Player player = (Player) sender;

            if (args.length == 0) {
                player.sendMessage(ChatColor.YELLOW + "Usage: /trade <player>");
                return true;
            }

            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("accept")) {
                    handleTradeAccept(player);
                    return true;
                } else if (args[0].equalsIgnoreCase("deny")) {
                    handleTradeDeny(player);
                    return true;
                } else {
                    handleTradeRequest(player, args[0]);
                    return true;
                }
            }

            player.sendMessage(ChatColor.RED + "Usage: /trade <player> or /trade accept/deny");
            return true;
        }

        private void handleTradeRequest(Player requester, String targetName) {
            if (busyPlayers.contains(requester.getUniqueId())) {
                requester.sendMessage(ChatColor.RED + "You are already in a trade or have a pending request!");
                return;
            }

            Player target = Bukkit.getPlayer(targetName);
            if (target == null || !target.isOnline()) {
                requester.sendMessage(ChatColor.RED + "Player not found or offline!");
                return;
            }

            if (requester.equals(target)) {
                requester.sendMessage(ChatColor.RED + "You cannot trade with yourself!");
                return;
            }

            if (busyPlayers.contains(target.getUniqueId())) {
                requester.sendMessage(ChatColor.RED + target.getName() + " is already busy with another trade!");
                return;
            }

            tradeRequests.put(target.getUniqueId(), requester.getUniqueId());
            busyPlayers.add(requester.getUniqueId());
            busyPlayers.add(target.getUniqueId());

            requester.sendMessage(ChatColor.GREEN + "Trade request sent to " + target.getName() + "!");
            target.sendMessage(ChatColor.YELLOW + "═══════════════════════════════");
            target.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "TRADE REQUEST");
            target.sendMessage(ChatColor.YELLOW + requester.getName() + " wants to trade with you!");
            target.sendMessage(ChatColor.GREEN + "Use " + ChatColor.WHITE + "/trade accept" + ChatColor.GREEN + " to accept");
            target.sendMessage(ChatColor.RED + "Use " + ChatColor.WHITE + "/trade deny" + ChatColor.RED + " to deny");
            target.sendMessage(ChatColor.YELLOW + "═══════════════════════════════");
            target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);

            sendTradeStaffBroadcast(ChatColor.YELLOW + requester.getName() + " has sent a trade request to " + target.getName() + ".");

            // Create clickable chat components
            // ACCEPT button
            TextComponent acceptButton = new TextComponent("[ACCEPT]");
            acceptButton.setColor(net.md_5.bungee.api.ChatColor.GREEN); // Use BungeeCord ChatColor
            acceptButton.setBold(true);
            acceptButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/trade accept"));
            acceptButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(net.md_5.bungee.api.ChatColor.GREEN + "Click to accept the trade request.")));

            // Separator text
            TextComponent separatorText = new TextComponent(" | ");
            separatorText.setColor(net.md_5.bungee.api.ChatColor.GRAY);

            // DENY button
            TextComponent denyButton = new TextComponent("[DENY]");
            denyButton.setColor(net.md_5.bungee.api.ChatColor.RED); // Use BungeeCord ChatColor
            denyButton.setBold(true);
            denyButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/trade deny"));
            denyButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(net.md_5.bungee.api.ChatColor.RED + "Click to deny the trade request.")));

            // Combine them into a single message
            TextComponent fullMessage = new TextComponent();
            fullMessage.addExtra(acceptButton);
            fullMessage.addExtra(separatorText);
            fullMessage.addExtra(denyButton);

            target.sendMessage(""); // Add a blank line for spacing
            target.spigot().sendMessage(fullMessage); // Send the JSON component message
            target.sendMessage(ChatColor.GRAY + "Click the buttons above or use commands");


            new BukkitRunnable() {
                @Override
                public void run() {
                    if (tradeRequests.containsKey(target.getUniqueId())) {
                        tradeRequests.remove(target.getUniqueId());
                        busyPlayers.remove(requester.getUniqueId());
                        busyPlayers.remove(target.getUniqueId());
                        requester.sendMessage(ChatColor.RED + "Trade request to " + target.getName() + " expired.");
                        target.sendMessage(ChatColor.RED + "Trade request from " + requester.getName() + " expired.");
                    }
                }
            }.runTaskLater(TradeUps.this, 600); // 30 seconds
        }


        private void handleTradeAccept(Player player) {
            UUID requesterId = tradeRequests.get(player.getUniqueId());
            if (requesterId == null) {
                player.sendMessage(ChatColor.RED + "You have no pending trade requests!");
                return;
            }

            Player requester = Bukkit.getPlayer(requesterId);
            if (requester == null || !requester.isOnline()) {
                player.sendMessage(ChatColor.RED + "The player who sent the request is no longer online!");
                tradeRequests.remove(player.getUniqueId());
                busyPlayers.remove(requesterId);
                busyPlayers.remove(player.getUniqueId());
                return;
            }

            tradeRequests.remove(player.getUniqueId());
            TradeSession session = new TradeSession(requester, player);
            activeTrades.put(requester.getUniqueId(), session);
            activeTrades.put(player.getUniqueId(), session);
        }

        private void handleTradeDeny(Player player) {
            UUID requesterId = tradeRequests.get(player.getUniqueId());
            if (requesterId == null) {
                player.sendMessage(ChatColor.RED + "You have no pending trade requests!");
                return;
            }

            Player requester = Bukkit.getPlayer(requesterId);
            tradeRequests.remove(player.getUniqueId());
            busyPlayers.remove(requesterId);
            busyPlayers.remove(player.getUniqueId());

            player.sendMessage(ChatColor.RED + "Trade request denied.");
            if (requester != null && requester.isOnline()) {
                requester.sendMessage(ChatColor.RED + player.getName() + " denied your trade request.");
            }
        }
    }

    public class TradeSession {
        private final Player player1, player2;
        private final Inventory gui;
        private boolean ready1 = false, ready2 = false;
        private boolean locked = false;
        private BukkitRunnable timeoutTask;

        // Define trade areas
        private final int[] player1Slots = {10, 11, 12, 19, 20, 21, 28, 29, 30}; // left side
        private final int[] player2Slots = {14, 15, 16, 23, 24, 25, 32, 33, 34}; // right side

        public TradeSession(Player p1, Player p2) {
            this.player1 = p1;
            this.player2 = p2;
            this.gui = Bukkit.createInventory(null, 54, ChatColor.DARK_GREEN + "Trade: " + p1.getName() + " ↔ " + p2.getName());
            setupGUI();
            openTrade();
        }


        private void setupGUI() {
            ItemStack background = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta backgroundMeta = background.getItemMeta();
            backgroundMeta.setDisplayName(" ");
            background.setItemMeta(backgroundMeta);

            for (int i = 0; i < 54; i++) {
                gui.setItem(i, background);
            }

            for (int slot : player1Slots) {
                gui.setItem(slot, null);
            }
            for (int slot : player2Slots) {
                gui.setItem(slot, null);
            }

            ItemStack p1Head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta p1Meta = (SkullMeta) p1Head.getItemMeta();
            p1Meta.setOwningPlayer((OfflinePlayer) player1);
            p1Meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + player1.getName());
            p1Meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Place your items on the left side",
                    ChatColor.GRAY + "Status: " + (ready1 ? ChatColor.GREEN + "Ready" : ChatColor.RED + "Not Ready")
            ));
            p1Head.setItemMeta(p1Meta);
            gui.setItem(1, p1Head);

            ItemStack p2Head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta p2Meta = (SkullMeta) p2Head.getItemMeta();
            p2Meta.setOwningPlayer((OfflinePlayer) player2);
            p2Meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + player2.getName());
            p2Meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Place your items on the right side",
                    ChatColor.GRAY + "Status: " + (ready2 ? ChatColor.GREEN + "Ready" : ChatColor.RED + "Not Ready")
            ));
            p2Head.setItemMeta(p2Meta);
            gui.setItem(7, p2Head);

            ItemStack separator = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
            ItemMeta sepMeta = separator.getItemMeta();
            sepMeta.setDisplayName(ChatColor.YELLOW + "═══════════════");
            separator.setItemMeta(sepMeta);

            // Middle column separator
            for (int i = 4; i < 54; i += 9) {
                gui.setItem(i, separator);
            }

            updateButtons();
        }

        private void updateButtons() {
            ItemStack p1Head = gui.getItem(1);
            SkullMeta p1Meta = (SkullMeta) p1Head.getItemMeta();
            p1Meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Place your items on the left side",
                    ChatColor.GRAY + "Status: " + (ready1 ? ChatColor.GREEN + "Ready" : ChatColor.RED + "Not Ready")
            ));
            p1Head.setItemMeta(p1Meta);


            ItemStack p2Head = gui.getItem(7);
            SkullMeta p2Meta = (SkullMeta) p2Head.getItemMeta();
            p2Meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Place your items on the right side",
                    ChatColor.GRAY + "Status: " + (ready2 ? ChatColor.GREEN + "Ready" : ChatColor.RED + "Not Ready")
            ));
            p2Head.setItemMeta(p2Meta);

            // decline button
            ItemStack decline = new ItemStack(Material.RED_CONCRETE);
            ItemMeta declineMeta = decline.getItemMeta();
            declineMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "✗ DECLINE TRADE");
            declineMeta.setLore(Arrays.asList(ChatColor.GRAY + "Cancel this trade"));
            decline.setItemMeta(declineMeta);
            gui.setItem(45, decline);

            // accept or complete button
            Material acceptMaterial = (ready1 && ready2) ? Material.LIME_CONCRETE : Material.ORANGE_CONCRETE;
            String acceptText = (ready1 && ready2) ? "✓ COMPLETE TRADE" : "? READY UP";
            ChatColor acceptColor = (ready1 && ready2) ? ChatColor.GREEN : ChatColor.GOLD;

            ItemStack accept = new ItemStack(acceptMaterial);
            ItemMeta acceptMeta = accept.getItemMeta();
            acceptMeta.setDisplayName(acceptColor + "" + ChatColor.BOLD + acceptText);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Players Status:");
            lore.add((ready1 ? ChatColor.GREEN + "✓ " : ChatColor.RED + "✗ ") + ChatColor.GRAY + player1.getName());
            lore.add((ready2 ? ChatColor.GREEN + "✓ " : ChatColor.RED + "✗ ") + ChatColor.GRAY + player2.getName());

            if (ready1 && ready2) {
                lore.add("");
                lore.add(ChatColor.GREEN + "Both players are ready!");
                lore.add(ChatColor.YELLOW + "Click to complete the trade");
            } else {
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click when you're ready to trade");
            }

            acceptMeta.setLore(lore);
            accept.setItemMeta(acceptMeta);
            gui.setItem(53, accept);
        }


        private void openTrade() {
            player1.openInventory(gui);
            player2.openInventory(gui);

            player1.sendMessage(ChatColor.GREEN + "Trade opened with " + player2.getName() + "!");
            player2.sendMessage(ChatColor.GREEN + "Trade opened with " + player1.getName() + "!");

            player1.sendMessage(ChatColor.YELLOW + "Place items in the LEFT side, then click 'Ready Up' when done.");
            player2.sendMessage(ChatColor.YELLOW + "Place items in the RIGHT side, then click 'Ready Up' when done.");
        }


        public void handleClick(Player clicker, int slot, InventoryClickEvent event) {
            if (locked) {
                clicker.sendMessage(ChatColor.RED + "Trade is being processed!");
                return;
            }

            // Check if the click is in the trade GUI or player's inventory
            if (event.getClickedInventory() != gui) {
                // Player is clicking in their own inventory - allow this
                event.setCancelled(false);
                return;
            }

            if (slot == 45) { // decline
                cancel();
                return;
            } else if (slot == 53) { // accept or complete
                if (ready1 && ready2) {
                    completeTrade();
                } else {
                    toggleReady(clicker);
                }
                return;
            }

            // Check if clicking in valid trade areas
            boolean isPlayer1Slot = contains(player1Slots, slot);
            boolean isPlayer2Slot = contains(player2Slots, slot);

            if (isPlayer1Slot && clicker.equals(player1)) {
                // Player 1 clicking in their area - allow
                resetReady();
                event.setCancelled(false); // Allow the click
                return;
            } else if (isPlayer2Slot && clicker.equals(player2)) {
                // Player 2 clicking in their area - allow
                resetReady();
                event.setCancelled(false); // Allow the click
                return;
            } else if (isPlayer1Slot || isPlayer2Slot) {
                // Player clicking in wrong area
                clicker.sendMessage(ChatColor.RED + "You can only place items in your own area!");
                return;
            }

            // any other slot in the trade GUI - block completely
            clicker.sendMessage(ChatColor.RED + "You cannot place items here!");
        }

        private boolean contains(int[] array, int value) {
            for (int i : array) {
                if (i == value) return true;
            }
            return false;
        }

        private void toggleReady(Player player) {
            if (player.equals(player1)) {
                ready1 = !ready1;
                player1.sendMessage(ready1 ? ChatColor.GREEN + "You are ready to trade!" : ChatColor.YELLOW + "You are no longer ready.");
                if (ready1) {
                    player2.sendMessage(ChatColor.GREEN + player1.getName() + " is ready to trade!");
                    player1.playSound(player1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                } else {
                    player2.sendMessage(ChatColor.YELLOW + player1.getName() + " is no longer ready.");
                }
            } else if (player.equals(player2)) {
                ready2 = !ready2;
                player2.sendMessage(ready2 ? ChatColor.GREEN + "You are ready to trade!" : ChatColor.YELLOW + "You are no longer ready.");
                if (ready2) {
                    player1.sendMessage(ChatColor.GREEN + player2.getName() + " is ready to trade!");
                    player2.playSound(player2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                } else {
                    player1.sendMessage(ChatColor.YELLOW + player2.getName() + " is no longer ready.");
                }
            }
            updateButtons();
        }

        private void resetReady() {
            if (ready1 || ready2) {
                ready1 = false;
                ready2 = false;
                player1.sendMessage(ChatColor.YELLOW + "Items changed - ready status reset.");
                player2.sendMessage(ChatColor.YELLOW + "Items changed - ready status reset.");
                player1.playSound(player1.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                player2.playSound(player2.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                updateButtons();
            }
        }

        private String formatItemStacks(List<ItemStack> items) {
            if (items.isEmpty()) {
                return "nothing";
            }

            Map<String, Integer> itemCounts = new HashMap<>();
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR) {
                    String itemName;
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && meta.hasDisplayName()) {
                        itemName = ChatColor.stripColor(meta.getDisplayName()); // Use display name if present
                    } else {
                        itemName = item.getType().name().replace("_", " ").toLowerCase(); // Fallback to material name
                    }
                    itemCounts.put(itemName, itemCounts.getOrDefault(itemName, 0) + item.getAmount());
                }
            }

            List<String> formattedParts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                formattedParts.add("x" + entry.getValue() + " " + entry.getKey());
            }

            return String.join(", ", formattedParts);
        }

        private void completeTrade() {
            if (!ready1 || !ready2) {
                return;
            }

            locked = true;

            // Collect items from both sides
            List<ItemStack> p1Items = new ArrayList<>();
            List<ItemStack> p2Items = new ArrayList<>();

            for (int slot : player1Slots) {
                ItemStack item = gui.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    p1Items.add(item.clone());
                }
            }

            for (int slot : player2Slots) {
                ItemStack item = gui.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    p2Items.add(item.clone());
                }
            }

            // Verify inventory space
            if (!hasInventorySpace(player1, p2Items) || !hasInventorySpace(player2, p1Items)) {
                player1.sendMessage(ChatColor.RED + "Trade failed: Not enough inventory space!");
                player2.sendMessage(ChatColor.RED + "Trade failed: Not enough inventory space!");
                locked = false;
                return;
            }

            // Log the trade BEFORE clearing items
            TradeUps.this.logTrade(player1, player2, p1Items, p2Items);

            // Clear the trade slots
            for (int slot : player1Slots) {
                gui.setItem(slot, null);
            }
            for (int slot : player2Slots) {
                gui.setItem(slot, null);
            }

            // Transfer items
            for (ItemStack item : p2Items) {
                player1.getInventory().addItem(item);
            }
            for (ItemStack item : p1Items) {
                player2.getInventory().addItem(item);
            }

            // Success messages and effects
            player1.sendMessage(ChatColor.GREEN + "═══════════════════════════════");
            player1.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "TRADE COMPLETED!");
            player1.sendMessage(ChatColor.GREEN + "Successfully traded with " + player2.getName());
            player1.sendMessage(ChatColor.GREEN + "═══════════════════════════════");

            player2.sendMessage(ChatColor.GREEN + "═══════════════════════════════");
            player2.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "TRADE COMPLETED!");
            player2.sendMessage(ChatColor.GREEN + "Successfully traded with " + player1.getName());
            player2.sendMessage(ChatColor.GREEN + "═══════════════════════════════");

            player1.playSound(player1.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            player2.playSound(player2.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);


            // --- DETAILED STAFF BROADCAST ---
            String p1ItemsString = formatItemStacks(p1Items);
            String p2ItemsString = formatItemStacks(p2Items);

            sendTradeStaffBroadcast(
                    ChatColor.GREEN + "Trade Completed: " +
                            ChatColor.AQUA + player1.getName() + ChatColor.GREEN + " gave " + ChatColor.GOLD + p1ItemsString +
                            ChatColor.GREEN + " to " +
                            ChatColor.LIGHT_PURPLE + player2.getName() + ChatColor.GREEN + " for " + ChatColor.GOLD + p2ItemsString +
                            ChatColor.GREEN + "."
            );

            cleanup();
        }

        private boolean hasInventorySpace(Player player, List<ItemStack> items) {
            // Count actual items (not null/air)
            int actualItems = 0;
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR) {
                    actualItems++;
                }
            }

            // Count free slots
            int freeSlots = 0;
            for (ItemStack slot : player.getInventory().getStorageContents()) {
                if (slot == null || slot.getType() == Material.AIR) {
                    freeSlots++;
                }
            }

            // Simple check: do we have enough free slots?
            return actualItems <= freeSlots;
        }

        public void cancel() {
            returnItems();
            player1.sendMessage(ChatColor.RED + "Trade cancelled.");
            player2.sendMessage(ChatColor.RED + "Trade cancelled.");
            cleanup();
        }

        public void forceCancel() {
            returnItems();
            cleanup();
        }

        private void returnItems() {
            // Return items to original owners
            for (int slot : player1Slots) {
                ItemStack item = gui.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    player1.getInventory().addItem(item);
                }
            }

            for (int slot : player2Slots) {
                ItemStack item = gui.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    player2.getInventory().addItem(item);
                }
            }
        }

        private void cleanup() {
            if (timeoutTask != null) {
                timeoutTask.cancel();
            }

            player1.closeInventory();
            player2.closeInventory();

            activeTrades.remove(player1.getUniqueId());
            activeTrades.remove(player2.getUniqueId());
            busyPlayers.remove(player1.getUniqueId());
            busyPlayers.remove(player2.getUniqueId());
        }

        public boolean isInvolved(Player player) {
            return player.equals(player1) || player.equals(player2);
        }

        public Inventory getGui() {
            return gui;
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;

        Player player = (Player) e.getWhoClicked();
        TradeSession session = activeTrades.get(player.getUniqueId());

        if (session == null) return;
        if (!e.getInventory().equals(session.getGui())) return;

        // Cancel by default, let the session decide if it should be allowed
        e.setCancelled(true);

        // Handle the click
        session.handleClick(player, e.getSlot(), e);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;

        Player player = (Player) e.getPlayer();
        TradeSession session = activeTrades.get(player.getUniqueId());

        if (session != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (activeTrades.containsKey(player.getUniqueId())) {
                    session.cancel();
                }
            }, 1);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        UUID playerId = player.getUniqueId();

        TradeSession session = activeTrades.get(playerId);
        if (session != null) {
            session.cancel();
        }

        tradeRequests.values().removeIf(uuid -> uuid.equals(playerId));
        tradeRequests.remove(playerId);
        busyPlayers.remove(playerId);
    }
}