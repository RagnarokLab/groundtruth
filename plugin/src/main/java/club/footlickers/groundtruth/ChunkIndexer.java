package club.footlickers.groundtruth;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
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
    private DetailBuilder detail;
    private volatile boolean writeDetail;
    private volatile int[] lodLevels;

    /** See the offline dumper's Dumper#isVegetation: mirrored here so live and offline agree. */
    private static final int MIN_MASS = 16;
    private static final int FLOAT_GAP = 8;

    /**
     * @param writeDetail also produce the per-block layers (pixel grid, voxels, LOD levels) for each
     *                    chunk as it is indexed - this is what lets a plugin-only server avoid ever
     *                    needing the offline dumper except for backfilling and maintenance.
     */
    public ChunkIndexer(Plugin plugin, Storage storage, MapColours colours, boolean writeDetail,
                        int[] lodLevels) {
        this.plugin = plugin;
        this.storage = storage;
        this.detail = new DetailBuilder(colours);
        this.writeDetail = writeDetail;
        this.lodLevels = lodLevels;
    }

    /** Re-apply config-derived settings after a reload (no restart of the listener needed). */
    public void applyConfig(MapColours colours, boolean writeDetail, int[] lodLevels) {
        this.detail = new DetailBuilder(colours);
        this.writeDetail = writeDetail;
        this.lodLevels = lodLevels;
    }

    /** Call on the main thread only. */
    public void indexChunk(Chunk chunk) {
        World world = chunk.getWorld();
        String worldName = world.getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        int minY = world.getMinHeight();

        // includeMaxBlockY (for getHighestBlockYAt) and includeBiome (for getBiome) MUST both be
        // true - getting them wrong silently broke live indexing for every chunk load, with the
        // offline dumper quietly covering for it.
        // BOTH flags matter: includeMaxBlockY for getHighestBlockYAt, includeBiome for getBiome.
        ChunkSnapshot snap = chunk.getChunkSnapshot(true, true, false);

        // Two terrain layers, both read straight off the real chunk:
        //   surface - the true highest block in the chunk (trees, plants and builds included).
        //   ground  - the terrain surface, skipping vegetation and floating masses, so a treetop or
        //             a sky island cannot spike the relief. Sampled every other column and taken as a
        //             median: that is plenty for one number per chunk and keeps indexing cheap.
        int maxSurfaceY = Integer.MIN_VALUE, maxX = 0, maxZ = 0;
        int[] gsX = new int[64], gsZ = new int[64], gsY = new int[64];
        int gi = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int hy = snap.getHighestBlockYAt(x, z);
                if (hy > maxSurfaceY) { maxSurfaceY = hy; maxX = x; maxZ = z; }
                if ((x & 1) == 0 && (z & 1) == 0) {
                    int g = groundColumn(snap, x, z, minY);
                    if (g != Integer.MIN_VALUE) { gsX[gi] = x; gsZ[gi] = z; gsY[gi] = g; gi++; }
                }
            }
        }

        Material surfaceMat = maxSurfaceY == Integer.MIN_VALUE
                ? Material.AIR : snap.getBlockType(maxX, maxSurfaceY, maxZ);
        String surfaceBlockKey = surfaceMat.getKey().toString();
        // MUST use the 3-arg getBiome: the 2-arg (x,z) form is the pre-1.18 legacy call, deprecated
        // because "biomes are now 3-dimensional", and it resolves at a fixed low Y - so it reported
        // the biome *underground* (usually a cave biome) instead of the one at the surface. That fed
        // wrong grass/foliage/water tints into the 2D map, the live 3D mesh and every prerendered
        // tile. Sample at the surface block's own Y, which is what the offline dumper does.
        Biome biome = maxSurfaceY == Integer.MIN_VALUE
                ? snap.getBiome(8, world.getSeaLevel(), 8) : snap.getBiome(maxX, maxSurfaceY, maxZ);
        long inhabitedTime = chunk.getInhabitedTime();

        Integer groundY = null;
        String groundBlockKey = null;
        if (gi > 0) {
            int[] vals = Arrays.copyOf(gsY, gi);
            Arrays.sort(vals);
            int median = vals[gi / 2];
            groundY = median;
            for (int i = 0; i < gi; i++) {
                if (gsY[i] == median) {
                    groundBlockKey = snap.getBlockType(gsX[i], median, gsZ[i]).getKey().toString();
                    break;
                }
            }
        }

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
        final Integer fGroundY = groundY;
        final String fGroundBlock = groundBlockKey;
        final int fSurfaceY = maxSurfaceY;
        final int fMinY = minY, fMaxY = world.getMaxHeight() - 1;
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        scheduler.runTaskAsynchronously(plugin, () -> {
            storage.upsertChunk(worldName, cx, cz, biomeKey, inhabitedTime, surfaceBlockKey, fSurfaceY,
                    fGroundBlock, fGroundY);
            for (int i = 0; i < boxes.size(); i++) {
                int[] b = boxes.get(i);
                storage.insertStructureIfNew(worldName, types.get(i), b[0], b[1], b[2], b[3], b[4], b[5]);
            }
            // The same per-block layers the offline dumper writes, so new chunks need no dump. The
            // snapshot was taken on the main thread; reading it here is what ChunkSnapshot is for.
            if (writeDetail) {
                try {
                    DetailBuilder.Built built = detail.build(snap, fMinY, fMaxY, biomeKey, lodLevels);
                    storage.upsertChunkDetail(worldName, cx, cz, built.pixelsRgb, built.pixelsHgt,
                            built.pixelsGroundHgt, built.voxels, built.lods);
                } catch (Exception e) {
                    plugin.getLogger().warning("[GroundTruth] detail build failed for chunk " + cx + "," + cz
                            + ": " + e.getMessage());
                }
            }
        });
    }

    /**
     * The terrain surface for one column: the topmost block that is neither vegetation nor part of a
     * floating mass. Mirrors the offline dumper's per-block ground pass so live data matches.
     */
    private static int groundColumn(ChunkSnapshot snap, int x, int z, int minY) {
        int y = snap.getHighestBlockYAt(x, z);
        while (y > minY) {
            Material m = snap.getBlockType(x, y, z);
            String n = m.getKey().toString();
            if (isTerrain(n)) {
                // a thin mass with a long air run under it is floating (a sky island), not the ground
                int thick = 0, air = 0, yy = y;
                while (yy > minY && thick < MIN_MASS && air < FLOAT_GAP) {
                    String b = snap.getBlockType(x, yy, z).getKey().toString();
                    if (b.equals("minecraft:air") || b.equals("minecraft:cave_air") || b.equals("minecraft:void_air")) {
                        air++;
                    } else {
                        air = 0;
                        if (!isVegetation(b)) thick++;
                    }
                    yy--;
                }
                if (thick >= MIN_MASS || air < FLOAT_GAP) return y;
                y = yy; // floating - keep looking below the gap
                continue;
            }
            y--;
        }
        return Integer.MIN_VALUE;
    }

    /** True for a block that counts as terrain (not air, not vegetation, not just bedrock). */
    private static boolean isTerrain(String n) {
        if (n.endsWith(":air") || n.equals("minecraft:bedrock")) return false;
        return !isVegetation(n);
    }

    /** True for blocks that are not terrain - leaves, logs, plants, crops and friends. */
    static boolean isVegetation(String b) {
        if (b == null) return false;
        String n = b.startsWith("minecraft:") ? b.substring(10) : b;
        if (n.equals("grass_block") || n.equals("snow") || n.equals("snow_block") || n.equals("moss_block")
                || n.equals("moss_carpet") || n.equals("mycelium") || n.equals("crimson_nylium")
                || n.equals("warped_nylium") || n.equals("shroomlight") || n.equals("cactus_flower")) {
            return false;
        }
        if (n.equals("grass") || n.equals("tall_grass") || n.equals("short_grass") || n.equals("fern")
                || n.equals("large_fern") || n.equals("dead_bush") || n.equals("sugar_cane")) {
            return true;
        }
        return n.contains("leaves") || n.contains("_log") || n.contains("_wood") || n.contains("sapling")
                || n.contains("flower") || n.contains("petal") || n.contains("tulip") || n.contains("orchid")
                || n.contains("allium") || n.contains("bluet") || n.contains("daisy") || n.contains("dandelion")
                || n.contains("lily") || n.contains("mushroom") || n.contains("fungus") || n.contains("roots")
                || n.contains("sprouts") || n.contains("bush") || n.contains("vine") || n.contains("kelp")
                || n.contains("seagrass") || n.contains("cactus") || n.contains("bamboo") || n.contains("cane")
                || n.contains("wheat") || n.contains("carrot") || n.contains("potato") || n.contains("beetroot")
                || n.contains("berry") || n.contains("azalea") || n.contains("dripleaf") || n.contains("cocoa")
                || n.contains("stem") || n.contains("torchflower") || n.contains("pitcher")
                || n.contains("spore_blossom") || n.contains("frogspawn");
    }
}
