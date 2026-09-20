package club.footlickers.groundtruth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GroundTruthPlugin extends JavaPlugin implements CommandExecutor {

    private Storage storage;
    private ChunkIndexer indexer;
    private final Map<String, DumpTask> dumpsInProgress = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            storage = new Storage(getDataFolder(), getLogger());
        } catch (SQLException e) {
            getLogger().severe("[GroundTruth] Failed to open groundtruth.db, disabling: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        indexer = new ChunkIndexer(this, storage);
        getServer().getPluginManager().registerEvents(new ChunkListener(indexer), this);
        getCommand("groundtruth").setExecutor(this);
        getLogger().info("[GroundTruth] Ready. New chunks are indexed live; run /groundtruth dump <world> to backfill existing ones.");
    }

    @Override
    public void onDisable() {
        if (storage != null) storage.close();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /groundtruth <dump|stop|status|worlds|pos|players|find|portal|slime> ...");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "dump":
                return handleDump(sender, args);
            case "stop":
                return handleStop(sender, args);
            case "status":
                return handleStatus(sender, args);
            case "worlds":
                return handleWorlds(sender, args);
            case "pos":
                return handlePos(sender, args);
            case "players":
                return handlePlayers(sender, args);
            case "find":
                return handleFind(sender, args);
            case "portal":
                return handlePortal(sender, args);
            case "slime":
                return handleSlime(sender, args);
            default:
                sender.sendMessage("Unknown subcommand. Usage: /groundtruth <dump|stop|status|worlds|pos|players|find|portal|slime> ...");
                return true;
        }
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("Ops only.");
            return true;
        }
        World world = resolveWorld(sender, args, 1);
        if (world == null) {
            sender.sendMessage("Unknown world. Usage: /groundtruth stop <world>");
            return true;
        }
        DumpTask task = dumpsInProgress.get(world.getName());
        if (task == null) {
            sender.sendMessage("No dump running for " + world.getName() + ".");
            return true;
        }
        task.cancel();
        sender.sendMessage("[GroundTruth] Stopped the dump for " + world.getName() + ".");
        return true;
    }

    /**
     * Real Bukkit answer for one chunk - only used against already-generated chunks (never
     * forces generation). Exists mainly to cross-check the client-side slime chunk formula
     * used by the web viewer against the real game engine before trusting it in bulk.
     */
    private boolean handleSlime(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /groundtruth slime <x> <z> [world] - block coords");
            return true;
        }
        int x, z;
        try {
            x = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("x and z must be integers.");
            return true;
        }
        World world = resolveWorld(sender, args, 3);
        if (world == null) {
            sender.sendMessage("Unknown world.");
            return true;
        }
        int cx = x >> 4, cz = z >> 4;
        if (!world.isChunkGenerated(cx, cz)) {
            sender.sendMessage("Chunk " + cx + "," + cz + " isn't generated yet - won't force it just to check.");
            return true;
        }
        boolean slime = world.getChunkAt(cx, cz).isSlimeChunk();
        sender.sendMessage("[GroundTruth] chunk " + cx + "," + cz + " (seed " + world.getSeed() + "): slime=" + slime);
        return true;
    }

    /**
     * Overworld<->nether coordinate scaling (the real 8:1 ratio Java Edition portal linking
     * uses). Not a claim about where an EXISTING portal actually links - the game reuses any
     * portal it finds within its own search radius of the scaled point rather than always
     * making a new one, so this is "where a new portal would land," which is what a calculator
     * is for anyway.
     */
    private boolean handlePortal(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /groundtruth portal <x> <z> [world]");
            return true;
        }
        int x, z;
        try {
            x = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("x and z must be integers.");
            return true;
        }
        World world = resolveWorld(sender, args, 3);
        if (world == null) {
            sender.sendMessage("Unknown world.");
            return true;
        }
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER) {
            sender.sendMessage(String.format("[GroundTruth] Nether %d,%d -> Overworld %d,%d", x, z, x * 8, z * 8));
        } else if (env == World.Environment.NORMAL) {
            sender.sendMessage(String.format("[GroundTruth] Overworld %d,%d -> Nether %d,%d", x, z, x / 8, z / 8));
        } else {
            sender.sendMessage("[GroundTruth] " + world.getName() + " isn't an overworld or nether dimension - nothing to scale.");
        }
        return true;
    }

    private boolean handleDump(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("Ops only.");
            return true;
        }
        World world = resolveWorld(sender, args, 1);
        if (world == null) {
            sender.sendMessage("Unknown world. Usage: /groundtruth dump <world>");
            return true;
        }
        if (dumpsInProgress.containsKey(world.getName())) {
            sender.sendMessage("A dump for " + world.getName() + " is already running. /groundtruth stop " + world.getName() + " to cancel it.");
            return true;
        }
        sender.sendMessage("[GroundTruth] Starting backfill of " + world.getName()
                + " - this is a real one-time IO pass, it will take a while on a big world. /groundtruth stop " + world.getName() + " cancels it early.");
        DumpTask task = new DumpTask(this, storage, indexer, world, sender) {
            @Override
            public synchronized void cancel() {
                dumpsInProgress.remove(world.getName());
                super.cancel();
            }
        };
        dumpsInProgress.put(world.getName(), task);
        task.runTaskTimer(this, 20L, 1L);
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        World world = resolveWorld(sender, args, 1);
        if (world == null) {
            sender.sendMessage("Unknown world. Usage: /groundtruth status <world>");
            return true;
        }
        long chunks = storage.countChunks(world.getName());
        long structs = storage.countStructures(world.getName());
        sender.sendMessage("[GroundTruth] " + world.getName() + ": " + chunks + " chunk(s) indexed, "
                + structs + " structure(s) recorded.");
        return true;
    }

    private boolean handleFind(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /groundtruth find <structure_type_or_part> [world]");
            return true;
        }
        String typeFilter = args[1];
        World world;
        int originX = 0, originZ = 0;
        if (sender instanceof Player) {
            Player p = (Player) sender;
            world = args.length > 2 ? resolveWorld(sender, args, 2) : p.getWorld();
            originX = p.getLocation().getBlockX();
            originZ = p.getLocation().getBlockZ();
        } else {
            world = args.length > 2 ? resolveWorld(sender, args, 2) : Bukkit.getWorlds().get(0);
        }
        if (world == null) {
            sender.sendMessage("Unknown world.");
            return true;
        }
        List<Storage.StructureHit> hits = storage.findNearest(world.getName(), typeFilter, originX, originZ, 5);
        if (hits.isEmpty()) {
            sender.sendMessage("No indexed structures matching \"" + typeFilter + "\" in " + world.getName()
                    + " yet - either none have generated near explored areas, or that area hasn't been indexed.");
            return true;
        }
        sender.sendMessage("[GroundTruth] Nearest matches for \"" + typeFilter + "\" in " + world.getName() + ":");
        for (Storage.StructureHit h : hits) {
            double dist = Math.sqrt(Math.pow(h.x - originX, 2) + Math.pow(h.z - originZ, 2));
            sender.sendMessage(String.format("  %s at %d,%d,%d (%.0f blocks) - bbox %d,%d,%d to %d,%d,%d",
                    h.type, h.x, h.y, h.z, dist, h.minX, h.minY, h.minZ, h.maxX, h.maxY, h.maxZ));
        }
        return true;
    }

    /** Machine-readable list of every loaded world (the map's dimension selector reads this via RCON). */
    private boolean handleWorlds(CommandSender sender, String[] args) {
        StringBuilder sb = new StringBuilder("GTWORLDS|");
        for (World w : Bukkit.getWorlds()) {
            sb.append(w.getName()).append(",").append(w.getEnvironment().name()).append(",")
              .append(storage.countChunks(w.getName())).append(",")
              .append(storage.countStructures(w.getName())).append(";");
        }
        sender.sendMessage(sb.toString());
        return true;
    }

    /** Live player position + the REAL name of the world they're in (no name assumptions). */
    private boolean handlePos(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /groundtruth pos <player>");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            sender.sendMessage("GTPOS|error|" + args[1] + " is not online.");
            return true;
        }
        Location loc = p.getLocation();
        sender.sendMessage(String.format("GTPOS|%s|%.1f|%.1f|%.1f",
                p.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()));
        return true;
    }

    /** Machine-readable list of every online player: name, uuid, x, y, z, world. */
    private boolean handlePlayers(CommandSender sender, String[] args) {
        StringBuilder sb = new StringBuilder("GTPLAYERS|");
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            sb.append(p.getName()).append(",")
              .append(p.getUniqueId()).append(",")
              .append(String.format("%.1f", l.getX())).append(",")
              .append(String.format("%.1f", l.getY())).append(",")
              .append(String.format("%.1f", l.getZ())).append(",")
              .append(p.getWorld().getName()).append(";");
        }
        sender.sendMessage(sb.toString());
        return true;
    }

    private World resolveWorld(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            return Bukkit.getWorld(args[index]);
        }
        if (sender instanceof Player) {
            return ((Player) sender).getWorld();
        }
        return null;
    }
}
