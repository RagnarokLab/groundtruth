package club.footlickers.groundtruth;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Block colours and biome tints for the map endpoints, read from the JSON files the offline dumper
 * generates (blockcolors.json, biome_tints.json).
 *
 * <p>The tint rule is deliberately a mirror of the dumper's own (Dumper.colorChannel / tintColor):
 * which blocks take a biome tint and which don't has to agree exactly or the web map and the dumped
 * tiles would colour the same block differently. It is ~60 lines; copying those two functions is far
 * better than dragging the whole dumper into this jar. Same precedent as ChunkIndexer mirroring the
 * dumper's isVegetation().
 */
public final class MapColours {

    private final Map<String, int[]> colours = new HashMap<>();
    private final Map<String, Map<String, String>> tints = new HashMap<>();
    private boolean loaded = false;

    synchronized void load(File tilesDir) {
        if (loaded) return;
        loaded = true;
        try {
            Map<String, String> c = flat(Files.readString(new File(tilesDir, "blockcolors.json").toPath(),
                    StandardCharsets.UTF_8));
            for (Map.Entry<String, String> e : c.entrySet()) {
                String h = e.getValue().startsWith("#") ? e.getValue().substring(1) : e.getValue();
                if (h.length() >= 6) {
                    colours.put(e.getKey(), new int[] {
                            Integer.parseInt(h.substring(0, 2), 16),
                            Integer.parseInt(h.substring(2, 4), 16),
                            Integer.parseInt(h.substring(4, 6), 16) });
                }
            }
        } catch (Exception e) {
            // no colour table: fall back to the neutral grey below
        }
        try {
            tints.putAll(nested(Files.readString(new File(tilesDir, "biome_tints.json").toPath(),
                    StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // no tints: colours stay untinted
        }
    }

    /** A curated colour for the block, or a neutral grey when unknown. */
    public int blockColor(String block) {
        int[] c = colours.get(block);
        if (c == null) return 0x787878;
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }

    /** The tint channel a block uses: grass, foliage, dry_foliage, water, or none. */
    static String colorChannel(String name) {
        if (name == null) return "none";
        String b = name.startsWith("minecraft:") ? name.substring(10) : name;
        if (b.contains("water") || b.equals("bubble_column") || b.contains("seagrass") || b.contains("kelp")
                || b.equals("lily_pad")) return "water";
        if (b.contains("dry_grass") || b.contains("leaf_litter") || b.contains("dry_foliage")) return "dry_foliage";
        if (b.endsWith("_leaves") && !b.contains("spruce") && !b.contains("birch") && !b.contains("azalea")
                && !b.contains("cherry") && !b.contains("pale_oak")) return "foliage";
        if (b.contains("vine")) return "foliage";
        if (b.equals("grass_block") || b.equals("grass") || b.equals("short_grass") || b.equals("tall_grass")
                || b.equals("fern") || b.equals("large_fern") || b.contains("sugar_cane")
                || b.contains("potted_fern")) return "grass";
        return "none";
    }

    private static int[] defaultTint(String ch) {
        switch (ch) {
            case "grass": return new int[] { 0x91, 0xBD, 0x59 };
            case "foliage":
            case "dry_foliage": return new int[] { 0x77, 0xAB, 0x2F };
            case "water": return new int[] { 0x3F, 0x76, 0xE4 };
            default: return new int[] { 255, 255, 255 };
        }
    }

    /** Re-tint a block's colour by its biome's tint, leaving untinted blocks alone. */
    public int tintColor(String biome, String block, int color) {
        if (biome == null || block == null) return color;
        String ch = colorChannel(block);
        if (ch.equals("none")) return color;
        Map<String, String> t = tints.get(biome);
        if (t == null) return color;
        String hex = t.get(ch);
        if (hex == null) return color;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() < 6) return color;
        int br = Integer.parseInt(h.substring(0, 2), 16);
        int bg = Integer.parseInt(h.substring(2, 4), 16);
        int bb = Integer.parseInt(h.substring(4, 6), 16);
        int[] def = defaultTint(ch);
        int r = Math.min(255, ((color >> 16) & 255) * br / Math.max(1, def[0]));
        int g = Math.min(255, ((color >> 8) & 255) * bg / Math.max(1, def[1]));
        int b = Math.min(255, (color & 255) * bb / Math.max(1, def[2]));
        return (r << 16) | (g << 8) | b;
    }

    // --- minimal JSON reading (these files are flat "key": "value" maps, plus one nested level) ---

    private static Map<String, String> flat(String json) {
        Map<String, String> out = new HashMap<>();
        int i = 0;
        while ((i = json.indexOf('"', i)) >= 0) {
            int kEnd = json.indexOf('"', i + 1);
            if (kEnd < 0) break;
            String key = json.substring(i + 1, kEnd);
            int colon = json.indexOf(':', kEnd);
            if (colon < 0) break;
            int vStart = json.indexOf('"', colon);
            if (vStart < 0) break;
            int vEnd = json.indexOf('"', vStart + 1);
            if (vEnd < 0) break;
            out.put(key, json.substring(vStart + 1, vEnd));
            i = vEnd + 1;
        }
        return out;
    }

    private static Map<String, Map<String, String>> nested(String json) {
        Map<String, Map<String, String>> out = new HashMap<>();
        int i = 0;
        while ((i = json.indexOf('{', i)) >= 0) {
            int end = json.indexOf('}', i);
            if (end < 0) break;
            String body = json.substring(i, end);
            int kStart = body.indexOf('"');
            if (kStart < 0) { i = end + 1; continue; }
            int kEnd = body.indexOf('"', kStart + 1);
            String key = body.substring(kStart + 1, kEnd);
            out.put(key, flat(body));
            i = end + 1;
        }
        return out;
    }
}
