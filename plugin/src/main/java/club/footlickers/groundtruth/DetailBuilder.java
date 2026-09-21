package club.footlickers.groundtruth;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.zip.Deflater;

/**
 * The plugin's half of the dumper's job: everything the offline dumper writes per chunk must also be
 * written here, from the live chunk, or a server that only ever runs the plugin would grow a map with
 * no per-block detail and no 3D.
 *
 * <p>The dumper stays the tool for backfilling an existing world and for maintenance. This class
 * mirrors its output byte-for-byte so both paths feed the map identically:
 *
 * <ul>
 *   <li>{@code chunk_pixels} - 16x16 surface colour (biome-tinted) + surface and ground heights. The
 *       heights are <b>little-endian u16 relative to minY</b>, matching the dumper's raw byte writes.</li>
 *   <li>{@code chunk_voxels} - palette + 256 per-column runs of non-air blocks (the 3D mesher's input).</li>
 *   <li>{@code chunk_voxels_lod} - the same chunk decimated 2..16x, as N*N cell columns where each
 *       cell is a 2^lod cube (N = 16 >> lod).</li>
 * </ul>
 *
 * <p>Everything here is safe to run off the tick thread: a {@link ChunkSnapshot} is meant to be read
 * asynchronously. One snapshot pass fills a palette grid, and the rest is array work.
 */
final class DetailBuilder {

    private static final int FLOAT_GAP = 8;   // an air run this long under a thin mass means it floats
    private static final int MIN_MASS = 16;   // a mass at least this thick reads as terrain
    private static final int LOD_KEEP = 10;   // cells of depth kept per column at LOD levels
    private static final int SOLID_WEIGHT = 1000; // solid blocks outvote decorations when merging

    /** One chunk's per-block layers, ready to store. */
    static final class Built {
        byte[] pixelsRgb;
        byte[] pixelsHgt;
        byte[] pixelsGroundHgt;
        byte[] voxels;
        final Map<Integer, byte[]> lods = new LinkedHashMap<>();
    }

    private final MapColours colours;

    DetailBuilder(MapColours colours) {
        this.colours = colours;
    }

