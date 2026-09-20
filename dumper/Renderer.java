package club.footlickers.groundtruth.dumper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * GroundTruth tile renderer (M1).
 *
 * <p>Reads the GroundTruth SQLite DB and writes a pyramid of PNG map tiles that the web viewer and
 * the future client mod can stream, instead of loading the whole world as one blob.
 *
 * <p><b>Layers:</b> two tile sets are written per world:
 * <ul>
 *   <li>{@code terrain/} - colour per chunk from its surface block, shaded by surface height
 *       (so builds and relief are visible).</li>
 *   <li>{@code biome/} - colour per chunk from its biome, drawn as a toggleable overlay.</li>
 * </ul>
 *
 * <p><b>Pyramid:</b> zoom 0 is one pixel per chunk (tiles of {@value #TILE}x{@value #TILE} chunks).
 * Each higher zoom halves resolution by averaging the four child pixels from the level below, so the
 * expensive DB work only happens once, at zoom 0.
 *
 * <p><b>Output layout:</b> {@code <out>/<world>/<layer>/<z>/<tx>_<ty>.png} plus
 * {@code <out>/<world>/meta.json} describing the extent and zoom range.
 *
 * <p>Usage: {@code java -cp GroundTruthDumper.jar club.footlickers.groundtruth.dumper.Renderer
 * --db <db> --world <name> --out <dir> [--tile 256] [--min-y -64] [--max-zoom 6]}
 */
public final class Renderer {

    static final int TILE = 256;

    /** Real averaged block colours loaded from blockcolors.json (built by AtlasBuilder); override
        the hand-guessed palette so the map uses true texture colours. */
    static final java.util.Map<String, Integer> OVERRIDES = new java.util.HashMap<>();

    static void loadColors(String path) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"([^\"]+)\"\\s*:\\s*\"#([0-9a-fA-F]{6})\"").matcher(s);
            while (m.find()) OVERRIDES.put(m.group(1), Integer.parseInt(m.group(2), 16));
            System.out.println("loaded " + OVERRIDES.size() + " block colours from " + path);
        } catch (Exception e) {
            System.err.println("colour load failed (" + path + "): " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        String db = null, world = null, out = null;
        int tile = TILE, minY = -64, maxZoom = 6;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--db": db = args[++i]; break;
                case "--world": world = args[++i]; break;
                case "--out": out = args[++i]; break;
                case "--tile": tile = Integer.parseInt(args[++i]); break;
                case "--min-y": minY = Integer.parseInt(args[++i]); break;
                case "--max-zoom": maxZoom = Integer.parseInt(args[++i]); break;
                case "--colors": loadColors(args[++i]); break;
                case "--biome-tints": Dumper.loadBiomeTints(args[++i]); break;
                default: System.out.println("unknown arg " + args[i]); return;
            }
        }
        if (db == null || world == null || out == null) {
            System.out.println("usage: Renderer --db <db> --world <name> --out <dir> [--tile 256] [--min-y -64] [--max-zoom 6]");
            return;
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            int[] ext = extent(conn, world);
            if (ext == null) { System.out.println("no chunks for " + world); return; }
            int minCx = ext[0], maxCx = ext[1], minCz = ext[2], maxCz = ext[3];
            System.out.printf("world %s extent cx %d..%d cz %d..%d%n", world, minCx, maxCx, minCz, maxCz);

            Map<Integer, BufferedImage> heightStats = new HashMap<>(); // reserved (unused, kept simple)
            for (String layer : new String[] { "terrain", "biome" }) {
                renderLevel0(conn, world, out, layer, tile, minY, minCx, maxCx, minCz, maxCz);
            }
            // Build the pyramid by downscaling the previous level until it fits in one tile.
            int z = 1;
            for (; z <= maxZoom; z++) {
                int prevW = (maxCx - minCx) / (1 << (z - 1)) + 1;
                int prevH = (maxCz - minCz) / (1 << (z - 1)) + 1;
                if (prevW <= tile && prevH <= tile && z > 1) break; // already tiny
                for (String layer : new String[] { "terrain", "biome" }) {
                    downscale(out, world, layer, z, tile);
                }
            }
            int maxZ = z - 1;
            writeMeta(out, world, minCx, maxCx, minCz, maxCz, tile, maxZ, minY);
            System.out.println("DONE. max zoom " + maxZ);
        }
    }

    private static int[] extent(Connection conn, String world) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT min(cx),max(cx),min(cz),max(cz) FROM chunks WHERE world=?")) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4) };
            }
        }
    }

    private static void renderLevel0(Connection conn, String world, String outDir, String layer,
                                     int tile, int minY, int minCx, int maxCx, int minCz, int maxCz) throws Exception {
        File dir = new File(outDir, world + "/" + layer + "/0");
        dir.mkdirs();
        long t0 = System.currentTimeMillis();
        boolean biomeLayer = layer.equals("biome");
        int tiles = 0;
        for (int tz = 0; ; tz++) {
            int cz0 = minCz + tz * tile;
            if (cz0 > maxCz) break;
            for (int tx = 0; ; tx++) {
                int cx0 = minCx + tx * tile;
                if (cx0 > maxCx) break;
                BufferedImage img = new BufferedImage(tile, tile, BufferedImage.TYPE_INT_ARGB);
                // For the biome layer we fetch a 1-chunk margin too, so we can tell a real biome
                // boundary from a tile edge and draw the boundaries as outlines.
                int m = biomeLayer ? 1 : 0;
                int pw = tile + 2 * m; // padded width for the biome margin
                String[] biomeAt = biomeLayer ? new String[pw * pw] : null;
                String[] bioChunk = biomeLayer ? null : new String[tile * tile]; // biome per chunk (terrain tint)
                String[] blockAt = new String[tile * tile];
                int[] yAt = new int[tile * tile];
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT cx,cz,biome,surface_block,surface_y FROM chunks WHERE world=? AND cx>=? AND cx<? AND cz>=? AND cz<?")) {
                    ps.setString(1, world);
                    ps.setInt(2, cx0 - m); ps.setInt(3, cx0 + tile + m);
                    ps.setInt(4, cz0 - m); ps.setInt(5, cz0 + tile + m);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int px = rs.getInt(1) - cx0, py = rs.getInt(2) - cz0;
                            if (biomeLayer) biomeAt[(py + m) * pw + (px + m)] = rs.getString(3);
                            if (px < 0 || py < 0 || px >= tile || py >= tile) continue;
                            blockAt[py * tile + px] = rs.getString(4);
                            yAt[py * tile + px] = rs.getInt(5);
                            if (bioChunk != null) bioChunk[py * tile + px] = rs.getString(3);
                        }
                    }
                }
                for (int py = 0; py < tile; py++) {
                    for (int px = 0; px < tile; px++) {
                        if (biomeLayer) {
                            String b = biomeAt[(py + 1) * pw + (px + 1)];
                            if (b == null) continue;
                            boolean edge = false;
                            for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                                String n = biomeAt[(py + 1 + d[1]) * pw + (px + 1 + d[0])];
                                if (n != null && !n.equals(b)) { edge = true; break; }
                            }
                            int rgb = biomeColor(b);
                            if (edge) rgb = scale(rgb, 0.45f); // outline shade on boundary chunks
                            img.setRGB(px, py, 0xFF000000 | rgb);
                        } else {
                            if (blockAt[py * tile + px] == null) continue;
                            img.setRGB(px, py, 0xFF000000 | terrainColor(blockAt[py * tile + px],
                                    bioChunk != null ? bioChunk[py * tile + px] : null,
                                    yAt[py * tile + px], minY));
                        }
                    }
                }
                ImageIO.write(img, "png", new File(dir, tx + "_" + tz + ".png"));
                tiles++;
            }
        }
        System.out.printf("  %s/0: %d tiles in %.1fs%n", layer, tiles, (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** Average the 4 child pixels from the level below into each pixel at this level. */
    private static void downscale(String outDir, String world, String layer, int z, int tile) throws Exception {
        File prevDir = new File(outDir, world + "/" + layer + "/" + (z - 1));
        File dir = new File(outDir, world + "/" + layer + "/" + z);
        dir.mkdirs();
        File[] children = prevDir.listFiles((d, n) -> n.endsWith(".png"));
        if (children == null) return;
        Map<String, BufferedImage> cache = new HashMap<>();
        for (File child : children) {
            String[] p = child.getName().replace(".png", "").split("_");
            int ctx = Integer.parseInt(p[0]), cty = Integer.parseInt(p[1]);
            BufferedImage ci = ImageIO.read(child);
            int outX = ctx / 2, outY = cty / 2;
            String key = outX + "_" + outY;
            BufferedImage oi = cache.get(key);
            if (oi == null) {
                oi = new BufferedImage(tile, tile, BufferedImage.TYPE_INT_ARGB);
                cache.put(key, oi);
            }
            int ox = (ctx % 2) * (tile / 2), oy = (cty % 2) * (tile / 2);
            for (int y = 0; y < tile; y += 2) {
                for (int x = 0; x < tile; x += 2) {
                    int c00 = ci.getRGB(x, y), c10 = ci.getRGB(x + 1, y);
                    int c01 = ci.getRGB(x, y + 1), c11 = ci.getRGB(x + 1, y + 1);
                    int avg = avg4(c00, c10, c01, c11);
                    oi.setRGB(ox + x / 2, oy + y / 2, avg);
                }
            }
        }
        for (Map.Entry<String, BufferedImage> e : cache.entrySet()) {
            ImageIO.write(e.getValue(), "png", new File(dir, e.getKey() + ".png"));
        }
    }

    private static int avg4(int a, int b, int c, int d) {
        int aa = ((a >>> 24) + (b >>> 24) + (c >>> 24) + (d >>> 24)) / 4;
        int ra = (((a >> 16) & 255) + ((b >> 16) & 255) + ((c >> 16) & 255) + ((d >> 16) & 255)) / 4;
        int ga = (((a >> 8) & 255) + ((b >> 8) & 255) + ((c >> 8) & 255) + ((d >> 8) & 255)) / 4;
        int ba = ((a & 255) + (b & 255) + (c & 255) + (d & 255)) / 4;
        return (aa << 24) | (ra << 16) | (ga << 8) | ba;
    }

    private static void writeMeta(String outDir, String world, int minCx, int maxCx, int minCz, int maxCz,
                                  int tile, int maxZ, int minY) throws Exception {
        String json = "{\n"
                + "  \"world\": \"" + world + "\",\n"
                + "  \"tile\": " + tile + ",\n"
                + "  \"minCx\": " + minCx + ", \"maxCx\": " + maxCx + ",\n"
                + "  \"minCz\": " + minCz + ", \"maxCz\": " + maxCz + ",\n"
                + "  \"maxZoom\": " + maxZ + ",\n"
                + "  \"minY\": " + minY + ",\n"
                + "  \"layers\": [\"terrain\", \"biome\"]\n"
                + "}\n";
        Files.write(new File(outDir, world + "/meta.json").toPath(), json.getBytes());
    }

    // --- colours ---

    /** Terrain colour: the surface block's real colour, biome-tinted, then shaded by height. */
    static int terrainColor(String block, String biome, int y, int minY) {
        int base = blockColor(block);
        if (biome != null) base = Dumper.tintColor(biome, block, base); // grass/foliage/water per biome
        float t = Math.max(0f, Math.min(1f, (y - minY) / 200f)); // 0 low .. 1 high
        float shade = 0.65f + 0.5f * t;
        return scale(base, shade);
    }

    private static int scale(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    /** A curated colour for common surface blocks; anything else gets a stable hash colour. */
    static int blockColor(String block) {
        if (block == null) return 0x202020;
        Integer ov = OVERRIDES.get(block);
        if (ov != null) return ov;
        String b = block.contains(":") ? block.substring(block.indexOf(':') + 1) : block;
        switch (b) {
            case "grass_block": case "short_grass": case "grass": case "fern": case "large_fern": return 0x6f9b4b;
            case "dirt": case "coarse_dirt": case "rooted_dirt": case "podzol": return 0x8a5a3b;
            case "stone": case "cobblestone": case "deepslate": case "cobbled_deepslate": return 0x8a8a8a;
            case "sand": case "sandstone": case "smooth_sandstone": return 0xd8cd95;
            case "red_sand": case "red_sandstone": return 0xc06a3a;
            case "gravel": return 0x8a8580;
            case "water": case "kelp": case "seagrass": return 0x2b5f9e;
            case "lava": return 0xd45a12;
            case "netherrack": return 0x7a3b3b;
            case "soul_sand": return 0x5a4433;
            case "soul_soil": return 0x4d3a2d;
            case "basalt": case "smooth_basalt": return 0x555157;
            case "blackstone": return 0x2c2731;
            case "end_stone": return 0xd8d8a8;
            case "obsidian": case "crying_obsidian": return 0x1a1226;
            case "snow": case "snow_block": case "powder_snow": return 0xeef2f5;
            case "ice": case "packed_ice": case "blue_ice": return 0xa8c8e8;
            case "terracotta": return 0x98513d;
            case "moss_block": case "moss_carpet": return 0x5a7a3a;
            case "mycelium": return 0x6f6265;
            case "clay": return 0xa0a4b0;
            case "mud": case "muddy_mangrove_roots": return 0x3c3a3a;
        }
        if (b.endsWith("_log") || b.endsWith("_wood") || b.endsWith("_stem") || b.endsWith("_hyphae")) return 0x6a5236;
        if (b.endsWith("_leaves")) return 0x3f6b34;
        if (b.endsWith("_planks")) return 0x9c7c4a;
        if (b.endsWith("_concrete")) return 0x9a9a9a;
        if (b.endsWith("_wool") || b.endsWith("_carpet")) return 0xbdbdbd;
        return hashColor(b);
    }

    static int biomeColor(String biome) {
        if (biome == null) return 0x202020;
        String b = biome.contains(":") ? biome.substring(biome.indexOf(':') + 1) : biome;
        if (b.contains("ocean") || b.contains("river")) return 0x2b5f9e;
        if (b.contains("desert") || b.contains("beach") || b.contains("badlands")) return 0xd8cd95;
        if (b.contains("snow") || b.contains("frozen") || b.contains("ice") || b.contains("grove")) return 0xe2ecef;
        if (b.contains("jungle")) return 0x2f8f3d;
        if (b.contains("forest") || b.contains("taiga") || b.contains("wood")) return 0x3f6b34;
        if (b.contains("swamp") || b.contains("mangrove")) return 0x4d6b53;
        if (b.contains("savanna") || b.contains("plains") || b.contains("meadow")) return 0x8db15a;
        if (b.contains("nether") || b.contains("crimson") || b.contains("warped") || b.contains("soul") || b.contains("basalt")) return 0x7a3b3b;
        return hashColor(b);
    }

    private static int hashColor(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
        int r = 80 + (Math.floorMod(h, 120));
        int g = 80 + (Math.floorMod(h >> 8, 120));
        int b = 80 + (Math.floorMod(h >> 16, 120));
        return (r << 16) | (g << 8) | b;
    }
}
