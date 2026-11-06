package dev.tajinder.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CheckEngine implements Listener {
    private final AntiCheatPlugin plugin;
    private final Map<UUID, Deque<Long>> swings = new HashMap<>();
    private final Map<UUID, Deque<Long>> placements = new HashMap<>();
    private final Map<UUID, Deque<Long>> breaks = new HashMap<>();
    private final Map<UUID, Deque<Long>> attacks = new HashMap<>();
    private final Map<UUID, Set<UUID>> recentTargets = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Long> lastFlag = new HashMap<>();
    private final Set<UUID> punished = new HashSet<>();

    public CheckEngine(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!survival(player) || event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        boolean exempt = player.isFlying() || player.getAllowFlight() || player.isGliding()
                || player.isInsideVehicle() || player.isSwimming()
                || player.isInWater() || player.isClimbing() || player.isRiptiding();

        if (!exempt && enabled("movement.speed") && horizontal > 0.82 && player.isOnGround()) {
            flag(player, "Speed", 1, "horizontal movement " + round(horizontal));
        }

        if (!exempt && enabled("movement.fly")) {
            int ticks = player.isOnGround() ? 0 : airTicks.getOrDefault(player.getUniqueId(), 0) + 1;
            airTicks.put(player.getUniqueId(), ticks);
            if (ticks > 45 && player.getVelocity().getY() > -0.08 && Math.abs(to.getY() - from.getY()) < 0.03) {
                flag(player, "Fly", 1, "extended unsupported airtime");
            }
        } else {
            airTicks.remove(player.getUniqueId());
        }

        if (enabled("movement.phase") && !player.isInsideVehicle()
                && player.getBoundingBox().overlaps(to.clone().add(0, 0.15, 0).getBlock().getBoundingBox())
                && to.getBlock().getType().isSolid()) {
            flag(player, "Phase", 2, "player moved into a solid block");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !survival(player)) return;
        Entity target = event.getEntity();
        if (enabled("combat.reach")) {
            BoundingBox box = target.getBoundingBox();
            double distance = box.getCenter().distance(player.getEyeLocation().toVector());
            if (distance > 3.65) flag(player, "Reach", 2, "eye-to-target distance " + round(distance));
        }
        if (enabled("combat.killaura")) {
            Deque<Long> times = window(attacks, player.getUniqueId(), 1200);
            times.addLast(System.currentTimeMillis());
            Set<UUID> targets = recentTargets.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
            targets.add(target.getUniqueId());
            if (times.size() >= 8 && targets.size() >= 4) {
                flag(player, "KillAura pattern", 1, "rapid attacks across multiple targets");
                targets.clear();
                times.clear();
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!survival(player) || !enabled("combat.autoclicker")) return;
        Deque<Long> times = window(swings, player.getUniqueId(), 1000);
        times.addLast(System.currentTimeMillis());
        if (times.size() >= 24) {
            flag(player, "AutoClicker suspicion", 1, "high swing rate in one second");
            times.clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!survival(player) || !enabled("exploits.fast-place")) return;
        Deque<Long> times = window(placements, player.getUniqueId(), 1000);
        times.addLast(System.currentTimeMillis());
        if (times.size() > 18) {
            flag(player, "FastPlace", 1, "more than 18 placements in one second");
            times.clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!survival(player)) return;
        if (enabled("exploits.fast-break")) {
            Deque<Long> times = window(breaks, player.getUniqueId(), 1000);
            times.addLast(System.currentTimeMillis());
            if (times.size() > 14) {
                flag(player, "FastBreak", 1, "more than 14 block breaks in one second");
                times.clear();
            }
        }
        if (enabled("world.mining-analysis") && isValuableOre(event.getBlock().getType())) {
            // Mining patterns are evidence only; a single ore discovery is never a violation.
            plugin.getLogger().fine(player.getName() + " mined " + event.getBlock().getType()
                    + " at " + event.getBlock().getX() + "," + event.getBlock().getY() + "," + event.getBlock().getZ());
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        airTicks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        swings.remove(id); placements.remove(id); breaks.remove(id); attacks.remove(id);
        recentTargets.remove(id); violations.remove(id); airTicks.remove(id); lastFlag.remove(id); punished.remove(id);
    }

    private boolean survival(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private boolean enabled(String path) {
        return plugin.getConfig().getBoolean("checks." + path, false);
    }

    private Deque<Long> window(Map<UUID, Deque<Long>> map, UUID id, long duration) {
        long now = System.currentTimeMillis();
        Deque<Long> deque = map.computeIfAbsent(id, k -> new ArrayDeque<>());
        while (!deque.isEmpty() && now - deque.peekFirst() > duration) deque.removeFirst();
        return deque;
    }

    private void flag(Player player, String check, int amount, String detail) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (now - lastFlag.getOrDefault(id, 0L) < 700) return;
        lastFlag.put(id, now);
        int total = violations.merge(id, amount, Integer::sum);
        String message = "§8[§bAntiCheat§8] §c" + player.getName() + " §7flagged §e" + check
                + " §8(VL: " + total + ") §7" + detail;
        if (plugin.getConfig().getBoolean("settings.alerts", true)) {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("anticheat.alerts") || staff.hasPermission("anticheat.admin")) {
                    staff.sendMessage(message);
                }
            }
        }
        if (plugin.getConfig().getBoolean("settings.debug", false)) plugin.getLogger().info(message);
        int threshold = Math.max(1, plugin.getConfig().getInt("settings.violation-threshold", 10));
        if (total >= threshold && punished.add(id)) {
            String command = plugin.getConfig().getString("settings.punish-command", "");
            if (command != null && !command.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
            }
        }
    }

    private boolean isValuableOre(Material material) {
        return material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS;
    }

    private String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
