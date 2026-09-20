package club.footlickers.groundtruth.dumper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GroundTruth biome tint generator.
 *
 * <p>Grass/foliage/water are tinted per biome, so a single leaf texture looks lavender in a lavender
 * valley and red in an autumnal biome. This reads each biome's {@code effects} colours from the
 * vanilla data bundled in a **client jar** plus the server's **datapack zips** (Terralith, Incendium,
 * Trek, ...), falling back to the vanilla temperature/downfall colormaps for biomes that set no
 * explicit colour. Output is a plain {@code biome_tints.json} the web client tints voxel faces with.
 *
 * <p>All inputs are the user's own client jar / their own datapacks; nothing here is redistributed.
 *
 * <p>Usage: {@code java -cp GroundTruthDumper.jar ...BiomeTints --client-jar <jar> --datapacks <dir>
 * --out <biome_tints.json>}
 */
public final class BiomeTints {

    public static void main(String[] args) throws Exception {
        String jar = null, packs = null, out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--client-jar": jar = args[++i]; break;
                case "--datapacks": packs = args[++i]; break;
                case "--out": out = args[++i]; break;
                default: System.out.println("unknown arg " + args[i]); return;
            }
        }
        if (jar == null || out == null) {
            System.out.println("usage: BiomeTints --client-jar <jar> [--datapacks <dir>] --out <json>");
            return;
        }

        BufferedImage foliageCm, grassCm, dryCm;
        try (ZipFile z = new ZipFile(jar)) {
            foliageCm = img(z, "assets/minecraft/textures/colormap/foliage.png");
            grassCm = img(z, "assets/minecraft/textures/colormap/grass.png");
            dryCm = img(z, "assets/minecraft/textures/colormap/dry_foliage.png");
        }

        // biome id -> parsed definition (later sources override earlier: datapacks beat vanilla)
        Map<String, Map<String, Object>> biomes = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(jar)) {
            var e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                collect(biomes, en.getName(), z.getInputStream(en));
            }
        }
        if (packs != null) {
            File[] fs = new File(packs).listFiles();
            if (fs != null) for (File f : fs) {
                if (f.getName().endsWith(".zip")) {
                    try (ZipFile z = new ZipFile(f)) {
                        var e = z.entries();
                        while (e.hasMoreElements()) {
                            ZipEntry en = e.nextElement();
                            collect(biomes, en.getName(), z.getInputStream(en));
                        }
                    } catch (Exception ex) {
                        System.out.println("  skip " + f.getName() + ": " + ex.getMessage());
                    }
                } else if (f.isDirectory()) {
                    for (Path p : Files.walk(f.toPath()).toList()) {
                        String n = p.toString().replace(File.separatorChar, '/');
                        if (n.contains("/worldgen/biome/") && n.endsWith(".json")) {
                            collect(biomes, n, Files.newInputStream(p));
                        }
                    }
                }
            }
        }
        System.out.println("biomes: " + biomes.size());

        Map<String, Map<String, String>> result = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> e : biomes.entrySet()) {
            Map<String, Object> b = e.getValue();
            double temp = num(b.get("temperature"), 0.5);
            double down = num(b.get("downfall"), 0.5);
            @SuppressWarnings("unchecked")
            Map<String, Object> eff = (b.get("effects") instanceof Map)
                    ? (Map<String, Object>) b.get("effects") : Map.of();
            String modifier = str(eff.get("grass_color_modifier"));

            int foliage = or(color(eff.get("foliage_color")), sample(foliageCm, temp, down, 0x77AB2F));
            int grass = or(color(eff.get("grass_color")),
                    "swamp".equals(modifier) ? 0x6A7039 : sample(grassCm, temp, down, 0x91BD59));
            int dry = or(color(eff.get("dry_foliage_color")),
                    dryCm != null ? sample(dryCm, temp, down, foliage) : foliage);
            int water = or(color(eff.get("water_color")), 0x3F76E4);

            Map<String, String> m = new LinkedHashMap<>();
            m.put("grass", hex(grass));
            m.put("foliage", hex(foliage));
            m.put("dry_foliage", hex(dry));
            m.put("water", hex(water));
            result.put(e.getKey(), m);
        }

        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Map<String, String>> e : result.entrySet()) {
            Map<String, String> v = e.getValue();
            sb.append("  \"").append(e.getKey()).append("\": {")
              .append("\"grass\": \"").append(v.get("grass")).append("\", ")
              .append("\"foliage\": \"").append(v.get("foliage")).append("\", ")
              .append("\"dry_foliage\": \"").append(v.get("dry_foliage")).append("\", ")
              .append("\"water\": \"").append(v.get("water")).append("\"}")
              .append(++i < result.size() ? "," : "").append("\n");
        }
        sb.append("}\n");
        Files.write(Path.of(out), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out + " (" + result.size() + " biomes)");
    }

    private static void collect(Map<String, Map<String, Object>> biomes, String name, InputStream in) {
        if (!name.endsWith(".json") || !name.contains("/worldgen/biome/") || name.contains("/tags/")) return;
        int di = name.indexOf("data/");
        int bi = name.indexOf("/worldgen/biome/");
        if (di < 0 || bi < 0) return;
        String ns = name.substring(di + 5, bi);
        String id = name.substring(bi + "/worldgen/biome/".length(), name.length() - 5);
        try (in) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            biomes.put(ns + ":" + id, m);
        } catch (Exception e) {
            // ignore malformed
        }
    }

    private static BufferedImage img(ZipFile z, String path) {
        ZipEntry e = z.getEntry(path);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) {
            return ImageIO.read(new ByteArrayInputStream(in.readAllBytes()));
        } catch (IOException ex) {
            return null;
        }
    }

    /** Vanilla biome colour lookup: index the colormap by temperature/downfall. */
    private static int sample(BufferedImage cm, double temp, double down, int fallback) {
        if (cm == null) return fallback;
        double t = clamp(temp), d = clamp(down) * t;
        int x = (int) ((1 - t) * 255), y = (int) ((1 - d) * 255);
        x = Math.max(0, Math.min(255, x));
        y = Math.max(0, Math.min(255, y));
        return cm.getRGB(x, y) & 0xFFFFFF;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static Integer color(Object v) {
        if (v instanceof Number) return ((Number) v).intValue() & 0xFFFFFF;
        if (v instanceof String s) {
            s = s.trim();
            if (s.startsWith("#")) s = s.substring(1);
            try {
                return Integer.parseInt(s, 16) & 0xFFFFFF;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer or(Integer a, int b) {
        return a != null ? a : b;
    }

    private static double num(Object v, double def) {
        return (v instanceof Number) ? ((Number) v).doubleValue() : def;
    }

    private static String str(Object v) {
        return (v instanceof String) ? (String) v : null;
    }

    private static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    /** Tiny JSON reader (maps/lists/strings/numbers/bools) so this stays dependency-free. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Map<String, Object> parse(String text) {
            Json j = new Json(text);
            j.ws();
            return j.obj();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                expect(':');
                ws();
                m.put(k, val());
                ws();
                char c = s.charAt(i++);
                if (c == '}') break;
                if (c != ',') throw new IllegalStateException("obj at " + i);
            }
            return m;
        }

        private java.util.List<Object> arr() {
            java.util.List<Object> l = new java.util.ArrayList<>();
            expect('[');
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(val());
                ws();
                char c = s.charAt(i++);
                if (c == ']') break;
                if (c != ',') throw new IllegalStateException("arr at " + i);
            }
            return l;
        }

        private Object val() {
            char c = peek();
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't') { i += 4; return Boolean.TRUE; }
            if (c == 'f') { i += 5; return Boolean.FALSE; }
            if (c == 'n') { i += 4; return null; }
            int st = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(st, i);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }

        private String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        private char peek() {
            return s.charAt(i);
        }

        private void expect(char c) {
            ws();
            if (s.charAt(i) != c) throw new IllegalStateException("expected " + c + " at " + i);
            i++;
        }
    }
}
