package club.footlickers.groundtruth.dumper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GroundTruth block colour + texture atlas builder.
 *
 * <p>Reads block textures out of a **vanilla Minecraft client jar** (the user's own copy - never
 * bundled or redistributed) and produces, for the block names actually present in our
 * index:
 * <ul>
 *   <li>{@code blockcolors.json} - a real <b>average colour</b> per block, so the map stops using
 *       hand-guessed colours. Used by the dumper/renderer immediately (2D + 3D terrain).</li>
 *   <li>{@code atlas.png} + {@code atlas.json} - a texture atlas and a block-&gt;UV map (top and
 *       side faces) for textured 3D rendering.</li>
 * </ul>
 *
 * <p>This output is derived from Mojang's textures and is generated locally on the server; it must
 * NOT be committed to the project or redistributed (only the code that generates it is ours).
 *
 * <p>Usage: {@code java -cp GroundTruthDumper.jar ...AtlasBuilder --client-jar <client.jar>
 * --db <groundtruth.db> --out <dir>}
 */
public final class AtlasBuilder {

    private static final int TILE = 16;      // vanilla block textures are 16x16
    private static final int COLS = 32;      // atlas grid width in tiles

    public static void main(String[] args) throws Exception {
        String jarPath = null, db = null, out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--client-jar": jarPath = args[++i]; break;
                case "--db": db = args[++i]; break;
                case "--out": out = args[++i]; break;
                default: System.out.println("unknown arg " + args[i]); return;
            }
        }
        if (jarPath == null || db == null || out == null) {
            System.out.println("usage: AtlasBuilder --client-jar <client.jar> --db <db> --out <dir>");
            return;
        }
        new File(out).mkdirs();

        // 1. which block names do we actually need colours/textures for? Surface blocks (for the 2D
        // map) plus every block that appears in a voxel palette (so the 3D mesher can texture it).
        Set<String> blocks = distinctSurfaceBlocks(db);
        blocks.addAll(voxelPaletteBlocks(db));
        System.out.println("distinct surface+voxel blocks: " + blocks.size());

        // 2. load all block textures from the client jar into memory (name -> png bytes), plus each
        // animated texture's .mcmeta so we can rebuild its frame strip for the 3D water animation.
        Map<String, byte[]> textures = new HashMap<>();
        Map<String, byte[]> metas = new HashMap<>();
        Map<String, byte[]> entityTex = new LinkedHashMap<>(); // "entity/chest/normal" -> png bytes
        java.util.Set<String> entWant = new java.util.HashSet<>();
        entWant.add("entity/banner/banner_base");
        entWant.add("entity/chest/ender");
        for (String v : new String[] { "normal", "trapped", "copper", "copper_exposed",
                "copper_weathered", "copper_oxidized" }) {
            entWant.add("entity/chest/" + v);
            entWant.add("entity/chest/" + v + "_left");
            entWant.add("entity/chest/" + v + "_right");
        }
        String texPrefix = "assets/minecraft/textures/block/";
        String entPrefix = "assets/minecraft/textures/";
        try (ZipFile zip = new ZipFile(jarPath)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.startsWith(texPrefix)) {
                    try (InputStream in = zip.getInputStream(e)) {
                        byte[] bytes = in.readAllBytes();
                        if (n.endsWith(".png.mcmeta")) {
                            metas.put(n.substring(texPrefix.length(), n.length() - ".png.mcmeta".length()), bytes);
                        } else if (n.endsWith(".png")) {
                            textures.put(n.substring(texPrefix.length(), n.length() - 4), bytes);
                        }
                    }
                } else if (n.startsWith(entPrefix) && n.endsWith(".png") && e.getSize() > 0) {
                    String key = n.substring(entPrefix.length(), n.length() - 4);
                    if (entWant.contains(key)) {
                        try (InputStream in = zip.getInputStream(e)) { entityTex.put(key, in.readAllBytes()); }
                    }
                }
            }
        }
        System.out.println("block textures in jar: " + textures.size() + ", entity textures: " + entityTex.size());

        // Also register every texture basename as a block candidate (minecraft:deepslate, etc.). This
        // gives us texture resolution for blocks that never appear as a surface block (stone, ores,
        // deepslate...), which the voxel mesher needs, without shipping a hardcoded block list.
        for (String t : textures.keySet()) blocks.add("minecraft:" + t);

        // 3. resolve a top + side texture per block, average its colour, and pack an atlas
        Map<String, int[]> colors = new HashMap<>();
        Map<String, Map<String, Object>> atlas = new HashMap<>();
        List<String> usedTiles = new ArrayList<>();
        for (String full : blocks) {
            String base = full.contains(":") ? full.substring(full.indexOf(':') + 1) : full;
            String topName = firstTexture(textures, base);
            String sideName = topName == null ? null : sideTexture(textures, base, topName);
            String bottomName = topName == null ? null : bottomTexture(textures, base, sideName, topName);
            // grass side is dirt + a tintable overlay; bake the overlay so only the top face is tinted.
            if (base.equals("grass_block") && sideName != null && textures.containsKey("grass_block_side_overlay")) {
                byte[] comp = composeGrassSide(textures.get(sideName), textures.get("grass_block_side_overlay"));
                if (comp != null) { textures.put("grass_block_side_gt", comp); sideName = "grass_block_side_gt"; }
            }
            int[] col = topName != null ? averageColor(textures.get(topName)) : null;
            if (col == null) { colors.put(full, mutedColor(base)); continue; } // no texture: muted, not hash
            String tint = tintChannel(base, col);
            col = applyTint(base, col); // grass/foliage/water textures are greyscale + biome-tinted
            colors.put(full, col);
            Map<String, Object> entry = new HashMap<>();
            entry.put("color", String.format("#%02x%02x%02x", col[0], col[1], col[2]));
            entry.put("top", tileIndex(usedTiles, topName));
            entry.put("side", tileIndex(usedTiles, sideName != null ? sideName : topName));
            entry.put("bottom", tileIndex(usedTiles, bottomName != null ? bottomName : topName));
            entry.put("tint", tint);
            if (base.equals("grass_block")) entry.put("tintSide", "none"); // dirt must not go green
            atlas.put(full, entry);
        }

        // 4. write the atlas PNG
        int rows = Math.max(1, (usedTiles.size() + COLS - 1) / COLS);
        BufferedImage atlasImg = new BufferedImage(COLS * TILE, rows * TILE, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < usedTiles.size(); i++) {
            BufferedImage tile = toRawArgb(firstFrame(ImageIO.read(new ByteArrayInputStream(textures.get(usedTiles.get(i))))));
            if (tile == null) continue;
            int gx = (i % COLS) * TILE, gy = (i / COLS) * TILE;
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    atlasImg.setRGB(gx + x, gy + y, tile.getRGB(x, y));
                }
            }
        }
        // 4b. animated texture strips. Vanilla animates water_still/water_flow as a vertical strip of
        // 16x16 frames; rebuild them into anim.png with one animation per row (frames left-to-right)
        // and describe them in atlas.json, so the 3D water shader can advance the frame over time
        // instead of showing a frozen first frame.
        List<String> animNames = new ArrayList<>();
        for (String n : new String[] { "water_still", "water_flow" }) if (textures.containsKey(n)) animNames.add(n);
        List<Integer> animFrames = new ArrayList<>();
        List<Integer> animTimes = new ArrayList<>();
        int maxFrames = 1;
        for (String n : animNames) {
            BufferedImage src = toRawArgb(ImageIO.read(new ByteArrayInputStream(textures.get(n))));
            // Animated strips are vertical and the frame is square, but frame size is NOT always 16
            // (water_still is 16 wide, water_flow is 32), so the count is height/width.
            int f = (src == null || src.getWidth() <= 0) ? 1 : Math.max(1, src.getHeight() / src.getWidth());
            animFrames.add(f);
            animTimes.add(mcmetaFrametime(metas.get(n)));
            maxFrames = Math.max(maxFrames, f);
        }
        if (!animNames.isEmpty()) {
            BufferedImage animImg = new BufferedImage(maxFrames * TILE, animNames.size() * TILE, BufferedImage.TYPE_INT_ARGB);
            for (int s = 0; s < animNames.size(); s++) {
                BufferedImage src = toRawArgb(ImageIO.read(new ByteArrayInputStream(textures.get(animNames.get(s)))));
                if (src == null) continue;
                int f = animFrames.get(s);
                int fw = src.getWidth(), fh = Math.max(1, src.getHeight() / f);
                for (int frame = 0; frame < f; frame++)
                    for (int y = 0; y < TILE; y++)
                        for (int x = 0; x < TILE; x++)
                            animImg.setRGB(frame * TILE + x, s * TILE + y, src.getRGB(
                                    Math.min(fw - 1, x * fw / TILE), frame * fh + Math.min(fh - 1, y * fh / TILE)));
            }
            ImageIO.write(animImg, "png", new File(out, "anim.png"));
        }

        // 4c. entity textures (chests/banners) are not 16x16 block tiles. Append them below the tile
        // grid in the atlas (so the shared block material can sample them) at native size, and record
        // each texture's region + pixel size.
        Map<String, Map<String, Object>> entities = new java.util.LinkedHashMap<>();
        if (!entityTex.isEmpty()) {
            List<BufferedImage> eimgs = new ArrayList<>();
            for (byte[] b : entityTex.values()) eimgs.add(toRawArgb(ImageIO.read(new ByteArrayInputStream(b))));
            int entH = 0;
            for (BufferedImage im : eimgs) if (im != null) entH += im.getHeight();
            entH = ((entH + TILE - 1) / TILE) * TILE;
            int totalW = COLS * TILE, totalH = rows * TILE;
            BufferedImage big = new BufferedImage(totalW, totalH + entH, BufferedImage.TYPE_INT_ARGB);
            for (int yy = 0; yy < totalH; yy++)
                for (int xx = 0; xx < totalW; xx++) big.setRGB(xx, yy, atlasImg.getRGB(xx, yy));
            int ey = totalH, ei = 0;
            for (String key : entityTex.keySet()) {
                BufferedImage im = eimgs.get(ei++);
                if (im == null) continue;
                for (int yy = 0; yy < im.getHeight(); yy++)
                    for (int xx = 0; xx < im.getWidth(); xx++) big.setRGB(xx, ey + yy, im.getRGB(xx, yy));
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("u0", 0.0);
                m.put("v0", (double) ey / big.getHeight());
                m.put("u1", (double) im.getWidth() / big.getWidth());
                m.put("v1", (double) (ey + im.getHeight()) / big.getHeight());
                m.put("size", im.getWidth());
                entities.put(key, m);
                ey += im.getHeight();
            }
            atlasImg = big;
            rows = big.getHeight() / TILE; // tileRect() maps tiles by rows, so this must track the height
        }
        ImageIO.write(atlasImg, "png", new File(out, "atlas.png"));

        // 5. write the JSON: colours + atlas UVs
        StringBuilder cj = new StringBuilder("{\n");
        int ci = 0;
        for (Map.Entry<String, int[]> e : colors.entrySet()) {
            cj.append("  \"").append(e.getKey()).append("\": \"#")
              .append(String.format("%02x%02x%02x", e.getValue()[0], e.getValue()[1], e.getValue()[2]))
              .append("\"").append(++ci < colors.size() ? "," : "").append("\n");
        }
        cj.append("}\n");
        Files.write(new File(out, "blockcolors.json").toPath(), cj.toString().getBytes(StandardCharsets.UTF_8));

        Map<String, Object> meta = new HashMap<>();
        meta.put("atlas", "atlas.png");
        meta.put("tile", TILE);
        meta.put("cols", COLS);
        meta.put("rows", rows);
        meta.put("blocks", atlas);
        StringBuilder aj = new StringBuilder();
        aj.append("{\n  \"atlas\": \"atlas.png\",\n");
        aj.append("  \"tile\": ").append(TILE).append(",\n");
        aj.append("  \"cols\": ").append(COLS).append(",\n");
        aj.append("  \"rows\": ").append(rows).append(",\n");
        aj.append("  \"blocks\": {\n");
        int ai = 0;
        for (Map.Entry<String, Map<String, Object>> e : atlas.entrySet()) {
            Map<String, Object> v = e.getValue();
            aj.append("    \"").append(e.getKey()).append("\": {\"color\": \"").append(v.get("color"))
              .append("\", \"top\": ").append(v.get("top")).append(", \"side\": ").append(v.get("side"))
              .append(", \"bottom\": ").append(v.get("bottom"))
              .append(", \"tint\": \"").append(v.get("tint")).append("\"")
              .append(v.get("tintSide") != null ? ", \"tintSide\": \"" + v.get("tintSide") + "\"" : "")
              .append("}").append(++ai < atlas.size() ? "," : "").append("\n");
        }
        aj.append("  },\n  \"tiles\": {\n");
        for (int i = 0; i < usedTiles.size(); i++) {
            aj.append("    \"").append(usedTiles.get(i)).append("\": ").append(i)
              .append(i + 1 < usedTiles.size() ? "," : "").append("\n");
        }
        aj.append("  },\n  \"anims\": {\n");
        for (int i = 0; i < animNames.size(); i++) {
            aj.append("    \"").append(animNames.get(i)).append("\": {\"row\": ").append(i)
              .append(", \"frames\": ").append(animFrames.get(i))
              .append(", \"frametime\": ").append(animTimes.get(i)).append("}")
              .append(i + 1 < animNames.size() ? "," : "").append("\n");
        }
        aj.append("  },\n  \"animCols\": ").append(maxFrames)
          .append(",\n  \"animRows\": ").append(animNames.size()).append(",\n  \"entities\": {\n");
        int ei2 = 0;
        for (Map.Entry<String, Map<String, Object>> e : entities.entrySet()) {
            Map<String, Object> v = e.getValue();
            aj.append("    \"").append(e.getKey()).append("\": {\"u0\": ").append(v.get("u0"))
              .append(", \"v0\": ").append(v.get("v0")).append(", \"u1\": ").append(v.get("u1"))
              .append(", \"v1\": ").append(v.get("v1")).append(", \"size\": ").append(v.get("size"))
              .append("}").append(++ei2 < entities.size() ? "," : "").append("\n");
        }
        aj.append("  }\n}\n");
        Files.write(new File(out, "atlas.json").toPath(), aj.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("colours: " + colors.size() + ", atlas tiles: " + usedTiles.size()
                + ", atlas " + (COLS * TILE) + "x" + (rows * TILE)
                + ", anim " + (maxFrames * TILE) + "x" + (animNames.size() * TILE) + " (" + animNames + ")");
    }

    /** Which biome tint channel (if any) a block's textures use; the client tints at render time. */
    private static String tintChannel(String base, int[] avg) {
        if (base.contains("water") || base.equals("bubble_column") || base.contains("seagrass")
                || base.contains("kelp") || base.equals("lily_pad")) return "water";
        if (base.contains("dry_grass") || base.contains("leaf_litter") || base.contains("dry_foliage"))
            return "dry_foliage";
        if (base.endsWith("_leaves")) {
            // In current Minecraft all oak/birch/spruce/... leaf textures are greyscale and tinted;
            // cherry/azalea textures are already coloured, so those must not be tinted.
            // Nearly all vanilla leaves are greyscale + tinted (some, like jungle, carry a faint
            // yellow spot but are still tinted). Only genuinely coloured leaves (cherry ~56, azalea
            // ~70) are left alone.
            int sat = avg == null ? 0 : Math.max(avg[0], Math.max(avg[1], avg[2]))
                    - Math.min(avg[0], Math.min(avg[1], avg[2]));
            return sat <= 28 ? "foliage" : "none";
        }
        if (base.contains("vine")) return "foliage";
        if (base.equals("grass_block") || base.equals("grass") || base.equals("short_grass")
                || base.equals("tall_grass") || base.equals("fern") || base.equals("large_fern")
                || base.contains("sugar_cane") || base.contains("potted_fern")) return "grass";
        return "none";
    }

    /** Collect every block name referenced by a voxel palette in the DB (so the atlas covers them). */
    private static Set<String> voxelPaletteBlocks(String db) {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement("SELECT data FROM chunk_voxels WHERE data IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] d = inflate(rs.getBytes(1));
                if (d == null || d.length < 3) continue;
                int o = 1;
                int plen = ((d[o] & 255) << 8) | (d[o + 1] & 255);
                o += 2;
                for (int i = 0; i < plen && o + 2 <= d.length; i++) {
                    int n = ((d[o] & 255) << 8) | (d[o + 1] & 255);
                    o += 2;
                    if (o + n > d.length) break;
                    String name = new String(d, o, n, StandardCharsets.UTF_8);
                    int brace = name.indexOf('['); // voxel palettes carry states; the atlas is per-block
                    out.add(brace >= 0 ? name.substring(0, brace) : name);
                    o += n;
                }
            }
        } catch (Exception e) {
            System.out.println("  (no chunk_voxels table yet - voxel palette blocks skipped)");
        }
        return out;
    }

    private static byte[] inflate(byte[] in) {
        if (in == null) return null;
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(in);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(in.length * 3 + 64);
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) break;
                bos.write(buf, 0, n);
            }
        } catch (Exception e) {
            return null;
        } finally {
            inf.end();
        }
        return bos.toByteArray();
    }

    /**
     * Normalise a texture to raw sRGB ARGB. Minecraft stores many block textures as <b>greyscale</b>
     * PNGs; Java reads those as *linear* gray and {@code getRGB} then converts to sRGB, brightening
     * every pixel (stone 125 -> 185). Reading the raster samples directly preserves the authored
     * values, so the map/atlas match what the game actually shows.
     */
    private static BufferedImage toRawArgb(BufferedImage src) {
        if (src == null) return null;
        if (src.getColorModel() instanceof java.awt.image.IndexColorModel) return src; // palette is sRGB
        int w = src.getWidth(), h = src.getHeight();
        java.awt.image.Raster r = src.getRaster();
        int bands = r.getNumBands();
        boolean gray = src.getColorModel().getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY || bands <= 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb;
                if (gray && bands == 1) {
                    int v = r.getSample(x, y, 0);
                    argb = (255 << 24) | (v << 16) | (v << 8) | v;
                } else if (gray && bands == 2) {
                    int v = r.getSample(x, y, 0), a = r.getSample(x, y, 1);
                    argb = (a << 24) | (v << 16) | (v << 8) | v;
                } else {
                    int rr = r.getSample(x, y, 0), gg = bands > 1 ? r.getSample(x, y, 1) : rr;
                    int bb = bands > 2 ? r.getSample(x, y, 2) : rr;
                    int a = bands > 3 ? r.getSample(x, y, 3) : 255;
                    argb = (a << 24) | (rr << 16) | (gg << 8) | bb;
                }
                out.setRGB(x, y, argb);
            }
        }
        return out;
    }

    /** Composite the (greyscale, tintable) grass side overlay onto the dirt side using a default tint. */
    private static byte[] composeGrassSide(byte[] sidePng, byte[] overlayPng) {
        try {
            BufferedImage base = toRawArgb(firstFrame(ImageIO.read(new ByteArrayInputStream(sidePng))));
            BufferedImage ov = toRawArgb(firstFrame(ImageIO.read(new ByteArrayInputStream(overlayPng))));
            if (base == null || ov == null) return null;
            int grass = 0x91BD59;
            int gr = (grass >> 16) & 255, gg = (grass >> 8) & 255, gb = grass & 255;
            BufferedImage out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < base.getHeight(); y++) {
                for (int x = 0; x < base.getWidth(); x++) {
                    int b = base.getRGB(x, y), o = ov.getRGB(x, y);
                    int oa = (o >>> 24);
                    if (oa == 0) { out.setRGB(x, y, b); continue; }
                    int tr = (((o >> 16) & 255) * gr) / 255;
                    int tg = (((o >> 8) & 255) * gg) / 255;
                    int tb = ((o & 255) * gb) / 255;
                    int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
                    out.setRGB(x, y, (255 << 24) | (((tr * oa + br * (255 - oa)) / 255) << 16)
                            | (((tg * oa + bg * (255 - oa)) / 255) << 8) | ((tb * oa + bb * (255 - oa)) / 255));
                }
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolve(Map<String, byte[]> tex, String... candidates) {
        for (String c : candidates) if (tex.containsKey(c)) return c;
        return null;
    }

    /** First texture (in priority order) for a block, or null. Leaves are matched by their own name. */
    private static String firstTexture(Map<String, byte[]> tex, String base) {
        for (String cand : textureCandidates(base)) if (tex.containsKey(cand)) return cand;
        // Crops are textured per growth stage (wheat_stage0..7, carrots_stage0..3, ...) with no plain
        // "<crop>" texture, so pick the most-grown stage as the representative look.
        if (base.matches(".*(wheat|carrots|potatoes|beetroots|nether_wart|torchflower|pitcher).*")
                || base.endsWith("_crop") || base.endsWith("_stem")) {
            String best = null;
            int bestStage = -1;
            for (String k : tex.keySet()) {
                if (!k.startsWith(base + "_stage")) continue;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(".*_stage(\\d+)$").matcher(k);
                int st = m.matches() ? Integer.parseInt(m.group(1)) : 0;
                if (st > bestStage) { bestStage = st; best = k; }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static java.util.List<String> textureCandidates(String base) {
        java.util.List<String> c = new ArrayList<>();
        if (base.contains("leaves")) { c.add(base); return c; }
        c.add(base + "_top");
        c.add(base + "_still");
        c.add(base + "_front");
        c.add(base + "_side");
        c.add(base + "_outer");
        c.add(base);
        c.add(base + "_bottom");
        // stairs/slabs/fences/etc. borrow their base material's texture (oak_stairs -> oak_planks,
        // stone_brick_stairs -> stone_bricks, cobblestone_stairs -> cobblestone). Never fall through
        // to a *leaves* texture just because the stem happens to share a wood prefix.
        for (String stem : stems(base)) {
            c.add(stem);            // stone_slab -> stone, cobblestone_stairs -> cobblestone
            c.add(stem + "s");      // stone_brick_stairs -> stone_bricks
            c.add(stem + "_top");
            c.add(stem + "_side");
            c.add(stem + "_planks"); // oak_stairs -> oak_planks
            c.add(stem + "_bricks");
            c.add(stem + "_block");
        }
        return c;
    }

    private static java.util.List<String> stems(String base) {
        java.util.List<String> s = new ArrayList<>();
        for (String sfx : new String[] { "_slab", "_stairs", "_fence_gate", "_fence", "_wall", "_door",
                "_trapdoor", "_button", "_pressure_plate", "_carpet", "_pane", "_bricks", "_brick",
                "_block", "_planks" }) {
            if (base.endsWith(sfx)) s.add(base.substring(0, base.length() - sfx.length()));
        }
        return s;
    }

    private static String sideTexture(Map<String, byte[]> tex, String base, String topName) {
        for (String cand : new String[] { base + "_side", base + "_front", base, base + "_top" }) {
            if (tex.containsKey(cand)) return cand;
        }
        return topName;
    }

    private static String bottomTexture(Map<String, byte[]> tex, String base, String sideName, String topName) {
        if (base.equals("grass_block") && tex.containsKey("dirt")) return "dirt";
        for (String cand : new String[] { base + "_bottom", base + "_top", sideName, topName }) {
            if (cand != null && tex.containsKey(cand)) return cand;
        }
        return sideName != null ? sideName : topName;
    }

    /**
     * Vanilla grass/foliage/water textures are greyscale and tinted at runtime by biome. Since our
     * map colours are per-block (biome is a separate layer), apply a representative default tint.
     */
    private static int[] applyTint(String base, int[] c) {
        int tint = 0;
        if (base.contains("water") || base.equals("bubble_column") || base.contains("seagrass")
                || base.contains("kelp") || base.equals("lily_pad")) tint = 0x3F76E4;
        else if (base.endsWith("_leaves") || base.contains("vine") || base.contains("azalea")
                || base.contains("moss")) tint = 0x77AB2F;
        else if (base.contains("grass") || base.equals("fern") || base.equals("large_fern")
                || base.contains("sugar_cane")) tint = 0x91BD59;
        if (tint == 0) return c;
        return new int[] { c[0] * ((tint >> 16) & 255) / 255,
                           c[1] * ((tint >> 8) & 255) / 255,
                           c[2] * (tint & 255) / 255 };
    }

    /** Desaturated earthy fallback for blocks with no matching texture (never a wild hash colour). */
    private static int[] mutedColor(String base) {
        int h = 0;
        for (int i = 0; i < base.length(); i++) h = h * 31 + base.charAt(i);
        int v = 92 + Math.floorMod(h, 46);                 // 92..137, muted
        return new int[] { Math.min(255, v + (Math.floorMod(h >> 3, 16) - 8)),
                           Math.min(255, v + (Math.floorMod(h >> 7, 16) - 8)),
                           Math.min(255, v + (Math.floorMod(h >> 11, 16) - 8)) };
    }

    /** Index of a texture in the used-tile list, adding it if new. */
    private static int tileIndex(List<String> used, String name) {
        int i = used.indexOf(name);
        if (i >= 0) return i;
        used.add(name);
        return used.size() - 1;
    }

    /** Parse {@code "frametime": N} (game ticks per frame) out of an .mcmeta, defaulting to 1. */
    private static int mcmetaFrametime(byte[] meta) {
        if (meta == null) return 1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"frametime\"\\s*:\\s*(\\d+)")
                .matcher(new String(meta, StandardCharsets.UTF_8));
        return m.find() ? Math.max(1, Integer.parseInt(m.group(1))) : 1;
    }

    /** First 16x16 frame of a texture (animated textures are tall strips). */
    private static BufferedImage firstFrame(BufferedImage img) {
        if (img == null) return null;
        if (img.getWidth() == TILE && img.getHeight() >= TILE) return img.getSubimage(0, 0, TILE, TILE);
        if (img.getWidth() == TILE) return img;
        return img;
    }

    private static int[] averageColor(byte[] png) {
        try {
            BufferedImage img = toRawArgb(firstFrame(ImageIO.read(new ByteArrayInputStream(png))));
            if (img == null) return null;
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24);
                    if (a < 128) continue;
                    r += (argb >> 16) & 255; g += (argb >> 8) & 255; b += argb & 255; n++;
                }
            }
            if (n == 0) return null;
            return new int[] { (int) (r / n), (int) (g / n), (int) (b / n) };
        } catch (IOException e) {
            return null;
        }
    }

    private static Set<String> distinctSurfaceBlocks(String db) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT surface_block FROM chunks WHERE surface_block IS NOT NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }
}
