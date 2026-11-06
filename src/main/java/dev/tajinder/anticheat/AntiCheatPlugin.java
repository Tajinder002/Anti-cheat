package dev.tajinder.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AntiCheatPlugin extends JavaPlugin implements Listener {
    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "AntiCheat" + ChatColor.DARK_GRAY + "] ";
    private static final Map<String, String> CATEGORIES = Map.of(
            "combat", "Combat Checks",
            "movement", "Movement Checks",
            "world", "World Protection",
            "exploits", "Exploit Protection"
    );
    private static final String GUI_PREFIX = ChatColor.DARK_AQUA + "AntiCheat » ";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AntiCheat loaded. Use /ac to open the control panel.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("threshold")) {
            if (args.length != 2) {
                sender.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /ac threshold <number>");
                return true;
            }
            try {
                int value = Integer.parseInt(args[1]);
                if (value < 1 || value > 10000) throw new NumberFormatException();
                getConfig().set("settings.violation-threshold", value);
                saveConfig();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Violation threshold set to " + value + ".");
            } catch (NumberFormatException ex) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Enter a whole number between 1 and 10000.");
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("player")) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Player violation profiles will be added with the detection engine.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "Run this command in-game.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("checks")) {
            openCategory(player, "combat");
        } else {
            openMain(player);
        }
        return true;
    }

    private void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, GUI_PREFIX + "Control Panel");
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(10, item(Material.IRON_SWORD, ChatColor.RED + "Combat Checks", "KillAura, Reach, AimAssist, AutoClicker"));
        inv.setItem(12, item(Material.FEATHER, ChatColor.GREEN + "Movement Checks", "Fly, Speed, Timer, Scaffold, Phase"));
        inv.setItem(14, item(Material.DIAMOND_ORE, ChatColor.AQUA + "World Protection", "Anti-Xray and mining analysis"));
        inv.setItem(16, item(Material.COMPARATOR, ChatColor.LIGHT_PURPLE + "Exploit Protection", "Packet, inventory and interaction checks"));
        inv.setItem(31, item(Material.REDSTONE_TORCH, ChatColor.YELLOW + "Global Settings", "Alerts and violation threshold"));
        inv.setItem(40, item(Material.BARRIER, ChatColor.RED + "Close"));
        player.openInventory(inv);
    }

    private void openCategory(Player player, String category) {
        ConfigurationSection section = getConfig().getConfigurationSection("checks." + category);
        if (section == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "That category is not configured.");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 54, GUI_PREFIX + CATEGORIES.get(category));
        int slot = 10;
        for (String key : section.getKeys(false)) {
            if (slot == 17 || slot == 26 || slot == 35 || slot == 44) slot += 2;
            boolean enabled = section.getBoolean(key, true);
            inv.setItem(slot++, item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                    (enabled ? ChatColor.GREEN : ChatColor.RED) + pretty(key),
                    "Status: " + (enabled ? "ENABLED" : "DISABLED"),
                    "Click to toggle"));
        }
        inv.setItem(45, item(Material.ARROW, ChatColor.YELLOW + "Back"));
        inv.setItem(49, item(Material.BARRIER, ChatColor.RED + "Close"));
        player.openInventory(inv);
    }

    private void openSettings(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_PREFIX + "Settings");
        boolean alerts = getConfig().getBoolean("settings.alerts", true);
        inv.setItem(11, item(alerts ? Material.LIME_DYE : Material.GRAY_DYE,
                ChatColor.YELLOW + "Staff Alerts", "Status: " + (alerts ? "ON" : "OFF"), "Click to toggle"));
        inv.setItem(13, item(Material.PAPER, ChatColor.AQUA + "Violation Threshold",
                "Current: " + getConfig().getInt("settings.violation-threshold", 10),
                "Change with /ac threshold <number>"));
        inv.setItem(15, item(Material.BOOK, ChatColor.LIGHT_PURPLE + "Punishment Command",
                getConfig().getString("settings.punish-command", "Not configured")));
        inv.setItem(22, item(Material.ARROW, ChatColor.YELLOW + "Back"));
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.startsWith(GUI_PREFIX)) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        if (title.equals(GUI_PREFIX + "Control Panel")) {
            switch (slot) {
                case 10 -> openCategory(player, "combat");
                case 12 -> openCategory(player, "movement");
                case 14 -> openCategory(player, "world");
                case 16 -> openCategory(player, "exploits");
                case 31 -> openSettings(player);
                case 40 -> player.closeInventory();
                default -> { }
            }
            return;
        }

        if (title.equals(GUI_PREFIX + "Settings")) {
            if (slot == 11) {
                boolean next = !getConfig().getBoolean("settings.alerts", true);
                getConfig().set("settings.alerts", next);
                saveConfig();
                openSettings(player);
                player.sendMessage(PREFIX + "Staff alerts " + (next ? "enabled." : "disabled."));
            } else if (slot == 22) {
                openMain(player);
            }
            return;
        }

        String category = categoryFromTitle(title);
        if (category == null) return;
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 45) {
            openMain(player);
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String display = clicked.getItemMeta().getDisplayName();
        String key = ChatColor.stripColor(display).toLowerCase(Locale.ROOT).replace(' ', '-');
        String path = "checks." + category + "." + key;
        if (!getConfig().contains(path)) return;

        boolean enabled = !getConfig().getBoolean(path);
        getConfig().set(path, enabled);
        saveConfig();
        openCategory(player, category);
        player.sendMessage(PREFIX + pretty(key) + (enabled ? " enabled." : " disabled."));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().startsWith(GUI_PREFIX)) event.setCancelled(true);
    }

    private String categoryFromTitle(String title) {
        for (Map.Entry<String, String> entry : CATEGORIES.entrySet()) {
            if (title.equals(GUI_PREFIX + entry.getValue())) return entry.getKey();
        }
        return null;
    }

    private void fill(Inventory inventory, Material material) {
        ItemStack pane = item(material, " ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, pane);
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(List.of(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    private String pretty(String key) {
        String[] words = key.replace('-', ' ').replace('_', ' ').split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
