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

import java.io.File;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GroundTruthPlugin extends JavaPlugin implements CommandExecutor {

    private Storage storage;
    private ChunkIndexer indexer;
    private LogDb logDb;
    private WebServer web;
    private org.bukkit.scheduler.BukkitTask playerSnapTask, heatTask, posTask, invTask, reindexTask, idleTask;
    private BlockChangeListener blockChange;
    private MapColours colours;
    private Auth auth;
    private final Map<String, Process> externalDumps = new HashMap<>();
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
        // write defaults into config.yml so the knobs are discoverable
        getConfig().addDefault("dumper-script", "/opt/groundtruth-dumper/live-dump.sh");
        // "Only show what we've actually seen": one setting that scopes BOTH the 2D tiles and the 3D
        // view to chunks a player has visited, plus an N-chunk buffer so the edges look representative.
        // Off = render the whole world (fine for a small world, big for a large one).
        getConfig().addDefault("render-only-visited", true);
        getConfig().addDefault("render-visited-radius", 15);
        getConfig().addDefault("admin-code-hours", 24);
        // A player's login CODE is one-shot and short, but the session token the browser keeps should
        // last - otherwise a logged-in map loses live players and waypoint saving after 15 minutes.
        getConfig().addDefault("player-session-days", 30);
        // --- the web API, served by the plugin itself (migration away from the Python service) ---
        // 0 disables it. Ports must not clash with anything else on the box.
        getConfig().addDefault("web-enabled", true);
        getConfig().addDefault("web-port", 8096);
        getConfig().addDefault("web-threads", 4);
        // A shared key for local assistants (Sassy). Set it in config.yml - never in the source,
        // this repo is public. Empty means key auth is off and only login codes work.
        getConfig().addDefault("service-key", "");
        // Where the map page/JS and the rendered tiles live, and the old service to fall back to
        // while endpoints are still being ported into the plugin (empty disables the fallback).
        getConfig().addDefault("web-static", "/opt/groundtruth-web/static");
        getConfig().addDefault("tiles-dir", "/opt/groundtruth-web/tiles");
        getConfig().addDefault("proxy-to", "http://127.0.0.1:8095");
        // The map's public URL, told to clients on join so the mod needs no per-user config.
        getConfig().addDefault("public-api-url", "");
        // The plugin writes the same per-block layers the offline dumper does, so a server that only
        // runs the plugin still gets full detail and 3D for new chunks. The dumper is then only for
        // backfilling an existing world and for maintenance renders.
        getConfig().addDefault("detail-indexing", true);
        getConfig().addDefault("detail-lod-levels", "1,2,3,4");
        getConfig().addDefault("debug-chunk-events", false);
        getConfig().addDefault("inventory-snapshot-seconds", 300);
        getConfig().addDefault("heat-aggregate-seconds", 600);
        getConfig().options().copyDefaults(true);
        saveConfig();
        colours = new MapColours();
        colours.load(new java.io.File(getConfig().getString("tiles-dir", "/opt/groundtruth-web/tiles")));
        indexer = new ChunkIndexer(this, storage, colours,
                getConfig().getBoolean("detail-indexing", true), detailLodLevels());
        getServer().getPluginManager().registerEvents(new ChunkListener(indexer, this), this);
        // Player builds and natural changes must update the map too, not just new-chunk generation.
        blockChange = new BlockChangeListener(indexer, this);
        getServer().getPluginManager().registerEvents(blockChange, this);
        // Gentle drain: re-index a few dirty chunks a second so a big event (explosion) never
        // freezes the tick. indexChunk runs on the main thread; its DB writes are already async.
        reindexTask = getServer().getScheduler().runTaskTimer(this, () -> blockChange.drain(16), 40L, 20L);
        // ChunkListener only sees NEW chunks, so anything already loaded when we start (or that was
        // generated while we were reloading) would otherwise never be indexed.
        if (indexer != null) {
            for (org.bukkit.World w : getServer().getWorlds()) {
                for (org.bukkit.Chunk c : w.getLoadedChunks()) {
                    if (!storage.isChunkIndexed(w.getName(), c.getX(), c.getZ())) {
                        // a single bad chunk must never stop the plugin from enabling
                        try {
                            indexer.indexChunk(c);
                        } catch (Exception e) {
                            getLogger().warning("[GroundTruth] catch-up index failed for " + w.getName()
                                    + " " + c.getX() + "," + c.getZ() + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        if (logDb != null) {
            getServer().getPluginManager().registerEvents(new LogListener(logDb), this);
        }
        // Tell joining clients where the map is served: the join address and the map address can differ.
        String publicApi = getConfig().getString("public-api-url", "");
        getServer().getMessenger().registerOutgoingPluginChannel(this, MapAdvertListener.CHANNEL);
        getServer().getPluginManager().registerEvents(new MapAdvertListener(this, publicApi), this);
        if (!publicApi.isEmpty()) {
            getLogger().info("[GroundTruth] map API advertised to clients on join: " + publicApi);
        } else {
            getLogger().info("[GroundTruth] no public-api-url set; clients fall back to <server>:8095");
        }
        startComponents();
        getCommand("groundtruth").setExecutor(this);
        getLogger().info("[GroundTruth] Ready. New chunks are indexed live; run /groundtruth dump <world> to backfill existing ones.");
    }

    @Override
    public void onDisable() {
        if (reindexTask != null) { reindexTask.cancel(); reindexTask = null; }
        if (web != null) { web.stop(); web = null; }
        if (storage != null) storage.close();
        if (logDb != null) logDb.close();
    }

    /**
     * Character inventory logging: every N seconds, snapshot each online player's inventory IF it
     * changed since the last snapshot. That gives a real inventory history (for "my stuff vanished")
     * without a row every time something moves.
     */
    private final Map<java.util.UUID, Integer> invHash = new HashMap<>();

    private org.bukkit.scheduler.BukkitTask startInventorySampler() {
        int secs = getConfig().getInt("inventory-snapshot-seconds", 300);
        if (secs <= 0) return null;
        return getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                int h = 1;
                for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) {
                    h = h * 31 + (it == null ? 0 : it.getType().ordinal() * 131 + it.getAmount());
                }
                Integer prev = invHash.get(p.getUniqueId());
                if (prev != null && prev == h) continue;
                invHash.put(p.getUniqueId(), h);
                logDb.inventorySnapshot(p.getUniqueId().toString(), "periodic",
                        LogListener.snapshotJson(p.getInventory().getContents()), System.currentTimeMillis());
            }
        }, secs * 20L, secs * 20L);
    }

    /** Samples player positions for the heatmap: at most one row per player per ~8 blocks / 5s. */
    private org.bukkit.scheduler.BukkitTask startPositionSampler() {
        return getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location l = p.getLocation();
                Location prev = lastSample.get(p.getUniqueId());
                if (prev == null || prev.getWorld() != l.getWorld() || prev.distanceSquared(l) >= 64) {
                    lastSample.put(p.getUniqueId(), l.clone());
                    logDb.position(p.getUniqueId().toString(), l.getWorld().getName(),
                            l.getBlockX(), l.getBlockY(), l.getBlockZ(), System.currentTimeMillis());
                    final String u = p.getUniqueId().toString();
                    final String w = l.getWorld().getName();
                    final int vcx = l.getBlockX() >> 4, vcz = l.getBlockZ() >> 4;
                    getServer().getScheduler().runTaskAsynchronously(this, () -> storage.recordVisit(u, w, vcx, vcz));
                }
            }
        }, 100L, 100L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /groundtruth <dump|stop|status|pos|find|portal|slime|lookup|rollback|link|logstatus|reload> ..."
                    + "  (reload re-reads config.yml; a new JAR needs a server restart)");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "dump":
                return handleDump(sender, args);
            case "stop":
                return handleStop(sender, args);
            case "status":
                return handleStatus(sender, args);
            case "pos":
                return handlePos(sender, args);
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
            case "waypoint":
            case "wp":
                return handleWaypoint(sender, args);
            case "logstatus":
                return handleLogStatus(sender, args);
            case "reload":
                return handleReload(sender, args);
            default:
                sender.sendMessage("Unknown subcommand. Usage: /groundtruth <dump|stop|status|pos|find|portal|slime|lookup|rollback|link|logstatus> ...");
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
        Process p = externalDumps.get(world.getName());
        if (p == null || !p.isAlive()) {
            sender.sendMessage("No dump running for " + world.getName() + ".");
            return true;
        }
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        externalDumps.remove(world.getName());
        sender.sendMessage("[GroundTruth] Stopped the offline dump for " + world.getName() + ".");
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

    /**
     * /groundtruth dump <world> - spwans the OFFLINE dumper (a separate JVM, zero server tick impact)
     * instead of indexing in-process. The in-process path used to load chunks on the main thread and
     * tanked TPS; the offline dumper reads region files directly, so it's the only safe backfill.
     * Writes to `dumper-script` (default /opt/groundtruth-dumper/live-dump.sh), which runs the dumper
     * then rebuilds the tile pyramid.
     */
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
        String name = world.getName();
        Process existing = externalDumps.get(name);
        if (existing != null && existing.isAlive()) {
            sender.sendMessage("[GroundTruth] A dump for " + name + " is already running. /groundtruth stop " + name + " to cancel it.");
            return true;
        }
        File regionDir = new File(world.getWorldFolder(), "region");
        if (!regionDir.isDirectory()) {
            sender.sendMessage("[GroundTruth] No region dir at " + regionDir + " - can't dump " + name + ".");
            return true;
        }
        String script = getConfig().getString("dumper-script", "/opt/groundtruth-dumper/live-dump.sh");
        if (!new File(script).isFile()) {
            sender.sendMessage("[GroundTruth] Offline dumper script not found: " + script + " (set dumper-script in config.yml).");
            return true;
        }
        String db = new File(getDataFolder(), "groundtruth.db").getAbsolutePath();
        int minY = world.getMinHeight();
        // optional: only draw chunks a player has visited, plus an optional chunk radius around them
        String visitArg = getConfig().getBoolean("render-only-visited", false)
                ? String.valueOf(getConfig().getInt("render-visited-radius", 0)) : "-1";
        try {
            Process p = new ProcessBuilder("bash", script, name, regionDir.getAbsolutePath(), db,
                    String.valueOf(minY), visitArg)
                    .redirectErrorStream(true)
                    .redirectOutput(new File(getDataFolder(), "dump-" + name + ".log"))
                    .start();
            p.onExit().thenAccept(proc -> externalDumps.remove(name));
            externalDumps.put(name, p);
            sender.sendMessage("[GroundTruth] Started the OFFLINE dumper for " + name
                    + " (separate process, zero tick impact). Tiles rebuild when it finishes.");
            sender.sendMessage("[GroundTruth] /groundtruth stop " + name + " cancels it; log: plugins/GroundTruth/dump-"
                    + name + ".log");
        } catch (Exception e) {
            sender.sendMessage("[GroundTruth] Failed to start the offline dumper: " + e.getMessage());
        }
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
            boolean rollback = p.hasPermission("groundtruth.admin.rollback") || p.isOp();
            long adminTtl = getConfig().getLong("admin-code-hours", 24) * 3600_000L;
            long playerTtl = getConfig().getLong("player-session-days", 30) * 24L * 3600_000L;
            long ttl = admin ? adminTtl : playerTtl;
            String token = auth.token(p.getUniqueId().toString(), p.getName(), admin, rollback, ttl);
            sender.sendMessage("[GroundTruth] Your login code" + (admin ? " (admin" + (rollback ? "+rollback" : "") + ")" : "") + ":");
            sender.sendMessage(token);
            sender.sendMessage("[GroundTruth] Paste it into the map login box. "
                    + (admin ? "Valid until it expires (default 24h), one use."
                             : "One use, expires in 15 minutes."));
            return true;
        }
        // Console / RCON: console access already means full control, so this is always an admin code.
        long ttl = getConfig().getLong("admin-code-hours", 24) * 3600_000L;
        String token = auth.token("console", sender.getName(), true, true, ttl);
        sender.sendMessage("[GroundTruth] Admin login code (console):");
        sender.sendMessage(token);
        sender.sendMessage("[GroundTruth] One use. (It will appear in the server log.)");
        return true;
    }

    /**
     * /groundtruth waypoint add <name> [public|private] | remove <name> | list
     * Waypoints are stored in the map DB and shown on the website (public ones to everyone, private
     * ones only to their owner).
     */
    private boolean handleWaypoint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Run /groundtruth waypoint in-game (it's tied to your player).");
            return true;
        }
        Player p = (Player) sender;
        String sub = args.length > 1 ? args[1].toLowerCase() : "list";
        String uuid = p.getUniqueId().toString();
        if (sub.equals("add") || sub.equals("set")) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /groundtruth waypoint add <name> [public|private]");
                return true;
            }
            String name = args[2];
            boolean pub = args.length > 3 && args[3].equalsIgnoreCase("public");
            Location l = p.getLocation();
            storage.putWaypoint(uuid, name, l.getWorld().getName(),
                    l.getBlockX(), l.getBlockY(), l.getBlockZ(), pub);
            sender.sendMessage("[GroundTruth] Waypoint \"" + name + "\" saved (" + (pub ? "public" : "private")
                    + ") at " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ".");
            return true;
        }
        if (sub.equals("remove") || sub.equals("delete") || sub.equals("del")) {
            if (args.length < 3) {
                sender.sendMessage("Usage: /groundtruth waypoint remove <name>");
                return true;
            }
            boolean ok = storage.removeWaypoint(uuid, args[2]);
            sender.sendMessage("[GroundTruth] " + (ok ? "Removed waypoint \"" + args[2] + "\"."
                    : "No waypoint named \"" + args[2] + "\"."));
            return true;
        }
        List<Storage.Waypoint> wps = storage.listWaypoints(uuid);
        if (wps.isEmpty()) {
            sender.sendMessage("[GroundTruth] No waypoints yet. /groundtruth waypoint add <name> [public|private]");
            return true;
        }
        sender.sendMessage("[GroundTruth] " + wps.size() + " waypoint(s):");
        for (Storage.Waypoint w : wps) {
            sender.sendMessage("  " + w.name + (w.isPublic ? " (public)" : "") + " - " + w.world + " "
                    + w.x + "," + w.y + "," + w.z + (w.uuid.equals(uuid) ? "" : " [another player]"));
        }
        return true;
    }

    /**
     * Create (or re-create) everything that reads config or owns a resource: the auth secret, the
     * colour tables, the web server and its player snapshot, and the samplers. Safe to call on a
     * reload because stopComponents() tears all of it down first.
     */
    private void startComponents() {
        colours = new MapColours();
        colours.load(new java.io.File(getConfig().getString("tiles-dir", "/opt/groundtruth-web/tiles")));
        if (indexer != null) indexer.applyConfig(colours, getConfig().getBoolean("detail-indexing", true), detailLodLevels());

        if (!getConfig().isString("auth-secret") || getConfig().getString("auth-secret", "").isEmpty()) {
            getConfig().set("auth-secret", Auth.randomSecret());
            saveConfig();
        }
        auth = new Auth(getConfig().getString("auth-secret"));

        if (getConfig().getBoolean("web-enabled", true)) {
            try {
                web = new WebServer(this, storage, logDb, auth,
                        getConfig().getString("service-key", ""),
                        getConfig().getInt("web-port", 8096),
                        getConfig().getInt("web-threads", 4),
                        getConfig().getString("web-static", "/opt/groundtruth-web/static"),
                        getConfig().getString("tiles-dir", "/opt/groundtruth-web/tiles"),
                        getConfig().getString("proxy-to", "http://127.0.0.1:8095"),
                        getConfig().getBoolean("render-only-visited", true),
                        getConfig().getInt("render-visited-radius", 15),
                        getDataFolder(), colours);
                web.start();
                playerSnapTask = getServer().getScheduler().runTaskTimer(this, () -> {
                    if (web != null) web.refreshPlayers();
                }, 20L, 20L);
            } catch (Exception e) {
                getLogger().warning("[GroundTruth] web API failed to start on port "
                        + getConfig().getInt("web-port", 8096) + ": " + e.getMessage());
                web = null;
            }
        }
        if (logDb != null) {
            posTask = startPositionSampler();
            invTask = startInventorySampler();
            int heatSecs = getConfig().getInt("heat-aggregate-seconds", 600);
            if (heatSecs > 0) {
                heatTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                        () -> logDb.aggregateHeat(), 200L, heatSecs * 20L);
            }
        }

        // Close database connections once they have been idle. SQLite cannot checkpoint the WAL while
        // a connection is open, and a connection left open also keeps this process holding a wal-index
        // mapping that another process may reset underneath it.
        idleTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (storage != null) storage.closeIdle();
            if (logDb != null) logDb.closeIdle();
            if (web != null) web.closeIdle();
        }, 200L, 200L);
    }

    /** Tear down everything startComponents() created, so it can be created again cleanly. */
    private void stopComponents() {
        if (playerSnapTask != null) { playerSnapTask.cancel(); playerSnapTask = null; }
        if (heatTask != null) { heatTask.cancel(); heatTask = null; }
        if (posTask != null) { posTask.cancel(); posTask = null; }
        if (invTask != null) { invTask.cancel(); invTask = null; }
        if (idleTask != null) { idleTask.cancel(); idleTask = null; }
        if (web != null) { web.stop(); web = null; }
    }

    /** detail-lod-levels as an int array. */
    private int[] detailLodLevels() {
        String csv = getConfig().getString("detail-lod-levels", "1,2,3,4");
        if (csv == null || csv.isBlank()) return new int[0];
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].trim()); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /**
     * /groundtruth reload [config|full]
     *
     * A server restart is the wrong tool for a plugin change, so this is the first-class way to
     * apply a configuration change. It re-reads config.yml, rebuilds the colour tables, restarts the
     * web server and its player snapshot, and reschedules the samplers - everything that reads config
     * or owns a resource. It deliberately does not touch the plugin's classes: a new JAR is applied by
     * restarting the server, like any other plugin.
     */
    private boolean handleReload(CommandSender sender, String[] args) {
        // Deliberately self-contained: it re-reads config.yml and restarts everything that owns a
        // resource (web server, colour tables, samplers, indexer settings). No external reloader, no
        // classloader games, nothing that can drop the plugin. Replacing the JAR is a different
        // thing entirely and is done by restarting the server - as it is for any other plugin.
        reloadConfig();
        stopComponents();
        startComponents();
        sender.sendMessage("[GroundTruth] reloaded: config re-read; web server, colour tables and "
                + "samplers restarted. (A new JAR needs a server restart, same as any plugin.)");
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
