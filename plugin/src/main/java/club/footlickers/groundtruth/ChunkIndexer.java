package club.footlickers.groundtruth;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Extracts what's really in a chunk via the live Bukkit/Paper API (which itself reads the
 * real chunk NBT under the hood) - this is the one place "ground truth" actually gets read.
 * Must be called on the main thread (Chunk/World calls aren't thread-safe); hands the result
 * off to Storage asynchronously so disk IO never blocks the server tick.
 */
public class ChunkIndexer {

    private final Plugin plugin;
    private final Storage storage;

    public ChunkIndexer(Plugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    /** Call on the main thread only. */
    public void indexChunk(Chunk chunk) {
        World world = chunk.getWorld();
        String worldName = world.getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        // One representative (surface) biome per chunk column for v1 map colouring - not the
        // full 3D biome palette. Good enough for a coloured tile map; revisit if per-height
        // accuracy ever matters (e.g. a player standing in a cave under a different biome).
        // The same highest-block sample also gives the terrain layer: its Y (relief) and its
        // block type (the colour a terrain map draws), stored as surface_y/surface_block.
        Block highest = world.getHighestBlockAt(cx * 16 + 8, cz * 16 + 8);
        Biome biome = highest.getBiome();
        int surfaceY = highest.getY();
        String surfaceBlockKey = highest.getType().getKey().toString();
        long inhabitedTime = chunk.getInhabitedTime();

        // getStructures() returns every structure touching this chunk, not just the ones that
        // "start" here - a village can span dozens of chunks. Dedup on (world, type, min corner)
        // via INSERT OR IGNORE in Storage rather than trying to compute the true start chunk.
        // Only Bukkit API calls happen here (main thread); the actual DB writes are handed off
        // below so disk IO never blocks the server tick.
        //
        // Uses the plain (deprecated) getKey(), not getKeyOrThrow(): confirmed live on this
        // server that getKeyOrThrow() throws NoSuchMethodError against the real runtime Biome
        // implementation even though it resolves fine at compile time against spigot-api.jar -
        // a real ABI gap between the API jar and this server's actual build. getKey() is the
        // one already proven working here (see BiomeReminderListener in biome-photo-reminder).
        Collection<GeneratedStructure> structures = chunk.getStructures();
        List<int[]> boxes = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (GeneratedStructure gs : structures) {
            try {
                BoundingBox bb = gs.getBoundingBox();
                // Real find (2026-09-18): some structure types (e.g. trek:monster_room/*) report
                // a degenerate zero-width, zero-depth bounding box on nearly every chunk - not a
                // real placed structure with a footprint, more like an internal per-chunk marker
                // the datapack registers. That is not "a structure you can go find," so skip it
                // rather than let it swamp real results (confirmed via real data: every OTHER
                // structure type this server has ever produced has a genuine non-zero footprint).
                if (bb.getWidthX() == 0 && bb.getWidthZ() == 0) {
                    continue;
                }
                types.add(gs.getStructure().getKey().toString());
                boxes.add(new int[] {
                        (int) Math.floor(bb.getMinX()), (int) Math.floor(bb.getMinY()), (int) Math.floor(bb.getMinZ()),
                        (int) Math.ceil(bb.getMaxX()), (int) Math.ceil(bb.getMaxY()), (int) Math.ceil(bb.getMaxZ())
                });
            } catch (Exception e) {
                // One odd structure (e.g. an unregistered/modded one) should never cost the
                // rest of this chunk's biome/other-structure data.
                plugin.getLogger().warning("[GroundTruth] Skipped one structure in chunk " + cx + "," + cz
                        + ": " + e);
            }
        }

        String biomeKey = biome.getKey().toString();
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        scheduler.runTaskAsynchronously(plugin, () -> {
            storage.upsertChunk(worldName, cx, cz, biomeKey, inhabitedTime, surfaceBlockKey, surfaceY);
            for (int i = 0; i < boxes.size(); i++) {
                int[] b = boxes.get(i);
                storage.insertStructureIfNew(worldName, types.get(i), b[0], b[1], b[2], b[3], b[4], b[5]);
            }
        });
    }
}