    Built build(ChunkSnapshot snap, int minY, int maxY, String biome, int[] lodLevels) {
        Built out = new Built();
        int height = Math.max(1, maxY - minY + 1);

        // One pass: palette ids per cell (0 = air) plus the type map the ground scan needs.
        int[] grid = new int[256 * height];
        byte[] types = new byte[256 * height];
        List<String> palette = new ArrayList<>();
        palette.add("");                                  // indices are 1-based, like the dumper's
        Map<String, Integer> palIdx = new HashMap<>();
        int[] surfY = new int[256];
        String[] surfB = new String[256];
        Arrays.fill(surfY, Integer.MIN_VALUE);

        for (int zi = 0; zi < 16; zi++) {
            for (int xi = 0; xi < 16; xi++) {
                int idx = zi * 16 + xi;
                for (int y = maxY; y >= minY; y--) {
                    Material m = snap.getBlockType(xi, y, zi);
                    if (m.isAir()) continue;
                    String name = blockName(snap, xi, y, zi, m);
                    Integer pi = palIdx.get(name);
                    if (pi == null) {
                        palette.add(name);
                        pi = palette.size() - 1;
                        palIdx.put(name, pi);
                    }
                    // column-major: grid[column * height + y] - the layout emit() and the LOD merge use
                    grid[idx * height + (y - minY)] = pi;
                    boolean bedrock = name.equals("minecraft:bedrock");
                    // the type map stays y-major: it is only ever read by the ground scan below
                    types[(y - minY) * 256 + idx] =
                            bedrock ? (byte) 3 : (isVegetation(name) ? (byte) 1 : (byte) 2);
                    if (!bedrock && surfY[idx] == Integer.MIN_VALUE) {
                        surfY[idx] = y;
                        surfB[idx] = name;
                    }
                }
            }
        }

        // Ground per column: the topmost terrain that is not a floating mass (dumper's rule).
        int[] groundY = new int[256];
        for (int i = 0; i < 256; i++) {
            int y2 = surfY[i];
            groundY[i] = Integer.MIN_VALUE;
            while (y2 >= minY) {
                if (types[(y2 - minY) * 256 + i] != 2) { y2--; continue; }
                int thick = 0, air = 0, y3 = y2;
                while (y3 >= minY && thick < MIN_MASS && air < FLOAT_GAP) {
                    byte t3 = types[(y3 - minY) * 256 + i];
                    if (t3 == 0) air++;
                    else { air = 0; if (t3 != 1) thick++; }
                    y3--;
                }
                if (thick >= MIN_MASS || air < FLOAT_GAP) { groundY[i] = y2; break; }
                y2 = y3;
            }
            if (groundY[i] == Integer.MIN_VALUE) groundY[i] = surfY[i];
        }

        // --- chunk_pixels -------------------------------------------------------------------
        ByteArrayOutputStream rgb = new ByteArrayOutputStream(768);
        ByteArrayOutputStream hgt = new ByteArrayOutputStream(512);
        ByteArrayOutputStream ghgt = new ByteArrayOutputStream(512);
        for (int i = 0; i < 256; i++) {
            // colours are keyed by the bare block name; the surface name carries its state
            String block = surfB[i];
            int bracket = block == null ? -1 : block.indexOf('[');
            String bare = bracket < 0 ? block : block.substring(0, bracket);
            int col = colours.blockColor(bare);
            if (bare != null) col = colours.tintColor(biome, bare, col);
            rgb.write((col >> 16) & 0xFF);
            rgb.write((col >> 8) & 0xFF);
            rgb.write(col & 0xFF);
            int sy = surfY[i] == Integer.MIN_VALUE ? minY : surfY[i];
            int gy = groundY[i] == Integer.MIN_VALUE ? sy : groundY[i];
            putU16LE(hgt, Math.max(0, sy - minY));
            putU16LE(ghgt, Math.max(0, gy - minY));
        }
        out.pixelsRgb = rgb.toByteArray();
        out.pixelsHgt = hgt.toByteArray();
        out.pixelsGroundHgt = ghgt.toByteArray();

        // --- chunk_voxels: runs straight out of the grid -------------------------------------
        out.voxels = emit(grid, palette, minY, height, 256, 1, 0);

        // --- LOD levels ----------------------------------------------------------------------
        for (int lod : lodLevels) {
            if (lod < 1 || lod > 4) continue;
            byte[] blob = buildLod(grid, palette, minY, height, lod);
            if (blob != null) out.lods.put(lod, blob);
        }
        return out;
    }

