package dev.herobrine;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HerobrinePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final int TICK_STEP = 2;

    /** Natural ground that signs may be placed on (never player-built floors). */
    private static final Set<Material> SIGN_GROUND = EnumSet.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
            Material.ROOTED_DIRT, Material.MYCELIUM, Material.SAND, Material.RED_SAND,
            Material.GRAVEL, Material.SNOW_BLOCK, Material.STONE, Material.MOSS_BLOCK);

    private enum EventType { STARE, WALK, JUMPSCARE, SIGN }

    private final Random random = new Random();
    private final Map<UUID, Haunt> haunts = new ConcurrentHashMap<>();
    private final List<Location> placedSigns = new ArrayList<>();
    private NPCRegistry registry;
    private BukkitTask rollTask;
    private BukkitTask tickTask;

    /** One active Herobrine sighting for one player. */
    private static final class Haunt {
        final NPC npc;
        final EventType type;
        final int lifetime;
        int stareTicks = 0;
        int age = 0;

        Haunt(NPC npc, EventType type, int lifetime) {
            this.npc = npc;
            this.type = type;
            this.lifetime = lifetime;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Temporary registry: Herobrine NPCs are never saved to Citizens' npcs file
        registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
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
        long interval = Math.max(1, getConfig().getInt("check-interval-seconds", 30)) * 20L;
        rollTask = getServer().getScheduler().runTaskTimer(this, this::rollDice, interval, interval);
        tickTask = getServer().getScheduler().runTaskTimer(this, this::tickHaunts, TICK_STEP, TICK_STEP);
    }

    private void stop() {
        if (rollTask != null) rollTask.cancel();
        if (tickTask != null) tickTask.cancel();
    }

    // ------------------------------------------------------------------
    // Random events
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
            trigger(player, pickEvent());
        }
    }

    /** Picks an event type using the weights in config.yml. */
    private EventType pickEvent() {
        int stare = Math.max(0, getConfig().getInt("events.stare", 50));
        int walk = Math.max(0, getConfig().getInt("events.walk", 25));
        int scare = Math.max(0, getConfig().getInt("events.jumpscare", 15));
        int sign = Math.max(0, getConfig().getInt("events.sign", 10));
        int total = stare + walk + scare + sign;
        if (total <= 0) return EventType.STARE;

        int roll = random.nextInt(total);
        if ((roll -= stare) < 0) return EventType.STARE;
        if ((roll -= walk) < 0) return EventType.WALK;
        if ((roll -= scare) < 0) return EventType.JUMPSCARE;
        return EventType.SIGN;
    }

    /** Runs one event for a player. Returns true on success. */
    private boolean trigger(Player target, EventType type) {
        if (type == EventType.SIGN) return placeSign(target);
        if (haunts.containsKey(target.getUniqueId())) return false;

        Location spot;
        switch (type) {
            case WALK -> spot = findSpot(target,
                    getConfig().getDouble("walk.min-distance", 15),
                    getConfig().getDouble("walk.max-distance", 24));
            case JUMPSCARE -> spot = findFrontSpot(target);
            default -> spot = findSpot(target,
                    getConfig().getDouble("min-distance", 20),
                    getConfig().getDouble("max-distance", 32));
        }
        if (spot == null) return false;

        int lifetime = switch (type) {
            case WALK -> getConfig().getInt("walk.max-seconds", 12) * 20;
            case JUMPSCARE -> getConfig().getInt("jumpscare.duration-ticks", 30);
            default -> getConfig().getInt("max-lifetime-seconds", 60) * 20;
        };

        NPC npc = spawnNpc(spot);
        if (npc == null) return false;

        if (type == EventType.WALK) {
            Location dest = walkDestination(spot, target);
            if (dest == null) {
                npc.destroy();
                return false;
            }
            npc.getNavigator().getDefaultParameters().speedModifier(
                    (float) getConfig().getDouble("walk.speed", 1.0));
            npc.getNavigator().setTarget(dest);
        }

        haunts.put(target.getUniqueId(), new Haunt(npc, type, lifetime));

        switch (type) {
            case STARE -> {
                fakeMessage("join", false);
                target.playSound(target.getLocation(), Sound.AMBIENT_CAVE, 1.0f, 1.0f);
            }
            case WALK -> fakeMessage("join", false);
            case JUMPSCARE -> jumpscareEffects(target);
            default -> { }
        }
        return true;
    }

    private void jumpscareEffects(Player player) {
        String sound = getConfig().getString("jumpscare.sound", "entity.enderman.scream");
        player.playSound(player.getLocation(), sound, 2.0f, 0.8f);
        int darkness = getConfig().getInt("jumpscare.darkness-ticks", 40);
        if (darkness > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darkness, 0, false, false, false));
        }
    }

    // ------------------------------------------------------------------
    // Finding spots
    // ------------------------------------------------------------------

    /** Open-air ground spot between min and max blocks away from the player. */
    private Location findSpot(Player target, double min, double max) {
        World world = target.getWorld();
        Location base = target.getLocation();
        max = Math.max(min + 1, max);

        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = min + random.nextDouble() * (max - min);
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = world.getHighestBlockYAt(x, z);
            if (Math.abs(y - base.getBlockY()) > 6) continue;
            if (!world.getBlockAt(x, y, z).getType().isSolid()) continue;

            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    /** A free standing spot a few blocks in front of the player (works indoors too). */
    private Location findFrontSpot(Player target) {
        World world = target.getWorld();
        Location base = target.getLocation();
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir.normalize();

        double distance = getConfig().getDouble("jumpscare.distance", 4.0);

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = Math.toRadians(random.nextInt(51) - 25);
            double dx = dir.getX() * Math.cos(angle) - dir.getZ() * Math.sin(angle);
            double dz = dir.getX() * Math.sin(angle) + dir.getZ() * Math.cos(angle);
            double dist = distance + random.nextDouble();
            int x = base.getBlockX() + (int) Math.round(dx * dist);
            int z = base.getBlockZ() + (int) Math.round(dz * dist);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            for (int dy : new int[]{0, -1, 1}) {
                Block feet = world.getBlockAt(x, base.getBlockY() + dy, z);
                Block head = feet.getRelative(BlockFace.UP);
                Block below = feet.getRelative(BlockFace.DOWN);
                if (below.getType().isSolid()
                        && feet.isPassable() && !feet.isLiquid()
                        && head.isPassable() && !head.isLiquid()) {
                    return new Location(world, x + 0.5, feet.getY(), z + 0.5);
                }
            }
        }
        return null;
    }

    /** A point to the side of the player that Herobrine walks toward. */
    private Location walkDestination(Location from, Player target) {
        World world = from.getWorld();
        Vector toPlayer = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (toPlayer.lengthSquared() < 1.0E-4) return null;
        toPlayer.normalize();

        double side = random.nextBoolean() ? 1 : -1;
        Vector perp = new Vector(-toPlayer.getZ() * side, 0, toPlayer.getX() * side);
        double dist = getConfig().getDouble("walk.distance", 18);

        int x = from.getBlockX() + (int) Math.round(perp.getX() * dist);
        int z = from.getBlockZ() + (int) Math.round(perp.getZ() * dist);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;

        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        if (Math.abs(y - from.getBlockY()) > 8) return null;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    /** Creates a Citizens player NPC with Herobrine's skin and no visible name. */
    private NPC spawnNpc(Location loc) {
        NPC npc = registry.createNPC(EntityType.PLAYER, "Herobrine");
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.getOrAddTrait(SkinTrait.class).setSkinName(getConfig().getString("skin-name", "Herobrine"));
        if (!npc.spawn(loc)) {
            npc.destroy();
            return null;
        }
        return npc;
    }

    // ------------------------------------------------------------------
    // Signs
    // ------------------------------------------------------------------

    /**
     * Places a creepy sign on open natural ground near the player.
     * It only ever uses an empty air block, so nothing is replaced or broken.
     */
    private boolean placeSign(Player player) {
        List<String> messages = getConfig().getStringList("sign.messages");
        if (messages.isEmpty()) return false;

        World world = player.getWorld();
        Location base = player.getLocation();
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir.normalize();

        for (int attempt = 0; attempt < 25; attempt++) {
            double angle = Math.toRadians(random.nextInt(121) - 60);
            double dx = dir.getX() * Math.cos(angle) - dir.getZ() * Math.sin(angle);
            double dz = dir.getX() * Math.sin(angle) + dir.getZ() * Math.cos(angle);
            double dist = 5 + random.nextDouble() * 8;
            int x = base.getBlockX() + (int) Math.round(dx * dist);
            int z = base.getBlockZ() + (int) Math.round(dz * dist);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int gy = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (Math.abs(gy - base.getBlockY()) > 4) continue;

            Block ground = world.getBlockAt(x, gy, z);
            if (!SIGN_GROUND.contains(ground.getType())) continue;

            Block spot = ground.getRelative(BlockFace.UP);
            if (!spot.getType().isAir()) continue;

            String text = messages.get(random.nextInt(messages.size()));
            writeSign(spot, text, faceToward(spot.getLocation(), base));

            int removeAfter = getConfig().getInt("sign.remove-after-seconds", 300);
            if (removeAfter > 0) {
                Location loc = spot.getLocation();
                placedSigns.add(loc);
                getServer().getScheduler().runTaskLater(this, () -> removeSign(loc), removeAfter * 20L);
            }
            return true;
        }
        return false;
    }

    private void writeSign(Block block, String text, BlockFace face) {
        block.setType(Material.OAK_SIGN, false);

        BlockData data = block.getBlockData();
        if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(face);
            block.setBlockData(rotatable, false);
        }

        if (block.getState() instanceof Sign sign) {
            SignSide side = sign.getSide(Side.FRONT);
            String[] lines = text.split("\\|", -1);
            for (int i = 0; i < 4; i++) {
                side.line(i, Component.text(i < lines.length ? lines[i].trim() : ""));
            }
            side.setColor(DyeColor.RED);
            sign.setWaxed(true); // players can't edit it
            sign.update(true, false);
        }
    }

    private BlockFace faceToward(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void removeSign(Location loc) {
        if (!placedSigns.remove(loc)) return;
        Block block = loc.getBlock();
        if (block.getState() instanceof Sign) {
            block.setType(Material.AIR, false);
        }
    }

    // ------------------------------------------------------------------
    // Per-tick behaviour
    // ------------------------------------------------------------------

    private void tickHaunts() {
        double vanishDist = getConfig().getDouble("vanish-distance", 8);
        int stareLimit = getConfig().getInt("stare-seconds", 2) * 20;

        Iterator<Map.Entry<UUID, Haunt>> it = haunts.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Haunt haunt = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            NPC npc = haunt.npc;

            if (player == null || !player.isOnline() || !npc.isSpawned()
                    || !player.getWorld().equals(npc.getEntity().getWorld())) {
                npc.destroy();
                it.remove();
                continue;
            }

            haunt.age += TICK_STEP;

            Entity body = npc.getEntity();
            Location pos = body.getLocation();

            // Face the player, except while walking
            if (haunt.type != EventType.WALK) {
                npc.faceLocation(player.getEyeLocation());
            }

            // Time is up
            if (haunt.age >= haunt.lifetime) {
                vanish(npc, haunt.type);
                it.remove();
                continue;
            }

            // Jumpscares only last their short duration
            if (haunt.type == EventType.JUMPSCARE) continue;

            // Finished walking
            if (haunt.type == EventType.WALK && haunt.age > 10 && !npc.getNavigator().isNavigating()) {
                vanish(npc, haunt.type);
                it.remove();
                continue;
            }

            // Player got too close
            double distance = pos.distance(player.getLocation());
            if (distance < vanishDist) {
                vanish(npc, haunt.type);
                it.remove();
                continue;
            }

            // Being stared at
            Vector eyeToNpc = pos.clone().add(0, 1.6, 0).toVector()
                    .subtract(player.getEyeLocation().toVector()).normalize();
            double dot = player.getEyeLocation().getDirection().dot(eyeToNpc);
            if (dot > 0.96 && player.hasLineOfSight(body)) {
                haunt.stareTicks += TICK_STEP;
                if (haunt.stareTicks >= stareLimit) {
                    vanish(npc, haunt.type);
                    it.remove();
                }
            } else {
                haunt.stareTicks = Math.max(0, haunt.stareTicks - 1);
            }
        }
    }

    private void vanish(NPC npc, EventType type) {
        Location loc = npc.getEntity().getLocation();
        World world = loc.getWorld();
        world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.02);
        if (type == EventType.STARE && getConfig().getBoolean("lightning-on-vanish", true)) {
            world.strikeLightningEffect(loc); // visual + sound only, no damage or fire
        }
        npc.destroy();
        if (type == EventType.STARE || type == EventType.WALK) {
            fakeMessage("leave", false);
        }
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
        for (Haunt haunt : haunts.values()) {
            NPC npc = haunt.npc;
            if (withEffects && npc.isSpawned()) {
                Location loc = npc.getEntity().getLocation();
                loc.getWorld().spawnParticle(Particle.SMOKE, loc.add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.02);
            }
            npc.destroy();
        }
        haunts.clear();

        for (Location loc : new ArrayList<>(placedSigns)) {
            removeSign(loc);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Haunt haunt = haunts.remove(event.getPlayer().getUniqueId());
        if (haunt != null) haunt.npc.destroy();
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private static final List<String> SUBCOMMANDS = List.of(
            "summon", "stare", "walk", "jumpscare", "sign", "fakejoin", "fakeleave", "clear", "reload");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text(
                    "Usage: /herobrine <summon|stare|walk|jumpscare|sign> [player] | fakejoin | fakeleave | clear | reload",
                    NamedTextColor.GRAY));
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "summon", "stare", "walk", "jumpscare", "sign" -> {
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else {
                    target = sender instanceof Player p ? p : null;
                }
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                EventType type = sub.equals("summon") ? pickEvent() : EventType.valueOf(sub.toUpperCase());
                if (trigger(target, type)) {
                    sender.sendMessage(Component.text("Done (" + type.name().toLowerCase() + ") for "
                            + target.getName() + ".", NamedTextColor.DARK_RED));
                } else {
                    sender.sendMessage(Component.text(
                            "Couldn't do that (no good spot, or he's already there).", NamedTextColor.RED));
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
            for (String s : SUBCOMMANDS) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && List.of("summon", "stare", "walk", "jumpscare", "sign")
                .contains(args[0].toLowerCase())) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
                                                       }
