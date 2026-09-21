package club.footlickers.groundtruth.dumper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * GroundTruth offline dumper.
 *
 * <p><b>What it is / why it exists:</b> GroundTruth's whole pitch is that it records what a world
 * <i>actually generated</i>, not what a seed predicts - so modded worldgen and post-update changes
 * never break the map. The cheapest, safest way to (re)build that index is to read the world's
 * Anvil region files directly, off the live server, paying only CPU/IO and zero server ticks. This
 * tool does exactly that.
 *
 * <p><b>Loader-agnostic:</b> region files are the same format on Paper, Spigot, Fabric, NeoForge and
 * vanilla, so this one tool works everywhere - the server plugin is only needed for live incremental
 * indexing of newly-generated chunks, player positions, and the WorldEdit/permission hooks.
 *
 * <p><b>What it extracts per chunk</b> (all from chunk NBT, no server API):
 * <ul>
 *   <li>biome - the surface-column biome (from the section biome palette).</li>
 *   <li>surface block - the block at the surface (this is the colour a terrain map draws).</li>
 *   <li>surface Y - from the {@code Heightmaps.WORLD_SURFACE} heightmap (relief shading).</li>
 *   <li>inhabited time - carried over for completeness.</li>
 *   <li>structures - from {@code structures.starts}; each start's piece bounding boxes are unioned
 *       (recursing nested jigsaw children) to give the structure's real footprint.</li>
 * </ul>
 *
 * <p><b>Anvil/NBT notes for future maintainers:</b> a region file is a 4 KiB header of 1024
 * (offset, sectorCount) entries, one per 32x32 chunk column, then chunk records at
 * {@code offset*4096} consisting of a 4-byte big-endian length, a 1-byte compression id
 * (1=gzip, 2=zlib, 3=none) and the payload. Heightmaps store 9-bit values packed into longs,
 * <i>relative to the world's min build Y</i> (so the real Y is {@code minY + value - 1}). Block
 * and biome palettes pack indices into longs with <b>non-spanning</b> bit packing (a value never
 * crosses a long boundary) - see {@link Nbt#unpack}. Biome cells are 4x4x4 per section, indexed
 * {@code (y<<4)|(z<<2)|x}.
 *
 * <p><b>Usage:</b>
 * <pre>
 *   java -jar GroundTruthDumper.jar --db &lt;groundtruth.db&gt; --world &lt;name&gt; --regions &lt;region-dir&gt;
 *        [--min-y -64] [--skip-structures] [--limit N] [--dry]
 * </pre>
 * {@code --dry} prints a few sample chunks instead of writing (handy for a quick sanity check).
 * {@code --skip-structures} leaves the existing structure rows alone (useful when the plugin already
 * indexed them and you only want to add the new surface data without risking duplicate boxes).
 */
public final class Dumper {

    public static void main(String[] args) throws Exception {
        String db = null, world = null, regions = null;
        int minY = -64, limit = Integer.MAX_VALUE;
        int cx0 = Integer.MIN_VALUE, cz0 = Integer.MIN_VALUE, cx1 = Integer.MAX_VALUE, cz1 = Integer.MAX_VALUE;
        boolean skipStructures = false, dry = false, detail = false, voxels = false, wantNbt = false;
        boolean onlyVisited = false;
        int visitedRadius = 0;
        int[] voxelLods = null;
        String colorsPath = null, biomesPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--db": db = args[++i]; break;
                case "--world": world = args[++i]; break;
                case "--regions": regions = args[++i]; break;
                case "--min-y": minY = Integer.parseInt(args[++i]); break;
                case "--limit": limit = Integer.parseInt(args[++i]); break;
                case "--cx0": cx0 = Integer.parseInt(args[++i]); break;
                case "--cz0": cz0 = Integer.parseInt(args[++i]); break;
                case "--cx1": cx1 = Integer.parseInt(args[++i]); break;
                case "--cz1": cz1 = Integer.parseInt(args[++i]); break;
                case "--skip-structures": skipStructures = true; break;
                case "--detail": detail = true; break;
                case "--voxels": voxels = true; break;
                case "--voxel-lods": {
                    String[] parts = args[++i].split(",");
                    voxelLods = new int[parts.length];
                    for (int k = 0; k < parts.length; k++) voxelLods[k] = Integer.parseInt(parts[k].trim());
                    break;
                }
                case "--nbt": wantNbt = true; break;
                case "--only-visited": onlyVisited = true; break;
                case "--visited-radius": visitedRadius = Integer.parseInt(args[++i]); break;
                case "--colors": colorsPath = args[++i]; break;
                case "--biome-tints": biomesPath = args[++i]; break;
                case "--dry": dry = true; break;
                default: System.out.println("unknown arg " + args[i]); return;
            }
        }
        if (voxelLods != null) System.out.println("voxel LOD levels: " + java.util.Arrays.toString(voxelLods));
        if (world == null || regions == null || (!dry && db == null)) {
            System.out.println("usage: GroundTruthDumper --regions <dir> --world <name> --db <db> [--min-y -64] [--skip-structures] [--limit N] [--dry]");
            return;
        }
        if (dry && limit == Integer.MAX_VALUE) limit = 8; // dry is a quick sample, not a full pass
        if (colorsPath != null) Renderer.loadColors(colorsPath);
        if (biomesPath != null) loadBiomeTints(biomesPath);
        File dir = new File(regions);
        File[] files = dir.listFiles((d, n) -> n.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        if (files == null) { System.out.println("no region dir: " + regions); return; }
        System.out.println("regions: " + files.length + " files in " + regions + " -> world " + world);

        Db out = dry ? null : new Db(db);
        // "only show what we've seen": skip chunks outside the visited+radius scope entirely, so the
        // voxel backfill matches the renderer's visited filter (and stays small).
        java.util.Set<Long> visitedScope = (onlyVisited && db != null)
                ? loadVisitedScope(db, world, visitedRadius) : null;
        byte[] rgbBuf = new byte[16 * 16 * 3];
        byte[] hBuf = new byte[16 * 16 * 2];
        byte[] gBuf = new byte[16 * 16 * 2];
        long chunks = 0, indexed = 0, structs = 0, failed = 0, lodRows = 0, t0 = System.currentTimeMillis();
        try {
            for (File f : files) {
                if (chunks >= limit) break;
                if (cx0 != Integer.MIN_VALUE) { // bbox: skip region files that don't intersect
                    int rx = regionCoord(f, 1), rz = regionCoord(f, 2);
                    if (rx * 32 + 31 < cx0 || rx * 32 > cx1 || rz * 32 + 31 < cz0 || rz * 32 > cz1) continue;
                }
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    if (raf.length() < 4096) continue;
                    byte[] header = new byte[4096];
                    raf.readFully(header);
                    for (int i = 0; i < 1024 && chunks < limit; i++) {
                        int off = ((header[i * 4] & 0xFF) << 16) | ((header[i * 4 + 1] & 0xFF) << 8) | (header[i * 4 + 2] & 0xFF);
                        int sc = header[i * 4 + 3] & 0xFF;
                        if (off == 0 || sc == 0) continue;
                        raf.seek(off * 4096L);
                        int len = readInt(raf);
                        int comp = raf.readUnsignedByte();
                        if (len <= 1) continue;
                        int cx = regionCoord(f, 1) * 32 + (i % 32);
                        int cz = regionCoord(f, 2) * 32 + (i / 32);
                        if (cx < cx0 || cx > cx1 || cz < cz0 || cz > cz1) continue; // outside requested bbox
                        if (visitedScope != null
                                && !visitedScope.contains(((long) cx << 32) ^ (cz & 0xffffffffL))) continue;
                        byte[] raw = new byte[len - 1];
                        raf.readFully(raw);
                        Map<String, Object> nbt;
                        try {
                            byte[] data = decompress(raw, comp);
                            if (data == null) { failed++; continue; }
                            nbt = Nbt.parse(new ByteArrayInputStream(data));
                        } catch (Exception e) {
                            failed++; continue;
                        }
                        ChunkData cd = extract(nbt, minY);
        Grid grid = new Grid();
                        chunks++;
                        if (dry) {
                            if (chunks <= 8) System.out.printf("  %d,%d biome=%s surface=%s y=%d structs=%d be=%d ent=%d keys=%s%n",
                                    cx, cz, cd.biome, cd.surfaceBlock, cd.surfaceY, cd.structures.size(),
                                    nbtList(nbt, "block_entities", "TileEntities").size(),
                                    nbtList(nbt, "entities", "Entities").size(), nbt.keySet());
                        } else {
                            // Skip chunks that aren't fully generated (no biome/heightmap data). This
                            // matches the plugin's isChunkGenerated() filtering so the index count
                            // stays consistent and empty region-header slots don't pollute the map.
                            if (cd.biome != null) {
                                if (detail) {
                                    // the grid build is authoritative for the terrain layers, so
                                    // take the chunk's high point and median ground from it
                                    buildGrid(nbt, minY, rgbBuf, hBuf, gBuf, grid);
                                    out.upsertPixels(world, cx, cz, rgbBuf, hBuf, gBuf);
                                    out.upsertChunk(world, cx, cz, cd.biome, cd.inhabited,
                                            grid.surfaceBlock, grid.surfaceY,
                                            grid.groundBlock, grid.groundY);
                                } else {
                                    out.upsertChunk(world, cx, cz, cd.biome, cd.inhabited,
                                            cd.surfaceBlock, cd.surfaceY, null, null);
                                }
                                if (voxels) {
                                    out.upsertVoxels(world, cx, cz, buildVoxels(nbt, minY));
                                }
                                if (voxelLods != null) {
                                    for (int lod : voxelLods) {
                                        if (lod < 1 || lod > 4) continue; // 2m..16m blocks
                                        out.upsertVoxelsLod(world, cx, cz, lod, buildVoxelsLod(nbt, minY, lod));
                                        lodRows++;
                                    }
                                }
                                if (wantNbt) {
                                    out.clearChunkNbt(world, cx, cz);
                                    for (Map<String, Object> be : nbtList(nbt, "block_entities", "TileEntities")) {
                                        Double bx = dbl(be.get("x")), by = dbl(be.get("y")), bz = dbl(be.get("z"));
                                        if (bx == null || by == null || bz == null) continue;
                                        out.insertChunkNbt(world, "block_entity", bx, by, bz,
                                                (String) be.get("id"), Nbt.toJson(be));
                                    }
                                    for (Map<String, Object> en : nbtList(nbt, "entities", "Entities")) {
                                        double[] p = entityPos(en);
                                        if (p == null) continue;
                                        out.insertChunkNbt(world, "entity", p[0], p[1], p[2],
                                                (String) en.get("id"), Nbt.toJson(en));
                                    }
                                }
                                indexed++;
                            }
                            if (!skipStructures) {
                                for (String[] s : cd.structures) {
                                    out.insertStructure(world, s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]),
                                            Integer.parseInt(s[3]), Integer.parseInt(s[4]), Integer.parseInt(s[5]), Integer.parseInt(s[6]));
                                    structs++;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("  region failed " + f.getName() + ": " + e);
                    failed++;
                }
                if (chunks % 50000 == 0 && chunks > 0) {
                    long ms = System.currentTimeMillis() - t0;
                    System.out.printf("  %,d chunks (%,d/s), %,d structs, %d failed%n",
                            chunks, chunks * 1000L / Math.max(1, ms), structs, failed);
                }
            }
            if (out != null) out.flush();
        } finally {
            if (out != null) out.close();
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.printf("lodRows=%d%n", lodRows);
        System.out.printf("DONE: %,d chunks scanned, %,d indexed, %,d structures, %d failed in %.1fs%n",
                chunks, indexed, structs, failed, ms / 1000.0);
    }

    private static int regionCoord(File f, int group) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca").matcher(f.getName());
        if (m.matches()) return Integer.parseInt(m.group(group));
        return 0;
    }

    private static int readInt(RandomAccessFile raf) throws IOException {
        return (raf.readUnsignedByte() << 24) | (raf.readUnsignedByte() << 16)
             | (raf.readUnsignedByte() << 8) | raf.readUnsignedByte();
    }

    private static byte[] decompress(byte[] raw, int comp) throws IOException {
        if (comp == 1) return readAll(new GZIPInputStream(new ByteArrayInputStream(raw)));
        if (comp == 2) return readAll(new InflaterInputStream(new ByteArrayInputStream(raw)));
        if (comp == 3) return raw;
        return null;
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        try (in) { return in.readAllBytes(); }
    }

    /**
     * Chunk keys where a player has actually been, for {@code --only-visited}. Uses the chunk's own
     * {@code inhabited_time} (world data, covers the world's whole life) rather than our visit log,
     * which only goes back to when logging was added. Null when nothing is recorded (dump everything).
     */
    private static java.util.Set<Long> loadVisitedScope(String db, String world, int radius) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        int inhabited = 0;
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT cx,cz FROM chunks WHERE world=? AND inhabited_time > 0")) {
            ps.setString(1, world);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    inhabited++;
                    out.add(((long) rs.getInt(1) << 32) ^ (rs.getInt(2) & 0xffffffffL));
                }
            }
        } catch (Exception e) {
            System.out.println("visited filter: could not read inhabited chunks (" + e.getMessage()
                    + ") - dumping everything");
            return null;
        }
        if (inhabited == 0) {
            System.out.println("visited filter: no inhabited chunks recorded - dumping everything");
            return null;
        }
        System.out.println("visited filter: " + inhabited + " inhabited chunk(s) in scope");
        return out;
    }

    // --- chunk extraction ---

    /** First present list-valued key (e.g. block_entities / TileEntities), as a list of compounds. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nbtList(Map<String, Object> nbt, String... keys) {
        for (String k : keys) {
            Object v = nbt.get(k);
            if (v instanceof List) return (List<Map<String, Object>>) (List<?>) v;
        }
        return java.util.Collections.emptyList();
    }

    private static Double dbl(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    /** An entity's position: the {@code Pos} list of three doubles, or null. */
    private static double[] entityPos(Map<String, Object> e) {
        Object p = e.get("Pos");
        if (p instanceof List && ((List<?>) p).size() >= 3) {
            List<?> l = (List<?>) p;
            Double a = dbl(l.get(0)), b = dbl(l.get(1)), c = dbl(l.get(2));
            if (a != null && b != null && c != null) return new double[] { a, b, c };
        }
        return null;
    }

    static class ChunkData {
        String biome, surfaceBlock;
        int surfaceY;
        long inhabited;
        List<String[]> structures = new ArrayList<>();
    }

    /** biome id -> {grass, foliage, dry_foliage, water} packed RGB, loaded from biome_tints.json. */
    static Map<String, int[]> BIOME_TINTS = null;

    static void loadBiomeTints(String path) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)),
                    java.nio.charset.StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> root = BiomeTints.Json.parse(text);
            Map<String, int[]> out = new HashMap<>();
            for (Map.Entry<String, Object> e : root.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                out.put(e.getKey(), new int[] { hex(v.get("grass")), hex(v.get("foliage")),
                        hex(v.get("dry_foliage")), hex(v.get("water")) });
            }
            BIOME_TINTS = out;
            System.out.println("biome tints: " + out.size());
        } catch (Exception ex) {
            System.out.println("biome tints load failed: " + ex);
        }
    }

    private static int hex(Object o) {
        String s = String.valueOf(o);
        if (s.startsWith("#")) s = s.substring(1);
        return Integer.parseInt(s, 16) & 0xFFFFFF;
    }

    /** Which biome tint channel a surface block uses (mirrors AtlasBuilder.tintChannel). */
    static String colorChannel(String base) {
        if (base.contains("water") || base.equals("bubble_column") || base.contains("seagrass")
                || base.contains("kelp") || base.equals("lily_pad")) return "water";
        if (base.contains("dry_grass") || base.contains("leaf_litter") || base.contains("dry_foliage"))
            return "dry_foliage";
        if (base.endsWith("_leaves") && !base.contains("spruce") && !base.contains("birch")
                && !base.contains("azalea") && !base.contains("cherry") && !base.contains("pale_oak"))
            return "foliage";
        if (base.contains("vine")) return "foliage";
        if (base.equals("grass_block") || base.equals("grass") || base.equals("short_grass")
                || base.equals("tall_grass") || base.equals("fern") || base.equals("large_fern")
                || base.contains("sugar_cane") || base.contains("potted_fern")) return "grass";
        return "none";
    }

    private static int defaultTint(String ch) {
        switch (ch) {
            case "grass": return 0x91BD59;
            case "foliage":
            case "dry_foliage": return 0x77AB2F;
            case "water": return 0x3F76E4;
            default: return 0;
        }
    }

    /** Re-tint a block's already default-tinted colour by the biome's tint, preserving the texture. */
    static int tintColor(String biome, String block, int color) {
        if (BIOME_TINTS == null) return color;
        String base = block.contains(":") ? block.substring(block.indexOf(':') + 1) : block;
        String ch = colorChannel(base);
        if (ch.equals("none")) return color;
        int[] t = BIOME_TINTS.get(biome);
        if (t == null) return color;
        int biomeCol = ch.equals("grass") ? t[0] : ch.equals("water") ? t[3]
                : ch.equals("dry_foliage") ? t[2] : t[1];
        int def = defaultTint(ch);
        int dr = (def >> 16) & 255, dg = (def >> 8) & 255, db = def & 255;
        int r = Math.min(255, ((color >> 16) & 255) * (((biomeCol >> 16) & 255)) / Math.max(1, dr));
        int g = Math.min(255, ((color >> 8) & 255) * (((biomeCol >> 8) & 255)) / Math.max(1, dg));
        int b = Math.min(255, (color & 255) * ((biomeCol & 255)) / Math.max(1, db));
        return (r << 16) | (g << 8) | b;
    }

    /** Result of the floor scan: the Y, block and biome of the walkable surface. */
    static class Surf {
        int y;
        String block, biome;
    }

    /**
     * Find the surface by scanning sections from the top down for the highest block that is neither
     * air nor bedrock. Bedrock is skipped so ceiling dimensions (nether) return their floor rather
     * than the roof. Air-only sections are skipped in O(1) via their palette, so this stays cheap.
     */
    @SuppressWarnings("unchecked")
    private static Surf floorScan(Map<Integer, Map<String, Object>> sections, int minY, int x, int z) {
        int maxSecY = Integer.MIN_VALUE;
        for (int k : sections.keySet()) maxSecY = Math.max(maxSecY, k);
        int airRun = 0;          // consecutive air blocks seen directly above the current position
        Surf sealedTop = null;   // highest non-air, non-bedrock block (fallback for sealed columns)
        for (int secY = maxSecY; secY >= (minY >> 4) - 1; secY--) {
            Map<String, Object> sec = sections.get(secY);
            if (sec == null || isAirOnly(sec)) { airRun += 16; continue; }
            for (int ly = 15; ly >= 0; ly--) {
                int wy = secY * 16 + ly;
                if (wy < minY) break;
                String b = blockInSection(sec, ly, x, z);
                if (b == null || b.endsWith(":air")) { airRun++; continue; }
                if (b.equals("minecraft:bedrock")) { airRun = 0; continue; } // roof/floor layer
                Surf s = new Surf();
                s.y = wy;
                s.block = b;
                s.biome = biomeInSection(sec, ly, x, z);
                if (sealedTop == null) sealedTop = s;   // remember the highest solid block
                if (airRun >= 1) return s;              // open surface (has sky/air above) - prefer it
                airRun = 0;                             // sealed (rock continues below) - keep looking
            }
        }
        return sealedTop;
    }

    /** True when a section's palette is only air-ish blocks - lets the floor scan skip it cheaply. */
    @SuppressWarnings("unchecked")
    private static boolean isAirOnly(Map<String, Object> sec) {
        Object bsObj = sec.get("block_states");
        if (!(bsObj instanceof Map)) return true;
        List<Object> pal = (List<Object>) ((Map<String, Object>) bsObj).get("palette");
        if (pal == null || pal.isEmpty()) return true;
        for (Object o : pal) {
            String n = (String) ((Map<String, Object>) o).get("Name");
            if (n != null && !n.endsWith(":air") && !n.equals("minecraft:cave_air") && !n.equals("minecraft:void_air")) {
                return false;
            }
        }
        return true;
    }

    /** Per-chunk aggregates of a grid build: the true high point and the median terrain height. */
    static final class Grid {
        int surfaceY = Integer.MIN_VALUE, groundY = Integer.MIN_VALUE;
        String surfaceBlock, groundBlock;
    }

    /** A mass at least this thick reads as terrain; thinner crusts with air under them are floating. */
    private static final int MIN_MASS = 16;
    /** An air run this long beneath a thin mass means it is floating (a sky island), not the ground. */
    private static final int FLOAT_GAP = 8;

    /**
     * True for blocks that are not terrain - leaves, logs, plants, crops and friends. Used so a
     * treetop does not read as the ground. Deliberately conservative: anything not clearly a plant
     * counts as terrain, so unusual blocks still show up as the surface.
     */
    static boolean isVegetation(String b) {
        if (b == null) return false;
        String n = b.startsWith("minecraft:") ? b.substring(10) : b;
        // these have plant-ish names but are ground in their own right
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

    /** Block name at (x,z,wy) using cached unpacked sections - cheaper than re-reading NBT. */
    @SuppressWarnings("unchecked")
    private static String nameAt(Map<Integer, int[]> blocks, Map<Integer, List<Object>> pals,
                                 int x, int z, int wy) {
        int secY = wy >> 4, ly = wy & 15;
        List<Object> pal = pals.get(secY);
        if (pal == null || pal.isEmpty()) return null;
        int[] arr = blocks.get(secY);
        int pi = (arr == null) ? 0 : arr[ly * 256 + (z & 15) * 16 + (x & 15)];
        if (pi < 0 || pi >= pal.size()) return null;
        return (String) ((Map<String, Object>) pal.get(pi)).get("Name");
    }

    /**
     * Build a chunk's block-resolution terrain layers in one top-down section sweep:
     *
     * <ul>
     *   <li>{@code hgtOut} - topmost non-air block per column (trees, plants and builds included).
     *       What the fine 3D tier draws and what a canopy-coloured map wants.</li>
     *   <li>{@code groundOut} - the terrain surface per column: the topmost block that is neither
     *       vegetation nor part of a floating mass, so a treetop or a sky island cannot spike the
     *       relief. This is what coarse zoom levels use.</li>
     *   <li>{@code rgbOut} - the surface block's biome-tinted colour for the 2D detail layer.</li>
     * </ul>
     *
     * Heights are little-endian u16 relative to minY; both arrays are 256 cells, z-major.
     */
    @SuppressWarnings("unchecked")
    static void buildGrid(Map<String, Object> nbt, int minY, byte[] rgbOut, byte[] hgtOut,
                          byte[] groundOut, Grid agg) {
        Map<Integer, Map<String, Object>> sections = new HashMap<>();
        Object secObj = nbt.get("sections");
        if (secObj instanceof List) {
            for (Object o : (List<Object>) secObj) {
                Map<String, Object> s = (Map<String, Object>) o;
                Object y = s.get("Y");
                if (y instanceof Byte) sections.put((int) (Byte) y, s);
            }
        }
        int maxSecY = Integer.MIN_VALUE;
        for (int k : sections.keySet()) maxSecY = Math.max(maxSecY, k);
        if (maxSecY == Integer.MIN_VALUE) {
            agg.surfaceY = agg.groundY = minY;
            return;
        }
        int top = maxSecY * 16 + 16;
        int levels = Math.max(1, top - minY);
        // 0 = air, 1 = vegetation, 2 = terrain, 3 = bedrock (solid, but never a surface itself)
        byte[] types = new byte[levels * 256];
        Map<Integer, int[]> secBlocks = new HashMap<>();
        Map<Integer, List<Object>> secPals = new HashMap<>();

        for (int secY = maxSecY; secY >= (minY >> 4) - 1; secY--) {
            Map<String, Object> sec = sections.get(secY);
            if (sec == null || isAirOnly(sec)) continue;
            List<Object> pal = paletteNames(sec);
            int[] blocks = sectionBlocks(sec, pal.size()); // unpacked once per section
            secBlocks.put(secY, blocks);
            secPals.put(secY, pal);
            for (int ly = 15; ly >= 0; ly--) {
                int wy = secY * 16 + ly;
                if (wy < minY || wy >= top) continue;
                int rowBase = ly * 256;
                int layer = (wy - minY) * 256;
                for (int zi = 0; zi < 16; zi++) {
                    for (int xi = 0; xi < 16; xi++) {
                        int idx = zi * 16 + xi;
                        int pi = blocks == null ? 0 : blocks[rowBase + zi * 16 + xi];
                        String b = (pi >= 0 && pi < pal.size())
                                ? (String) ((Map<String, Object>) pal.get(pi)).get("Name") : null;
                        if (b == null || b.endsWith(":air")) continue;
                        types[layer + idx] = b.equals("minecraft:bedrock") ? (byte) 3
                                : (isVegetation(b) ? (byte) 1 : (byte) 2);
                    }
                }
            }
        }

        int[] sy = new int[256], gy = new int[256];
        String[] sb = new String[256], sbio = new String[256];
        for (int i = 0; i < 256; i++) {
            sy[i] = Integer.MIN_VALUE;
            gy[i] = Integer.MIN_VALUE;
        }

        for (int zi = 0; zi < 16; zi++) {
            for (int xi = 0; xi < 16; xi++) {
                int idx = zi * 16 + xi;
                // surface: the topmost non-air, non-bedrock block (sealed columns keep it as the
                // fallback, matching the old behaviour for roofed dimensions)
                for (int y = top - 1; y >= minY; y--) {
                    byte t = types[(y - minY) * 256 + idx];
                    if (t == 1 || t == 2) {
                        sy[idx] = y;
                        sb[idx] = nameAt(secBlocks, secPals, xi, zi, y);
                        sbio[idx] = biomeInSection(sections.get(y >> 4), y & 15, xi, zi);
                        break;
                    }
                }
                // ground: the topmost terrain block that is not a floating mass. A thin crust with a
                // long air run under it is a sky island, so the search resumes below that gap; real
                // terrain is thick, so it wins immediately and a cave beneath never matters.
                int y2 = sy[idx];
                while (y2 >= minY) {
                    if (types[(y2 - minY) * 256 + idx] != 2) { y2--; continue; }
                    int thick = 0, air = 0, y3 = y2;
                    while (y3 >= minY && thick < MIN_MASS && air < FLOAT_GAP) {
                        byte t3 = types[(y3 - minY) * 256 + idx];
                        if (t3 == 0) air++;
                        else { air = 0; if (t3 != 1) thick++; }
                        y3--;
                    }
                    if (thick >= MIN_MASS || air < FLOAT_GAP) { gy[idx] = y2; break; }
                    y2 = y3; // floating mass - keep looking below the gap
                }
            }
        }

        int surfMax = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            int y = sy[i];
            String b = sb[i];
            int col = (b == null) ? 0x202020 : Renderer.blockColor(b);
            if (b != null && sbio[i] != null) col = tintColor(sbio[i], b, col); // biome-tinted leaves/grass/water
            // TRUE colour - what the block actually looks like, biome tint included and nothing else.
            // Relief shading is a presentation choice, so it is applied when drawing (2D detail layer,
            // 3D lighting) and never baked into the stored data.
            rgbOut[i * 3] = (byte) ((col >> 16) & 255);
            rgbOut[i * 3 + 1] = (byte) ((col >> 8) & 255);
            rgbOut[i * 3 + 2] = (byte) (col & 255);
            int hv = Math.max(0, (y == Integer.MIN_VALUE ? minY : y) - minY);
            hgtOut[i * 2] = (byte) (hv & 255);
            hgtOut[i * 2 + 1] = (byte) ((hv >> 8) & 255);
            int gv = Math.max(0, (gy[i] == Integer.MIN_VALUE ? minY : gy[i]) - minY);
            groundOut[i * 2] = (byte) (gv & 255);
            groundOut[i * 2 + 1] = (byte) ((gv >> 8) & 255);
            if (y != Integer.MIN_VALUE && y > surfMax) { surfMax = y; agg.surfaceBlock = b; }
        }
        agg.surfaceY = (surfMax == Integer.MIN_VALUE) ? minY : surfMax;

        // median terrain height across the chunk (ignoring columns with no terrain at all)
        int[] gys = new int[256];
        int n = 0;
        for (int i = 0; i < 256; i++) if (gy[i] != Integer.MIN_VALUE) gys[n++] = gy[i];
        if (n == 0) {
            agg.groundY = agg.surfaceY;
            agg.groundBlock = agg.surfaceBlock;
        } else {
            java.util.Arrays.sort(gys, 0, n);
            agg.groundY = gys[n / 2];
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < 256; i++) {
                if (gy[i] == Integer.MIN_VALUE) continue;
                int d = Math.abs(gy[i] - agg.groundY);
                if (d < best) {
                    best = d;
                    agg.groundBlock = nameAt(secBlocks, secPals, i & 15, i >> 4, gy[i]);
                }
            }
        }
    }

    /**
     * True for blocks that are thin decorations (cross-shaped plants, rails, torches). At LOD levels a
     * cell is a whole cube of source blocks, and a scaled-up grass tuft or rail reads as a giant
     * plant, so these are only used when a cell has nothing else in it.
     */
    static boolean isThinDecoration(String n) {
        if (n == null) return false;
        String b = n.startsWith("minecraft:") ? n.substring(10) : n;
        if (b.startsWith("potted_")) return true;
        if (b.equals("grass") || b.equals("tall_grass") || b.equals("short_grass") || b.equals("fern")
                || b.equals("large_fern") || b.equals("dead_bush")) return true;
        return b.contains("sapling") || b.contains("flower") || b.contains("tulip") || b.contains("orchid")
                || b.contains("allium") || b.contains("bluet") || b.contains("daisy")
                || b.contains("dandelion") || b.contains("lily_of") || b.equals("mushroom")
                || b.endsWith("_mushroom") || b.contains("torch") || b.contains("rail")
                || b.contains("wheat") || b.contains("carrot") || b.contains("potato")
                || b.contains("beetroot") || b.contains("sugar_cane") || b.contains("sweet_berry")
                || b.contains("kelp") || b.contains("seagrass") || b.contains("vine")
                || b.contains("sprouts") || b.contains("roots") || b.contains("petal")
                || b.contains("coral_fan") || b.contains("spore_blossom");
    }

    /**
     * Decimated voxel data for one LOD level: the chunk's real blocks halved {@code lod} times. Each
     * output cell is a 2^lod cube of source blocks and takes the most common block in that cube
     * (preferring solid blocks over thin decorations), so the result still looks like Minecraft - just
     * with bigger blocks. The renderer scales the mesh by the same factor, so nothing here needs to
     * know about world units.
     *
     * <p>Layout: {@code u8 version=2, u8 lod, u16 paletteLen, names..., then N*N columns (z-major) of
     * u16 runCount followed by runs of i16 y0, u16 len, u16 palIdx}. Y is in LOD-cell units, i.e.
     * worldY / 2^lod, which is exact for every minY this server uses.
     */
    @SuppressWarnings("unchecked")
    static byte[] buildVoxelsLod(Map<String, Object> nbt, int minY, int lod) throws IOException {
        Map<Integer, Map<String, Object>> sections = new HashMap<>();
        Object secObj = nbt.get("sections");
        if (secObj instanceof List) {
            for (Object o : (List<Object>) secObj) {
                Map<String, Object> s = (Map<String, Object>) o;
                Object y = s.get("Y");
                if (y instanceof Byte) sections.put((int) (Byte) y, s);
            }
        }
        int maxSecY = Integer.MIN_VALUE;
        for (int k : sections.keySet()) maxSecY = Math.max(maxSecY, k);
        int top = Math.max(minY + 16, maxSecY * 16 + 16);
        int height = top - minY;

        int[] grid = new int[256 * height]; // index+1 per cell, 0 = air
        LinkedHashMap<String, Integer> pal = new LinkedHashMap<>();
        boolean[] thin = new boolean[4096];
        for (int secY = maxSecY; secY >= (minY >> 4); secY--) {
            Map<String, Object> sec = sections.get(secY);
            if (sec == null || isAirOnly(sec)) continue;
            List<Object> names = paletteNames(sec);
            int[] blocks = sectionBlocks(sec, names.size());
            for (int ly = 0; ly < 16; ly++) {
                int wy = secY * 16 + ly;
                if (wy < minY || wy >= top) continue;
                int rowBase = ly * 256;
                for (int zi = 0; zi < 16; zi++) {
                    for (int xi = 0; xi < 16; xi++) {
                        int pi = blocks == null ? 0 : blocks[rowBase + zi * 16 + xi];
                        if (pi < 0 || pi >= names.size()) continue;
                        Map<String, Object> pe = (Map<String, Object>) names.get(pi);
                        String b = (String) pe.get("Name");
                        if (b == null || b.endsWith(":air") || b.equals("minecraft:cave_air")
                                || b.equals("minecraft:void_air")) continue;
                        b = withProperties(b, pe.get("Properties"));
                        Integer idx = pal.get(b);
                        if (idx == null) { idx = pal.size() + 1; pal.put(b, idx); }
                        if (idx < thin.length) thin[idx] = isThinDecoration(b);
                        grid[(zi * 16 + xi) * height + (wy - minY)] = idx;
                    }
                }
            }
        }

        // Merge: one output cell per f^3 cube of source blocks.
        int lodShift = lod;
        int f = 1 << lodShift;
        int n = 16 >> lodShift;
        int hLod = Math.max(1, (height + f - 1) / f);
        int[] out = new int[n * n * hLod];
        int[] counts = new int[pal.size() + 1];
        for (int zi = 0; zi < n; zi++) {
            for (int xi = 0; xi < n; xi++) {
                for (int yl = 0; yl < hLod; yl++) {
                    int bestSolid = 0, bestSolidN = 0, bestAny = 0, bestAnyN = 0;
                    for (int dz = 0; dz < f; dz++) {
                        for (int dx = 0; dx < f; dx++) {
                            int src = ((zi * f + dz) * 16 + (xi * f + dx)) * height;
                            for (int dy = 0; dy < f; dy++) {
                                int y = yl * f + dy;
                                if (y >= height) break;
                                int v = grid[src + y];
                                if (v == 0) continue;
                                if (v <= bestAny) { /* keep counts below */ }
                                counts[v]++;
                                int c = counts[v];
                                if (c > bestAnyN) { bestAnyN = c; bestAny = v; }
                                if ((v >= thin.length || !thin[v]) && c > bestSolidN) { bestSolidN = c; bestSolid = v; }
                            }
                        }
                    }
                    // solid blocks win; a cell of nothing but decoration falls back to that decoration
                    out[(zi * n + xi) * hLod + yl] = bestSolid != 0 ? bestSolid : bestAny;
                }
            }
        }

        // Drop everything deep underground: at LOD distances only the surface is ever on screen, and
        // keeping the full column is what made the coarse levels big. KEEP cells below the top cell of
        // each column is plenty for cliffs to read as solid.
        final int KEEP = 10;
        for (int zi = 0; zi < n; zi++) {
            for (int xi = 0; xi < n; xi++) {
                int base = (zi * n + xi) * hLod;
                int topCell = -1;
                for (int y = hLod - 1; y >= 0; y--) if (out[base + y] != 0) { topCell = y; break; }
                if (topCell < 0) continue;
                for (int y = 0; y < topCell - KEEP + 1; y++) out[base + y] = 0;
            }
        }

        // Prune the palette to the blocks this level actually uses. A chunk's full palette is ~70
        // names, which would dominate a 2x2 or 8x8 cell grid; the merged level needs a handful.
        int[] remap = new int[pal.size() + 1];
        List<String> usedNames = new ArrayList<>();
        for (int v : out) {
            if (v != 0 && remap[v] == 0) { remap[v] = usedNames.size() + 1; usedNames.add(null); }
        }
        int u = 0;
        for (String name : pal.keySet()) {
            u++;
            if (remap[u] != 0) usedNames.set(remap[u] - 1, name);
        }
        for (int i = 0; i < out.length; i++) if (out[i] != 0) out[i] = remap[out[i]];

        ByteArrayOutputStream bos = new ByteArrayOutputStream(256 + usedNames.size() * 24);
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte(2);
        d.writeByte(lodShift);
        d.writeShort(usedNames.size());
        for (String name : usedNames) {
            byte[] nb = name.getBytes(StandardCharsets.UTF_8);
            d.writeShort(nb.length);
            d.write(nb);
        }
        int minYCell = minY >> lodShift; // exact: minY is a multiple of 16
        for (int zi = 0; zi < n; zi++) {
            for (int xi = 0; xi < n; xi++) {
                int base = (zi * n + xi) * hLod;
                List<int[]> runs = new ArrayList<>();
                int y = 0;
                while (y < hLod) {
                    int v = out[base + y];
                    if (v == 0) { y++; continue; }
                    int s0 = y;
                    while (y < hLod && out[base + y] == v) y++;
                    runs.add(new int[] { minYCell + s0, y - s0, v });
                }
                d.writeShort(runs.size());
                for (int[] r : runs) {
                    d.writeShort(r[0]);
                    d.writeShort(r[1]);
                    d.writeShort(r[2]);
                }
            }
        }
        d.flush();
        return bos.toByteArray();
    }

    /**
     * Extract a chunk's full voxel data for the 3D mesher: a block-name palette plus, for each of the
     * 256 columns, run-length lists of (startY, length, paletteIndex) for non-air blocks. Air is
     * implied. Captures trees, overhangs and builds that a single surface colour cannot.
     *
     * <p>Binary layout (big-endian, zlib-compressed by {@link Db}): {@code u8 version=1},
     * {@code u16 paletteLen}, then each name {@code u16 len + UTF-8}; then 256 columns (z-major), each
     * {@code u16 runCount} followed by runs of {@code i16 y0, u16 len, u16 paletteIndex}.
     */
    @SuppressWarnings("unchecked")
    static byte[] buildVoxels(Map<String, Object> nbt, int minY) throws IOException {
        Map<Integer, Map<String, Object>> sections = new HashMap<>();
        Object secObj = nbt.get("sections");
        if (secObj instanceof List) {
            for (Object o : (List<Object>) secObj) {
                Map<String, Object> s = (Map<String, Object>) o;
                Object y = s.get("Y");
                if (y instanceof Byte) sections.put((int) (Byte) y, s);
            }
        }
        int maxSecY = Integer.MIN_VALUE;
        for (int k : sections.keySet()) maxSecY = Math.max(maxSecY, k);
        int top = Math.max(minY + 16, maxSecY * 16 + 16);
        int height = top - minY;

        int[] grid = new int[256 * height]; // index+1 per cell, 0 = air
        LinkedHashMap<String, Integer> pal = new LinkedHashMap<>();
        for (int secY = maxSecY; secY >= (minY >> 4); secY--) {
            Map<String, Object> sec = sections.get(secY);
            if (sec == null || isAirOnly(sec)) continue;
            List<Object> names = paletteNames(sec);
            int[] blocks = sectionBlocks(sec, names.size());
            for (int ly = 0; ly < 16; ly++) {
                int wy = secY * 16 + ly;
                if (wy < minY || wy >= top) continue;
                int rowBase = ly * 256;
                for (int zi = 0; zi < 16; zi++) {
                    for (int xi = 0; xi < 16; xi++) {
                        int pi = blocks == null ? 0 : blocks[rowBase + zi * 16 + xi];
                        if (pi < 0 || pi >= names.size()) continue;
                        Map<String, Object> pe = (Map<String, Object>) names.get(pi);
                        String b = (String) pe.get("Name");
                        if (b == null || b.endsWith(":air") || b.equals("minecraft:cave_air")
                                || b.equals("minecraft:void_air")) continue;
                        b = withProperties(b, pe.get("Properties"));
                        Integer idx = pal.get(b);
                        if (idx == null) { idx = pal.size() + 1; pal.put(b, idx); }
                        grid[(zi * 16 + xi) * height + (wy - minY)] = idx;
                    }
                }
            }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024 + pal.size() * 24);
        DataOutputStream d = new DataOutputStream(bos);
        d.writeByte(1);
        d.writeShort(pal.size());
        for (String name : pal.keySet()) {
            byte[] nb = name.getBytes(StandardCharsets.UTF_8);
            d.writeShort(nb.length);
            d.write(nb);
        }
        for (int zi = 0; zi < 16; zi++) {
            for (int xi = 0; xi < 16; xi++) {
                int base = (zi * 16 + xi) * height;
                List<int[]> runs = new ArrayList<>();
                int y = 0;
                while (y < height) {
                    int v = grid[base + y];
                    if (v == 0) { y++; continue; }
                    int s0 = y;
                    while (y < height && grid[base + y] == v) y++;
                    // 1-based, matching the LOD blobs, the assembler and the viewer: index i is
                    // palette entry i-1. Writing v-1 here made every lod-0 block resolve to the
                    // wrong palette entry, which wrecked face culling in the 3D view.
                    runs.add(new int[] { s0, y - s0, v });
                }
                d.writeShort(runs.size());
                for (int[] r : runs) {
                    d.writeShort(minY + r[0]);
                    d.writeShort(r[1]);
                    d.writeShort(r[2]);
                }
            }
        }
        d.flush();
        return bos.toByteArray();
    }

    /**
     * Append a block's state properties (sorted) to its id, e.g. {@code minecraft:oak_stairs[facing=east,
     * half=bottom]}. The 3D mesher needs this to orient stairs and place slabs. Property-free blocks
     * are returned unchanged.
     */
    @SuppressWarnings("unchecked")
    private static String withProperties(String name, Object properties) {
        if (!(properties instanceof Map) || ((Map<String, Object>) properties).isEmpty()) return name;
        Map<String, Object> p = (Map<String, Object>) properties;
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
        for (Map.Entry<String, Object> e : p.entrySet()) sorted.put(e.getKey(), String.valueOf(e.getValue()));
        StringBuilder sb = new StringBuilder(name).append('[');
        int i = 0;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> paletteNames(Map<String, Object> sec) {
        Object bsObj = sec.get("block_states");
        if (!(bsObj instanceof Map)) return java.util.Collections.emptyList();
        Object pal = ((Map<String, Object>) bsObj).get("palette");
        return (pal instanceof List) ? (List<Object>) pal : java.util.Collections.emptyList();
    }

    /** Unpack a section's block_states into 4096 palette indices, or null if the palette is size 1. */
    @SuppressWarnings("unchecked")
    private static int[] sectionBlocks(Map<String, Object> sec, int palSize) {
        Object bsObj = sec.get("block_states");
        if (!(bsObj instanceof Map) || palSize <= 1) return null;
        Object data = ((Map<String, Object>) bsObj).get("data");
        if (!(data instanceof long[])) return null;
        int bits = Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(palSize - 1));
        return Nbt.unpack((long[]) data, bits, 4096);
    }

    /** Block name at local position (x,z) and local Y {@code ly} within a section, or null. */
    @SuppressWarnings("unchecked")
    private static String blockInSection(Map<String, Object> sec, int ly, int x, int z) {
        Object bsObj = sec.get("block_states");
        if (!(bsObj instanceof Map)) return null;
        Map<String, Object> bs = (Map<String, Object>) bsObj;
        List<Object> pal = (List<Object>) bs.get("palette");
        if (pal == null || pal.isEmpty()) return null;
        int idx = 0;
        Object data = bs.get("data");
        if (pal.size() > 1 && data instanceof long[]) {
            int bits = Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(pal.size() - 1));
            idx = Nbt.unpack((long[]) data, bits, 4096)[ly * 256 + (z & 15) * 16 + (x & 15)];
        }
        if (idx < 0 || idx >= pal.size()) return null;
        return (String) ((Map<String, Object>) pal.get(idx)).get("Name");
    }

    /** Biome at local position (x,z) and local Y {@code ly} within a section, or null. */
    @SuppressWarnings("unchecked")
    private static String biomeInSection(Map<String, Object> sec, int ly, int x, int z) {
        Object biObj = sec.get("biomes");
        if (!(biObj instanceof Map)) return null;
        Map<String, Object> bi = (Map<String, Object>) biObj;
        List<Object> pal = (List<Object>) bi.get("palette");
        if (pal == null || pal.isEmpty()) return null;
        int idx = 0;
        Object data = bi.get("data");
        if (pal.size() > 1 && data instanceof long[]) {
            int bits = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(pal.size() - 1));
            int gi = ((ly >> 2) << 4) | (((z & 15) >> 2) << 2) | ((x & 15) >> 2);
            idx = Nbt.unpack((long[]) data, bits, 64)[gi];
        }
        if (idx < 0 || idx >= pal.size()) return null;
        return (String) pal.get(idx);
    }

    @SuppressWarnings("unchecked")
    private static ChunkData extract(Map<String, Object> nbt, int minY) {
        ChunkData cd = new ChunkData();
        Object inh = nbt.get("InhabitedTime");
        cd.inhabited = (inh instanceof Long) ? (Long) inh : 0L;

        Map<Integer, Map<String, Object>> sections = new HashMap<>();
        Object secObj = nbt.get("sections");
        if (secObj instanceof List) {
            for (Object o : (List<Object>) secObj) {
                Map<String, Object> s = (Map<String, Object>) o;
                Object y = s.get("Y");
                if (y instanceof Byte) sections.put((int) (Byte) y, s);
            }
        }

        int x = 8, z = 8;
        // Prefer a floor scan: walk sections top-down for the highest non-air, non-bedrock block.
        // This is correct for the nether/ceiling dimensions (where the top block is the bedrock
        // ROOF) as well as the overworld (bedrock sits at the bottom, so nothing changes there).
        Surf s = floorScan(sections, minY, x, z);
        if (s != null) {
            cd.surfaceY = s.y;
            cd.surfaceBlock = s.block;
            cd.biome = s.biome;
        } else {
            // Fallback for odd chunks with no sections: use the surface heightmap directly.
            Object hmObj = nbt.get("Heightmaps");
            if (hmObj instanceof Map) {
                Object wsObj = ((Map<String, Object>) hmObj).get("WORLD_SURFACE");
                if (wsObj instanceof long[]) {
                    int[] hs = Nbt.unpack((long[]) wsObj, 9, 256);
                    cd.surfaceY = minY + hs[x + z * 16] - 1;
                    cd.surfaceBlock = blockAt(sections, cd.surfaceY, x, z);
                    cd.biome = biomeAt(sections, cd.surfaceY, x, z);
                }
            }
        }

        Object stObj = nbt.get("structures");
        if (stObj instanceof Map) {
            Object startsObj = ((Map<String, Object>) stObj).get("starts");
            if (startsObj instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) startsObj).entrySet()) {
                    int[] mn = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
                    int[] mx = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
                    collectBB(e.getValue(), mn, mx);
                    if (mn[0] != Integer.MAX_VALUE) {
                        cd.structures.add(new String[] { e.getKey(),
                                "" + mn[0], "" + mn[1], "" + mn[2], "" + mx[0], "" + mx[1], "" + mx[2] });
                    }
                }
            }
        }
        return cd;
    }

    @SuppressWarnings("unchecked")
    private static void collectBB(Object o, int[] mn, int[] mx) {
        if (o instanceof Map) {
            for (Object v : ((Map<String, Object>) o).values()) collectBB(v, mn, mx);
        } else if (o instanceof List) {
            for (Object v : (List<Object>) o) collectBB(v, mn, mx);
        } else if (o instanceof int[]) {
            int[] b = (int[]) o;
            if (b.length == 6) {
                mn[0] = Math.min(mn[0], b[0]); mn[1] = Math.min(mn[1], b[1]); mn[2] = Math.min(mn[2], b[2]);
                mx[0] = Math.max(mx[0], b[3]); mx[1] = Math.max(mx[1], b[4]); mx[2] = Math.max(mx[2], b[5]);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String blockAt(Map<Integer, Map<String, Object>> sections, int wy, int x, int z) {
        Map<String, Object> sec = sections.get(wy >> 4);
        if (sec == null) return null;
        Object bsObj = sec.get("block_states");
        if (!(bsObj instanceof Map)) return null;
        Map<String, Object> bs = (Map<String, Object>) bsObj;
        List<Object> pal = (List<Object>) bs.get("palette");
        if (pal == null || pal.isEmpty()) return null;
        int idx = 0;
        Object data = bs.get("data");
        if (pal.size() > 1 && data instanceof long[]) {
            int bits = Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(pal.size() - 1));
            idx = Nbt.unpack((long[]) data, bits, 4096)[(wy & 15) * 256 + (z & 15) * 16 + (x & 15)];
        }
        if (idx < 0 || idx >= pal.size()) return null;
        return (String) ((Map<String, Object>) pal.get(idx)).get("Name");
    }

    @SuppressWarnings("unchecked")
    private static String biomeAt(Map<Integer, Map<String, Object>> sections, int wy, int x, int z) {
        Map<String, Object> sec = sections.get(wy >> 4);
        if (sec == null) return null;
        Object biObj = sec.get("biomes");
        if (!(biObj instanceof Map)) return null;
        Map<String, Object> bi = (Map<String, Object>) biObj;
        List<Object> pal = (List<Object>) bi.get("palette");
        if (pal == null || pal.isEmpty()) return null;
        int idx = 0;
        Object data = bi.get("data");
        if (pal.size() > 1 && data instanceof long[]) {
            int bits = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(pal.size() - 1));
            int gi = (((wy & 15) >> 2) << 4) | (((z & 15) >> 2) << 2) | ((x & 15) >> 2);
            idx = Nbt.unpack((long[]) data, bits, 64)[gi];
        }
        if (idx < 0 || idx >= pal.size()) return null;
        return (String) pal.get(idx);
    }
}