    /**
     * Decimate the grid by 2^lod. Each output cell keeps the dominant block of the cube it covers,
     * preferring solid blocks over decorations, then only the top LOD_KEEP cells of each column are
     * kept - identical rules to the dumper's, so both produce the same bytes.
     */
    private byte[] buildLod(int[] grid, List<String> palette, int minY, int height, int lod) {
        int f = 1 << lod;
        int n = 16 >> lod;
        int hLod = Math.max(1, (height + f - 1) / f);
        int[] outGrid = new int[n * n * hLod];
        int[] counts = new int[palette.size()];

        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                for (int cy = 0; cy < hLod; cy++) {
                    Arrays.fill(counts, 0);
                    for (int dz = 0; dz < f; dz++) {
                        for (int dx = 0; dx < f; dx++) {
                            int col = ((cz * f + dz) * 16) + (cx * f + dx);
                            for (int dy = 0; dy < f; dy++) {
                                int y = cy * f + dy;
                                if (y >= height) break;
                                int pi = grid[col * height + y];
                                if (pi != 0) counts[pi]++;
                            }
                        }
                    }
                    int best = 0, bestScore = 0;
                    for (int pi = 1; pi < counts.length; pi++) {
                        if (counts[pi] == 0) continue;
                        int score = counts[pi] * (isThinDecoration(palette.get(pi)) ? 1 : SOLID_WEIGHT);
                        if (score > bestScore) { bestScore = score; best = pi; }
                    }
                    outGrid[(cz * n + cx) * hLod + cy] = best;
                }
            }
        }

        // keep only the top LOD_KEEP cells under each column's highest cell
        for (int c = 0; c < n * n; c++) {
            int base = c * hLod, topCell = -1;
            for (int y = hLod - 1; y >= 0; y--) if (outGrid[base + y] != 0) { topCell = y; break; }
            if (topCell < 0) continue;
            for (int y = 0; y < topCell - LOD_KEEP + 1; y++) outGrid[base + y] = 0;
        }

        return emit(outGrid, palette, minY >> lod, hLod, n * n, 2, lod);
    }

    /** Write one voxel blob (version 1 = real blocks, version 2 = a LOD level) in the dumper's layout. */
    private byte[] emit(int[] grid, List<String> palette, int minYBase, int height, int columnCount,
                        int version, int lod) {
        // runs per column, collapapsing repeats of the same block
        List<int[]>[] columns = new List[columnCount];
        boolean[] used = new boolean[palette.size()];
        for (int c = 0; c < columnCount; c++) {
            List<int[]> runs = new ArrayList<>();
            int y = 0;
            while (y < height) {
                int pi = grid[c * height + y];
                if (pi == 0) { y++; continue; }
                int start = y;
                while (y < height && grid[c * height + y] == pi) y++;
                runs.add(new int[] { minYBase + start, y - start, pi });
                used[pi] = true;
            }
            columns[c] = runs;
        }

        // prune the palette to what this blob actually uses, then remap
        Map<Integer, Integer> remap = new HashMap<>();
        List<String> kept = new ArrayList<>();
        kept.add("");
        for (int pi = 1; pi < palette.size(); pi++) {
            if (used[pi]) { kept.add(palette.get(pi)); remap.put(pi, kept.size() - 1); }
        }
        if (kept.size() == 1) return null;      // nothing in this chunk (or this LOD level)

        ByteArrayOutputStream out = new ByteArrayOutputStream(512 + kept.size() * 24);
        out.write(version);
        if (version == 2) out.write(lod);
        putU16(out, kept.size() - 1);
        for (int i = 1; i < kept.size(); i++) {
            byte[] nb = kept.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            putU16(out, nb.length);
            out.write(nb, 0, nb.length);
        }
        for (int c = 0; c < columnCount; c++) {
            List<int[]> runs = columns[c];
            putU16(out, runs.size());
            for (int[] r : runs) {
                putI16(out, r[0]);
                putU16(out, r[1]);
                putU16(out, remap.get(r[2]));
            }
        }
        // NOT deflated here: storage compresses on write, exactly like the dumper's Db does.
        // Deflating in both places produced double-zlib blobs the mesher could not read.
        return out.toByteArray();
    }

    /**
     * A block's full name including its state, with the properties sorted - the dumper sorts them,
     * and matching means the offline and live data are interchangeable (stairs, doors, fences and
     * waterlogged blocks all keep their real shape).
     */
    private static String blockName(ChunkSnapshot snap, int x, int y, int z, Material m) {
        String s;
        try {
            s = snap.getBlockData(x, y, z).getAsString();
        } catch (Exception e) {
            return m.getKey().toString();
        }
        int open = s.indexOf('[');
        if (open < 0 || !s.endsWith("]")) return s;
        String base = s.substring(0, open);
        String[] props = s.substring(open + 1, s.length() - 1).split(",");
        Arrays.sort(props);
        return base + "[" + String.join(",", props) + "]";
    }

    // --- helpers --------------------------------------------------------------------------------

    static boolean isVegetation(String b) {
        return ChunkIndexer.isVegetation(b);
    }

    /** Cross-shaped plants, torches and rails: only chosen when a LOD cell has nothing solid. */
    static boolean isThinDecoration(String b) {
        if (b == null) return false;
        String n = b.startsWith("minecraft:") ? b.substring(10) : b;
        if (n.startsWith("potted_")) return true;
        if (n.equals("grass") || n.equals("tall_grass") || n.equals("short_grass") || n.equals("fern")
                || n.equals("large_fern") || n.equals("dead_bush")) return true;
        return n.contains("sapling") || n.contains("flower") || n.contains("tulip") || n.contains("orchid")
                || n.contains("allium") || n.contains("bluet") || n.contains("daisy") || n.contains("dandelion")
                || n.contains("lily_of") || n.endsWith("_mushroom") || n.contains("torch") || n.contains("rail")
                || n.contains("wheat") || n.contains("carrot") || n.contains("potato") || n.contains("beetroot")
                || n.contains("sugar_cane") || n.contains("sweet_berry") || n.contains("kelp")
                || n.contains("seagrass") || n.contains("vine") || n.contains("sprouts")
                || n.contains("roots") || n.contains("petal") || n.contains("spore_blossom");
    }

    static void putU16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    static void putI16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    static void putU16LE(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(6);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3 + 64);
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            int n = d.deflate(buf);
            if (n == 0) break;
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }
}
