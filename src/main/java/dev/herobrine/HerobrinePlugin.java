package dev.herobrine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HerobrinePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final int TICK_STEP = 2;

    private final Random random = new Random();
    private final Map<UUID, Haunt> haunts = new ConcurrentHashMap<>();
    private volatile PlayerProfile skinProfile;
    private BukkitTask rollTask;
    private BukkitTask tickTask;

    /** One active Herobrine sighting for one player. */
    private static final class Haunt {
        final ArmorStand stand;
        int stareTicks = 0;
        int age = 0;

        Haunt(ArmorStand stand) {
            this.stand = stand;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("herobrine");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        start();
    }

    @Override
    public void onDisable() {
        stop();
        clearAll(false);
    }

    private void start() {
        loadSkin();
        long interval = Math.max(1, getConfig().getInt("check-interval-seconds", 30)) * 20L;
        rollTask = getServer().getScheduler().runTaskTimer(this, this::rollDice, interval, interval);
        tickTask = getServer().getScheduler().runTaskTimer(this, this::tickHaunts, TICK_STEP, TICK_STEP);
    }

    private void stop() {
        if (rollTask != null) rollTask.cancel();
        if (tickTask != null) tickTask.cancel();
    }

    private void loadSkin() {
        String name = getConfig().getString("skin-name", "Herobrine");
        PlayerProfile profile = Bukkit.createProfile(name);
        // Resolve textures off the main thread (needs internet access).
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                profile.complete(true);
            } catch (Exception e) {
                getLogger().warning("Could not load skin for '" + name + "': " + e.getMessage());
            }
            skinProfile = profile;
        });
    }

    // ------------------------------------------------------------------
    // Random appearances
    // ------------------------------------------------------------------

    private void rollDice() {
        double chance = getConfig().getDouble("appear-chance", 0.05);
        boolean nightOnly = getConfig().getBoolean("night-only", false);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (haunts.containsKey(player.getUniqueId())) continue;
            if (player.getGameMode() == GameMode.SPECTATOR) continue;
            World world = player.getWorld();
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            if (nightOnly && world.getTime() < 13000) continue;
            if (random.nextDouble() > chance) continue;
            summon(player);
        }
    }

    /** Tries to place Herobrine somewhere in the distance. Returns true on success. */
    private boolean summon(Player target) {
        if (haunts.containsKey(target.getUniqueId())) return false;

        World world = target.getWorld();
        Location base = target.getLocation();
        double min = getConfig().getDouble("min-distance", 20);
        double max = Math.max(min + 1, getConfig().getDouble("max-distance", 32));

        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = min + random.nextDouble() * (max - min);
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = world.getHighestBlockYAt(x, z);
            if (Math.abs(y - base.getBlockY()) > 6) continue;
            if (!world.getBlockAt(x, y, z).getType().isSolid()) continue;

            Location spot = new Location(world, x + 0.5, y + 1, z + 0.5);
            ArmorStand stand = spawnBody(spot);
            haunts.put(target.getUniqueId(), new Haunt(stand));

            fakeMessage("join", false);
            target.playSound(target.getLocation(), Sound.AMBIENT_CAVE, 1.0f, 1.0f);
            return true;
        }
        return false;
    }

    private ArmorStand spawnBody(Location loc) {
        return loc.getWorld().spawn(loc, ArmorStand.class, s -> {
            s.setArms(true);
            s.setBasePlate(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setPersistent(false);
            s.setMarker(true);
            s.setSilent(true);
            s.setRightArmPose(EulerAngle.ZERO);
            s.setLeftArmPose(EulerAngle.ZERO);

            var eq = s.getEquipment();
            eq.setHelmet(makeHead());
            eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, Color.fromRGB(0x2AB7B7)));
            eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, Color.fromRGB(0x3B3B9D)));
            eq.setBoots(dyed(Material.LEATHER_BOOTS, Color.fromRGB(0x6B6B6B)));
        });
    }

    private ItemStack makeHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        PlayerProfile profile = skinProfile;
        if (profile != null) meta.setPlayerProfile(profile);
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack dyed(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------
    // Per-tick behaviour: stare at the player, vanish when noticed
    // ------------------------------------------------------------------

    private void tickHaunts() {
        double vanishDist = getConfig().getDouble("vanish-distance", 8);
        int stareLimit = getConfig().getInt("stare-seconds", 2) * 20;
        int maxAge = getConfig().getInt("max-lifetime-seconds", 60) * 20;

        Iterator<Map.Entry<UUID, Haunt>> it = haunts.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Haunt haunt = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            ArmorStand stand = haunt.stand;

            if (player == null || !player.isOnline() || !stand.isValid()
                    || !player.getWorld().equals(stand.getWorld())) {
                stand.remove();
                it.remove();
                continue;
            }

            haunt.age += TICK_STEP;

            // Always face the player
            Location pos = stand.getLocation();
            Vector toPlayer = player.getEyeLocation().toVector().subtract(pos.toVector());
            pos.setDirection(toPlayer);
            stand.setRotation(pos.getYaw(), 0);

            // Too close, or lingered too long
            double distance = pos.distance(player.getLocation());
            if (distance < vanishDist || haunt.age > maxAge) {
                vanish(stand, player);
                it.remove();
                continue;
            }

            // Being stared at
            Vector eyeToStand = pos.clone().add(0, 1.5, 0).toVector()
                    .subtract(player.getEyeLocation().toVector()).normalize();
            double dot = player.getEyeLocation().getDirection().dot(eyeToStand);
            if (dot > 0.96 && player.hasLineOfSight(stand)) {
                haunt.stareTicks += TICK_STEP;
                if (haunt.stareTicks >= stareLimit) {
                    vanish(stand, player);
                    it.remove();
                }
            } else {
                haunt.stareTicks = Math.max(0, haunt.stareTicks - 1);
            }
        }
    }

    private void vanish(ArmorStand stand, Player player) {
        Location loc = stand.getLocation();
        World world = loc.getWorld();
        world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.02);
        if (getConfig().getBoolean("lightning-on-vanish", true)) {
            world.strikeLightningEffect(loc); // visual + sound only, no damage or fire
        }
        stand.remove();
        fakeMessage("leave", false);
    }

    /** Broadcasts the fake join/leave message. When force is false, the config toggle and chance apply. */
    private void fakeMessage(String type, boolean force) {
        String path = "fake-messages." + type;
        if (!force) {
            if (!getConfig().getBoolean(path + ".enabled", true)) return;
            if (random.nextDouble() >= getConfig().getDouble(path + ".chance", 0.15)) return;
        }
        String fallback = type.equals("join")
                ? "<yellow>Herobrine joined the game"
                : "<yellow>Herobrine left the game";
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                getConfig().getString(path + ".message", fallback)));
    }

    private void clearAll(boolean withEffects) {
        for (var entry : haunts.entrySet()) {
            ArmorStand stand = entry.getValue().stand;
            if (withEffects && stand.isValid()) {
                stand.getWorld().spawnParticle(Particle.SMOKE, stand.getLocation().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.02);
            }
            stand.remove();
        }
        haunts.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Haunt haunt = haunts.remove(event.getPlayer().getUniqueId());
        if (haunt != null) haunt.stand.remove();
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /herobrine <summon [player]|fakejoin|fakeleave|clear|reload>", NamedTextColor.GRAY));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "summon" -> {
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else {
                    target = sender instanceof Player p ? p : null;
                }
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                } else if (summon(target)) {
                    sender.sendMessage(Component.text("He is watching " + target.getName() + ".", NamedTextColor.DARK_RED));
                } else {
                    sender.sendMessage(Component.text("Couldn't find a spot (or he's already there).", NamedTextColor.RED));
                }
            }
            case "fakejoin" -> fakeMessage("join", true);
            case "fakeleave" -> fakeMessage("leave", true);
            case "clear" -> {
                clearAll(true);
                sender.sendMessage(Component.text("Herobrine has been removed.", NamedTextColor.GRAY));
            }
            case "reload" -> {
                stop();
                clearAll(false);
                reloadConfig();
                start();
                sender.sendMessage(Component.text("Config reloaded.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("summon", "fakejoin", "fakeleave", "clear", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("summon")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
