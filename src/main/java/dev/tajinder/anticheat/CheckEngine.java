package dev.tajinder.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
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
    private final Map<UUID, Deque<Long>> movementPackets = new HashMap<>();
    private final Map<UUID, Set<UUID>> recentTargets = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Long> hoverSince = new HashMap<>();
    private final Map<UUID, Long> liquidSince = new HashMap<>();
    private final Map<UUID, Long> noSlowSince = new HashMap<>();
    private final Map<UUID, Integer> speedBuffer = new HashMap<>();
    private final Map<UUID, Integer> hoverBuffer = new HashMap<>();
    private final Map<UUID, Long> lastMoveNanos = new HashMap<>();
    private final Map<UUID, Long> lastFlag = new HashMap<>();
    private final Set<UUID> punished = new HashSet<>();

    public CheckEngine(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || !survival(player) || from.getWorld() != to.getWorld()) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        UUID id = player.getUniqueId();
        long now = System.nanoTime();
        long previous = lastMoveNanos.getOrDefault(id, 0L);
        lastMoveNanos.put(id, now);
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        double horizontal = Math.hypot(dx, dz);
        boolean exempt = player.isFlying() || player.getAllowFlight() || player.isGliding()
                || player.isInsideVehicle() || player.isSwimming() || player.isInWater()
                || player.isClimbing() || player.isRiptiding()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        // Large one-off movements and long packet gaps are treated as lag/teleport corrections.
        if (horizontal > 1.25 || Math.abs(dy) > 2.0 || (previous != 0L && now - previous > 250_000_000L)) {
            speedBuffer.remove(id);
            hoverBuffer.remove(id);
            hoverSince.remove(id);
            return;
        }

        if (!exempt && enabled("movement.timer")) {
            Deque<Long> moves = window(movementPackets, id, 1000);
            moves.addLast(System.currentTimeMillis());
            if (moves.size() > 36) {
                flag(player, "Timer suspicion", 1, "more than 36 position updates in one second");
                moves.clear();
            }
        }

        if (!exempt && enabled("movement.speed") && horizontal > speedLimit(player)) {
            int buffer = speedBuffer.merge(id, 1, Integer::sum);
            if (buffer >= 4) {
                flag(player, "Speed", 1, "repeated high horizontal movement");
                speedBuffer.put(id, 0);
            }
        } else {
            speedBuffer.computeIfPresent(id, (key, value) -> Math.max(0, value - 1));
        }

        if (!exempt && enabled("movement.fly")) {
            boolean hovering = !player.isOnGround() && Math.abs(dy) < 0.015
                    && player.getVelocity().getY() > -0.06 && !hasSolidSupport(player);
            if (hovering) {
                long since = hoverSince.computeIfAbsent(id, key -> now);
                if (now - since >= 2_500_000_000L) {
                    flag(player, "Fly", 2, "unsupported hovering persisted for 2.5 seconds");
                    hoverSince.put(id, now);
                }
            } else {
                hoverSince.remove(id);
            }
        } else {
            airTicks.remove(id);
            hoverBuffer.remove(id);
            hoverSince.remove(id);
        }

        if (enabled("movement.phase") && !player.isInsideVehicle() && isInsideSolidBlock(player)) {
            flag(player, "Phase", 2, "player bounding box intersected solid blocks");
        }

        boolean liquidWalk = enabled("movement.jesus") && isOverLiquid(player) && !player.isOnGround()
                && Math.abs(dy) < 0.015 && player.getVelocity().getY() > -0.05 && !exempt;
        if (liquidWalk) {
            long since = liquidSince.computeIfAbsent(id, key -> now);
            if (now - since >= 900_000_000L) {
                flag(player, "Jesus", 2, "unsupported movement over liquid persisted for 0.9 seconds");
                liquidSince.put(id, now);
            }
        } else {
            liquidSince.remove(id);
        }

        boolean usingItem = enabled("movement.noslow") && player.isHandRaised() && horizontal > 0.16 && !exempt;
        if (usingItem) {
            long since = noSlowSince.computeIfAbsent(id, key -> now);
            if (now - since >= 1_200_000_000L) {
                flag(player, "NoSlow", 1, "unusually fast movement while continuously using an item");
                noSlowSince.put(id, now);
            }
        } else {
            noSlowSince.remove(id);
        }
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (enabled("movement.nofall") && survival(player) && player.getFallDistance() >= 6.0f
                && event.getDamage() <= 0.0) {
            flag(player, "NoFall suspicion", 1, "long fall produced no damage");
        }
    }

    @EventHandler
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (event.isFlying() && enabled("movement.fly") && survival(player) && !player.getAllowFlight()) {
            flag(player, "Fly", 2, "flight toggle without permission");
        }
    }

    private double speedLimit(Player player) {
        double limit = player.isSprinting() ? 0.38 : 0.30;
        limit *= Math.max(0.5, player.getWalkSpeed() / 0.2);
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            limit *= 1.0 + 0.20 * (player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1);
        }
        return limit + 0.08;
    }

    private boolean hasSolidSupport(Player player) {
        return player.getLocation().clone().subtract(0, 0.12, 0).getBlock().getType().isSolid();
    }

    private boolean isInsideSolidBlock(Player player) {
        BoundingBox box = player.getBoundingBox();
        int minX = (int) Math.floor(box.getMinX() + 0.001);
        int maxX = (int) Math.floor(box.getMaxX() - 0.001);
        int minY = (int) Math.floor(box.getMinY() + 0.05);
        int maxY = (int) Math.floor(box.getMaxY() - 0.05);
        int minZ = (int) Math.floor(box.getMinZ() + 0.001);
        int maxZ = (int) Math.floor(box.getMaxZ() - 0.001);
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++)
            for (int z = minZ; z <= maxZ; z++) {
                Material type = player.getWorld().getBlockAt(x, y, z).getType();
                if (type.isSolid() && type.isOccluding()) return true;
            }
        return false;
    }

    private boolean isOverLiquid(Player player) {
        Material below = player.getLocation().clone().subtract(0, 0.08, 0).getBlock().getType();
        return below == Material.WATER || below == Material.LAVA;
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
        UUID id = event.getPlayer().getUniqueId();
        airTicks.remove(id); speedBuffer.remove(id); hoverBuffer.remove(id); lastMoveNanos.remove(id);
        liquidSince.remove(id); noSlowSince.remove(id); hoverSince.remove(id); movementPackets.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        swings.remove(id); placements.remove(id); breaks.remove(id); attacks.remove(id); movementPackets.remove(id);
        recentTargets.remove(id); violations.remove(id); airTicks.remove(id); lastFlag.remove(id); punished.remove(id);
        speedBuffer.remove(id); hoverBuffer.remove(id); lastMoveNanos.remove(id); hoverSince.remove(id); liquidSince.remove(id); noSlowSince.remove(id);
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
