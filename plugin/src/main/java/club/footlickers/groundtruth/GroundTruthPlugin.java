package club.footlickers.groundtruth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
    private LogDb logDb;
    private Auth auth;
    private final Map<String, DumpTask> dumpsInProgress = new HashMap<>();
    private final Map<java.util.UUID, Location> lastSample = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            storage = new Storage(getDataFolder(), getLogger());
        } catch (SQLException e) {
            getLogger().severe("[GroundTruth] Failed to open groundtruth.db, disabling: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            logDb = new LogDb(getDataFolder(), getLogger());
        } catch (SQLException e) {
            getLogger().warning("[GroundTruth] Failed to open groundtruth-log.db, logging disabled: " + e.getMessage());
        }
        indexer = new ChunkIndexer(this, storage);
        // one login code for everyone: the plugin mints signed tokens, the web verifies them
        if (!getConfig().isString("auth-secret") || getConfig().getString("auth-secret", "").isEmpty()) {
            getConfig().set("auth-secret", Auth.randomSecret());
            saveConfig();
        }
        auth = new Auth(getConfig().getString("auth-secret"));
        getServer().getPluginManager().registerEvents(new ChunkListener(indexer), this);
        if (logDb != null) {
            getServer().getPluginManager().registerEvents(new LogListener(logDb), this);
            startPositionSampler();
        }
        getCommand("groundtruth").setExecutor(this);
        getLogger().info("[GroundTruth] Ready. New chunks are indexed live; run /groundtruth dump <world> to backfill existing ones.");
    }

    @Override
    public void onDisable() {
        if (storage != null) storage.close();
        if (logDb != null) logDb.close();
    }

    /** Samples player positions for the heatmap: at most one row per player per ~8 blocks / 5s. */
    private void startPositionSampler() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location l = p.getLocation();
                Location prev = lastSample.get(p.getUniqueId());
                if (prev == null || prev.getWorld() != l.getWorld() || prev.distanceSquared(l) >= 64) {
                    lastSample.put(p.getUniqueId(), l.clone());
                    logDb.position(p.getUniqueId().toString(), l.getWorld().getName(),
                            l.getBlockX(), l.getBlockY(), l.getBlockZ(), System.currentTimeMillis());
                }
            }
        }, 100L, 100L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /groundtruth <dump|stop|status|worlds|pos|players|find|portal|slime|lookup|rollback|link|logstatus> ...");
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
            case "lookup":
                return handleLookup(sender, args);
            case "rollback":
                return handleRollback(sender, args);
            case "link":
            case "login":
                return handleLink(sender, args);
            case "logstatus":
                return handleLogStatus(sender, args);
            default:
                sender.sendMessage("Unknown subcommand. Usage: /groundtruth <dump|stop|status|worlds|pos|players|find|portal|slime|lookup|rollback|link|logstatus> ...");
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

    /**
     * /groundtruth lookup [player] [radius] [minutes] [action] - recent logged events, newest first.
     * Radius is centred on the sender when they're a player, otherwise on 0,0.
     */
    private boolean handleLookup(CommandSender sender, String[] args) {
        if (logDb == null) { sender.sendMessage("Logging isn't enabled on this server."); return true; }
        String player = args.length > 1 && !args[1].equals("-") ? args[1] : null;
        int radius = args.length > 2 ? parseInt(args[2], 0) : 0;
        int minutes = args.length > 3 ? parseInt(args[3], 60) : 60;
        String action = args.length > 4 ? args[4] : null;
        World world = sender instanceof Player ? ((Player) sender).getWorld() : null;
        int ox = 0, oz = 0;
        if (sender instanceof Player) {
            ox = ((Player) sender).getLocation().getBlockX();
            oz = ((Player) sender).getLocation().getBlockZ();
        }
        long since = System.currentTimeMillis() - minutes * 60_000L;
        List<LogDb.Hit> hits = logDb.lookup(world == null ? null : world.getName(), player, ox, oz, radius, since, action, 20);
        if (hits.isEmpty()) { sender.sendMessage("[GroundTruth] No logged events match."); return true; }
        sender.sendMessage("[GroundTruth] " + hits.size() + " recent event(s)"
                + (world != null ? " in " + world.getName() : "") + ":");
        for (LogDb.Hit h : hits) {
            sender.sendMessage(String.format("  %s  %s  %s  @%d,%d,%d  %s",
                    java.time.Instant.ofEpochMilli(h.ts).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0),
                    h.action, h.actorName != null ? h.actorName : (h.actorId != null ? h.actorId : "?"),
                    h.x, h.y, h.z, h.target != null ? h.target : ""));
        }
        return true;
    }

    private final Map<java.util.UUID, PendingRollback> pendingRollbacks = new HashMap<>();

    private static class PendingRollback {
        final List<LogDb.Hit> hits;
        final String query;
        PendingRollback(List<LogDb.Hit> hits, String query) { this.hits = hits; this.query = query; }
    }

    /**
     * /groundtruth rollback <player> [minutes] [radius]   -> preview (then `confirm` / `cancel`)
     * /groundtruth rollback undo <rollback_id>            -> re-apply the `after` states
     */
    private boolean handleRollback(CommandSender sender, String[] args) {
        if (logDb == null) { sender.sendMessage("Logging isn't enabled on this server."); return true; }
        if (!sender.isOp()) { sender.sendMessage("Ops only."); return true; }
        if (args.length < 2) {
            sender.sendMessage("Usage: /groundtruth rollback <player|mob> [minutes] [radius] | confirm | cancel | undo <id>");
            sender.sendMessage("  e.g. rollback creeper 120 64  /  rollback enderman 1440  /  rollback Steve 30");
            return true;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("confirm")) return applyPendingRollback(sender);
        if (sub.equals("cancel")) {
            pendingRollbacks.remove(senderKey(sender));
            sender.sendMessage("[GroundTruth] Pending rollback cancelled.");
            return true;
        }
        if (sub.equals("undo")) {
            if (args.length < 3) { sender.sendMessage("Usage: /groundtruth rollback undo <rollback_id>"); return true; }
            long id;
            try { id = Long.parseLong(args[2]); }
            catch (NumberFormatException e) { sender.sendMessage("Rollback id must be a number."); return true; }
            List<LogDb.Hit> hits = logDb.findReverted(id);
            if (hits.isEmpty()) { sender.sendMessage("[GroundTruth] No events found for rollback #" + id + "."); return true; }
            new RollbackTask(hits, sender, "{\"undo_of\":" + id + "}", true).runTaskTimer(this, 1L, 1L);
            return true;
        }

        // preview
        String player = args[1];
        int minutes = args.length > 2 ? parseInt(args[2], 15) : 15;
        int radius = args.length > 3 ? parseInt(args[3], 0) : 0;
        World world = sender instanceof Player ? ((Player) sender).getWorld() : null;
        int ox = 0, oz = 0;
        if (sender instanceof Player) {
            ox = ((Player) sender).getLocation().getBlockX();
            oz = ((Player) sender).getLocation().getBlockZ();
        }
        long since = System.currentTimeMillis() - minutes * 60_000L;
        List<LogDb.Hit> hits = logDb.findRevertible(world == null ? null : world.getName(),
                player, ox, oz, radius, since, 50000);
        if (hits.isEmpty()) {
            sender.sendMessage("[GroundTruth] Nothing to roll back for " + player + " in the last " + minutes + " min.");
            return true;
        }
        String query = "{\"player\":" + LogListener.Json.str(player) + ",\"minutes\":" + minutes
                + ",\"radius\":" + radius + ",\"blocks\":" + hits.size() + "}";
        pendingRollbacks.put(senderKey(sender), new PendingRollback(hits, query));
        sender.sendMessage("[GroundTruth] Rollback PREVIEW: " + hits.size() + " block(s) by " + player
                + " (last " + minutes + " min" + (radius > 0 ? ", within " + radius + " blocks" : "") + ").");
        for (int i = 0; i < Math.min(5, hits.size()); i++) {
            LogDb.Hit h = hits.get(i);
            sender.sendMessage("  " + h.action + " @ " + h.x + "," + h.y + "," + h.z + " -> " + h.before);
        }
        if (hits.size() > 5) sender.sendMessage("  ... and " + (hits.size() - 5) + " more");
        sender.sendMessage("[GroundTruth] Run /groundtruth rollback confirm to apply, or rollback cancel.");
        return true;
    }

    private boolean applyPendingRollback(CommandSender sender) {
        PendingRollback pr = pendingRollbacks.remove(senderKey(sender));
        if (pr == null) {
            sender.sendMessage("[GroundTruth] Nothing pending - run /groundtruth rollback <player> first.");
            return true;
        }
        sender.sendMessage("[GroundTruth] Applying rollback of " + pr.hits.size() + " block(s)...");
        new RollbackTask(pr.hits, sender, pr.query, false).runTaskTimer(this, 1L, 1L);
        return true;
    }

    private static java.util.UUID senderKey(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : new java.util.UUID(0, 0);
    }

    /** Applies block edits spread over ticks so a large rollback can't freeze the server. */
    private class RollbackTask extends org.bukkit.scheduler.BukkitRunnable {
        private final List<LogDb.Hit> hits;
        private final CommandSender sender;
        private final String query;
        private final boolean undo;
        private final List<Long> ids = new java.util.ArrayList<>();
        private int i = 0, applied = 0, failed = 0;

        RollbackTask(List<LogDb.Hit> hits, CommandSender sender, String query, boolean undo) {
            this.hits = hits; this.sender = sender; this.query = query; this.undo = undo;
        }

        @Override
        public void run() {
            int budget = 400; // blocks per tick
            while (i < hits.size() && budget-- > 0) {
                LogDb.Hit h = hits.get(i++);
                World w = h.world != null ? Bukkit.getWorld(h.world) : null;
                if (w == null) { failed++; continue; }
                String state = undo ? h.after : h.before;
                try {
                    Block b = w.getBlockAt(h.x, h.y, h.z);
                    b.setBlockData(state != null ? Bukkit.createBlockData(state)
                            : Material.AIR.createBlockData(), false);
                    ids.add(h.id);
                    applied++;
                } catch (Exception e) {
                    failed++;
                }
            }
            if (i < hits.size()) return; // more next tick
            int marked = undo
                    ? logDb.unmarkReverted(ids, senderKey(sender).toString(), sender.getName(), query)
                    : logDb.markReverted(ids, senderKey(sender).toString(), sender.getName(), query);
            sender.sendMessage("[GroundTruth] " + (undo ? "Undo" : "Rollback") + " complete: " + applied
                    + " block(s)" + (failed > 0 ? ", " + failed + " failed" : "")
                    + "; " + marked + " event(s) updated.");
            cancel();
        }
    }

    /**
     * /groundtruth link - one login code for the map. Admins (groundtruth.admin or op) get a long-lived
     * reusable code; everyone else gets a one-shot code that expires quickly.
     */
    private boolean handleLink(CommandSender sender, String[] args) {
        if (sender instanceof Player) {
            Player p = (Player) sender;
            boolean admin = p.hasPermission("groundtruth.admin") || p.isOp();
            long ttl = admin ? 30L * 24 * 3600 * 1000 : 15L * 60 * 1000;
            String token = auth.token(p.getUniqueId().toString(), p.getName(), admin, ttl);
            sender.sendMessage("[GroundTruth] Your login code" + (admin ? " (admin)" : "") + ":");
            sender.sendMessage(token);
            sender.sendMessage("[GroundTruth] Paste it into the map login box. "
                    + (admin ? "Valid 30 days, one use." : "One use, expires in 15 minutes."));
            return true;
        }
        // Console / RCON: console access already means full control, so this is always an admin code.
        long ttl = 30L * 24 * 3600 * 1000;
        String token = auth.token("console", sender.getName(), true, ttl);
        sender.sendMessage("[GroundTruth] Admin login code (console):");
        sender.sendMessage(token);
        sender.sendMessage("[GroundTruth] Valid 30 days, one use. (It will appear in the server log.)");
        return true;
    }

    private boolean handleLogStatus(CommandSender sender, String[] args) {
        if (logDb == null) { sender.sendMessage("Logging isn't enabled on this server."); return true; }
        sender.sendMessage(String.format("[GroundTruth] log: %,d events stored, %,d written, %,d dropped, queue %d",
                logDb.eventCount(), logDb.written(), logDb.dropped(), logDb.queueDepth()));
        return true;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
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
