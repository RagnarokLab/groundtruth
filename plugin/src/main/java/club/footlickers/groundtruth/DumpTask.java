package club.footlickers.groundtruth;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time backfill for chunks that generated before this plugin existed. Deliberately the
 * "big IO op upfront" Billy signed off on - but it must never put the server at risk. After
 * this runs once, the live ChunkListener keeps the index current with zero ongoing scanning.
 *
 * Improvements over the original fixed-count version (Billy's ask 2026-09-20, "don't crash it"):
 *  - Candidates come from the real region-file headers, not "every slot in every region file" -
 *    so chunks that were never generated are never even probed (a chunk's 4-byte entry is zero
 *    when absent). Skips a lot of pointless IO, especially on sparse worlds (nether/custom dims).
 *  - Per-tick work is TIME-BOXED, not fixed-count: a tick stops once it has spent MAX_MS_PER_TICK,
 *    so a slow disk/chunk can't push the tick over budget the way a fixed 8 chunks could.
 *  - Already-indexed chunks are checked in memory (one upfront query) instead of a synchronous
 *    SQLite read per candidate on the main thread.
 * Still never forces generation of a single chunk (isChunkGenerated is checked first, every time).
 */
public class DumpTask extends BukkitRunnable {

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    // Hard ceiling per tick; the TIME budget below is the real limiter, this just bounds the
    // all-already-indexed fast path.
    private static final int MAX_CHUNKS_PER_TICK = 64;
    // Max wall-clock a single tick of dump work may take. Keeps the tick bounded regardless of
    // how slow any one chunk load is - this is the "don't crash it" knob, not a chunk count.
    private static final long MAX_MS_PER_TICK = 25;
    // If more than this long elapsed since our last tick, the server is running behind
    // (20 TPS = 50 ms/tick) - skip work this tick. Portable equivalent of a TPS floor,
    // without depending on the Paper/CraftBukkit-only Server#getTPS().
    private static final long TICK_BEHIND_MS = 60;

    private final Plugin plugin;
    private final Storage storage;
    private final ChunkIndexer indexer;
    private final World world;
    private final CommandSender notify;

    private final Deque<int[]> candidates = new ArrayDeque<>();
    private final Set<Long> indexed = new HashSet<>();
    private long lastRunAt = 0;
    private long scanned = 0;
    private long generated = 0;
    private long indexedNow = 0;
    private final long startedAt = System.currentTimeMillis();

    public DumpTask(Plugin plugin, Storage storage, ChunkIndexer indexer, World world, CommandSender notify) {
        this.plugin = plugin;
        this.storage = storage;
        this.indexer = indexer;
        this.world = world;
        this.notify = notify;
        indexed.addAll(storage.chunkKeys(world.getName()));
        buildCandidateList();
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private void buildCandidateList() {
        File regionDir = new File(world.getWorldFolder(), "region");
        File[] files = regionDir.listFiles((dir, name) -> REGION_FILE.matcher(name).matches());
        if (files == null || files.length == 0) {
            notify.sendMessage("[GroundTruth] No region files found for " + world.getName() + " at " + regionDir);
            return;
        }
        byte[] header = new byte[4096];
        for (File f : files) {
            Matcher m = REGION_FILE.matcher(f.getName());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1));
            int rz = Integer.parseInt(m.group(2));
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                if (raf.length() < 4096) continue;
                raf.readFully(header);
                for (int i = 0; i < 1024; i++) {
                    int offset = ((header[i * 4] & 0xFF) << 16)
                            | ((header[i * 4 + 1] & 0xFF) << 8)
                            | (header[i * 4 + 2] & 0xFF);
                    if (offset == 0) continue; // chunk absent from this region file - skip it entirely
                    int lx = i % 32, lz = i / 32;
                    candidates.add(new int[] { rx * 32 + lx, rz * 32 + lz });
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[GroundTruth] Couldn't read region file " + f.getName() + ": " + e);
            }
        }
        notify.sendMessage("[GroundTruth] " + world.getName() + ": " + files.length + " region file(s), "
                + candidates.size() + " generated chunk(s) to check.");
    }

    @Override
    public void run() {
        // Never compete with actual players - pause the whole backfill while anyone's online.
        // (Resumes automatically once the server is empty again.)
        if (!plugin.getServer().getOnlinePlayers().isEmpty()) {
            return;
        }
        // Live backoff: if the server itself reports it isn't keeping up, skip real work this tick.
        if (!plugin.getServer().getServerTickManager().isRunningNormally()) {
            return;
        }
        // And a hard floor on tick health, so the backfill can never drag the server down.
        long now = System.currentTimeMillis();
        long sinceLast = lastRunAt == 0 ? 0 : now - lastRunAt;
        lastRunAt = now;
        if (sinceLast > TICK_BEHIND_MS) {
            return;
        }
        long budgetEnd = System.currentTimeMillis() + MAX_MS_PER_TICK;
        int processed = 0;
        while (System.currentTimeMillis() < budgetEnd && processed < MAX_CHUNKS_PER_TICK && !candidates.isEmpty()) {
            int[] coord = candidates.poll();
            int cx = coord[0], cz = coord[1];
            processed++;
            scanned++;

            if (indexed.contains(key(cx, cz))) continue; // already have it (in-memory check)
            if (!world.isChunkGenerated(cx, cz)) continue; // corruption guard - never force generation
            generated++;

            boolean wasLoaded = world.isChunkLoaded(cx, cz);
            try {
                Chunk chunk = world.getChunkAt(cx, cz);
                indexer.indexChunk(chunk);
                indexed.add(key(cx, cz));
                indexedNow++;
                if (!wasLoaded) {
                    // Read-only backfill: don't save. The old `unload(true)` rewrote every
                    // dumped chunk to disk for nothing, doubling IO and fighting the server.
                    chunk.unload(false);
                }
            } catch (Exception e) {
                // One bad chunk must never stop or spam-loop the whole backfill.
                plugin.getLogger().warning("[GroundTruth] Skipped chunk " + cx + "," + cz + " during dump: " + e);
            }
        }

        if (candidates.isEmpty()) {
            long secs = (System.currentTimeMillis() - startedAt) / 1000;
            notify.sendMessage("[GroundTruth] Dump of " + world.getName() + " complete in " + secs + "s: "
                    + scanned + " chunk(s) checked, " + generated + " already generated, "
                    + indexedNow + " newly indexed.");
            this.cancel();
        }
    }
}
