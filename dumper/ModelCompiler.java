package club.footlickers.groundtruth.dumper;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * GroundTruth block-model compiler.
 *
 * <p>Rather than hand-coding every block shape, we read the geometry straight out of the user's own
 * vanilla **client jar**: {@code assets/minecraft/models/block/*.json} (elements, per-face uv,
 * rotations, tintindex, cullface) and {@code assets/minecraft/blockstates/*.json} (variants and
 * multipart state rules). Parents are resolved, texture variables followed, and element rotations
 * baked into explicit face corners. The web client then renders each block from its actual state.
 *
 * <p>Output {@code blockmodels.json}: {@code models} (name -&gt; faces) and {@code blocks}
 * (block -&gt; its blockstate rule). Only blocks present in our index are compiled.
 *
 * <p>Derived from Mojang data and generated locally; never committed or redistributed.
 *
 * <p>Usage: {@code java -cp GroundTruthDumper.jar ...ModelCompiler --client-jar <jar> --db <db>
 * --out <dir>}
 */
public final class ModelCompiler {

    private static ZipFile JAR;
    private static final Map<String, Map<String, Object>> RESOLVED = new HashMap<>();
    private static final Map<String, Map<String, Object>> OUT_MODELS = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        String jar = null, db = null, out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--client-jar": jar = args[++i]; break;
                case "--db": db = args[++i]; break;
                case "--out": out = args[++i]; break;
                default: System.out.println("unknown arg " + args[i]); return;
            }
        }
        if (jar == null || db == null || out == null) {
            System.out.println("usage: ModelCompiler --client-jar <jar> --db <db> --out <dir>");
            return;
        }
        JAR = new ZipFile(jar);
        new File(out).mkdirs();

        Set<String> blocks = new LinkedHashSet<>(blocksInIndex(db));
        int fromIndex = blocks.size();
        for (String b : allBlockstates()) blocks.add(b);
        System.out.println("blocks: " + blocks.size() + " (" + fromIndex + " in index, "
                + (blocks.size() - fromIndex) + " more from jar blockstates)");

        Map<String, Object> blocksOut = new LinkedHashMap<>();
        int compiled = 0;
        for (String full : blocks) {
            String ns = full.contains(":") ? full.substring(0, full.indexOf(':')) : "minecraft";
            String base = full.contains(":") ? full.substring(full.indexOf(':') + 1) : full;
            if (!ns.equals("minecraft")) continue; // modded blocks use their own resource pack
            String path = "assets/minecraft/blockstates/" + base + ".json";
            byte[] raw = readJar(path);
            if (compiled < 3) System.out.println("  try " + full + " -> " + path + " -> " + (raw == null ? "MISS" : raw.length + "b"));
            if (raw == null) continue;
            String chestTex = chestTextureFor(base);
            if (chestTex != null) { // chests are block entities with no block model - synthesise one
                boolean ender = base.equals("ender_chest"); // ender chests have FACING but no TYPE
                String[] facings = { "north", "south", "east", "west" };
                int[] rot = { 180, 0, 270, 90 }; // the model's front (lock) is +Z, so rotate +Z onto facing
                Map<String, Object> variants = new LinkedHashMap<>();
                for (int fi = 0; fi < 4; fi++) {
                    if (ender) {
                        chestVariant(variants, base, chestTex, "single", "facing=" + facings[fi], rot[fi]);
                    } else {
                        for (String ty : new String[] { "single", "left", "right" }) {
                            chestVariant(variants, base, chestTex, ty,
                                    "facing=" + facings[fi] + ",type=" + ty, rot[fi]);
                        }
                    }
                }
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("variants", variants);
                blocksOut.put(full, rule);
                compiled++;
                continue;
            }
            try {
                Map<String, Object> bs = parse(raw);
                blocksOut.put(full, normalizeBlockstate(bs));
                compiled++;
            } catch (Exception e) {
                if (compiled < 5) System.out.println("  ERR " + full + ": " + e);
            }
        }
        System.out.println("blockstates compiled: " + compiled + ", models: " + OUT_MODELS.size());

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"models\": {\n");
        int i = 0;
        for (Map.Entry<String, Map<String, Object>> e : OUT_MODELS.entrySet()) {
            appendModel(sb, e.getKey(), e.getValue());
            sb.append(++i < OUT_MODELS.size() ? ",\n" : "\n");
        }
        sb.append("  },\n  \"blocks\": {\n");
        i = 0;
        for (Map.Entry<String, Object> e : blocksOut.entrySet()) {
            sb.append("    ").append(q(e.getKey())).append(": ");
            appendJson(sb, e.getValue());
            sb.append(++i < blocksOut.size() ? ",\n" : "\n");
        }
        sb.append("  }\n}\n");
        Files.write(Path.of(out, "blockmodels.json"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote blockmodels.json (" + (sb.length() / 1024) + " KB)");
    }

    // --- model resolution ---

    /** Resolve a model (following parents), returning {faces:[...], textures:{...}}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(String name) {
        if (RESOLVED.containsKey(name)) return RESOLVED.get(name);
        RESOLVED.put(name, Map.of("faces", new ArrayList<>(), "elements", new ArrayList<>(),
                "textures", new LinkedHashMap<>())); // cycle guard
        byte[] raw = readJar("assets/minecraft/models/" + stripNs(name) + ".json");
        Map<String, Object> m = raw == null ? Map.of() : parse(raw);
        Map<String, Object> parent = m.get("parent") != null ? resolve((String) m.get("parent")) : null;

        Map<String, String> textures = new LinkedHashMap<>();
        if (parent != null) textures.putAll((Map<String, String>) parent.get("textures"));
        if (m.get("textures") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("textures")).entrySet()) {
                textures.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        List<Object> elements = (m.get("elements") instanceof List) ? (List<Object>) m.get("elements")
                : (parent != null ? (List<Object>) parent.get("elements") : new ArrayList<>());

        List<Object> faces = new ArrayList<>();
        for (Object eo : elements) {
            Map<String, Object> el = (Map<String, Object>) eo;
            double[] from = vec(el.get("from")), to = vec(el.get("to"));
            Map<String, Object> rot = (el.get("rotation") instanceof Map) ? (Map<String, Object>) el.get("rotation") : null;
            double[][ ] corners = corners(from, to, rot);
            Map<String, Object> elFaces = (Map<String, Object>) el.get("faces");
            if (elFaces == null) continue;
            for (Map.Entry<String, Object> fe : elFaces.entrySet()) {
                String dir = fe.getKey();
                Map<String, Object> face = (Map<String, Object>) fe.getValue();
                double[] uv = face.get("uv") instanceof List ? vec4((List<?>) face.get("uv")) : defaultUv(dir, from, to);
                String tex = follow(textures, String.valueOf(face.get("texture")));
                // atlas tiles are keyed by basename (grass_block_top), not block/grass_block_top
                String t = tex == null ? null : tex.substring(tex.lastIndexOf('/') + 1);
                Object ti = face.get("tintindex");
                String cf = face.get("cullface") instanceof String ? (String) face.get("cullface") : null;
                Map<String, Object> f = new LinkedHashMap<>();
                double[][] quad = faceCorners(dir, corners);
                List<Object> cs = new ArrayList<>();
                for (double[] c : quad) cs.add(List.of(round(c[0]), round(c[1]), round(c[2])));
                f.put("c", cs);
                f.put("uv", List.of(round(uv[0]), round(uv[1]), round(uv[2]), round(uv[3])));
                if (t != null) f.put("t", t);
                if (ti instanceof Number && ((Number) ti).intValue() >= 0) f.put("ti", ((Number) ti).intValue());
                if (cf != null) f.put("cf", cf);
                faces.add(f);
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("elements", elements);
        res.put("textures", textures);
        res.put("faces", faces);
        RESOLVED.put(name, res);
        return res;
    }

    /** Keep the blockstate rule, but register every referenced model and record its resolved name. */
    @SuppressWarnings("unchecked")
    private static Object normalizeBlockstate(Map<String, Object> bs) {
        if (bs.get("variants") instanceof Map) {
            Map<String, Object> outVars = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) bs.get("variants")).entrySet()) {
                Object v = e.getValue();
                List<Object> list = (v instanceof List) ? (List<Object>) v : List.of(v);
                List<Object> norm = new ArrayList<>();
                for (Object o : list) norm.add(normalizeApply(o));
                outVars.put(e.getKey(), norm.size() == 1 ? norm.get(0) : norm);
            }
            return Map.of("variants", outVars);
        }
        if (bs.get("multipart") instanceof List) {
            List<Object> parts = new ArrayList<>();
            for (Object o : (List<Object>) bs.get("multipart")) {
                Map<String, Object> part = (Map<String, Object>) o;
                Map<String, Object> np = new LinkedHashMap<>();
                if (part.get("when") != null) np.put("when", part.get("when"));
                np.put("apply", normalizeApply(part.get("apply")));
                parts.add(np);
            }
            return Map.of("multipart", parts);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeApply(Object applyObj) {
        if (applyObj instanceof List) { // multipart apply may itself be a list
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) applyObj) out.add(normalizeApply(o));
            return out;
        }
        Map<String, Object> apply = (Map<String, Object>) applyObj;
        Map<String, Object> out = new LinkedHashMap<>();
        String model = (String) apply.get("model");
        if (model != null) {
            resolve(model); // ensure compiled
            Object faces = RESOLVED.get(model).get("faces");
            OUT_MODELS.put(model, Map.of("faces", faces));
            out.put("model", model);
        }
        for (String k : new String[] { "x", "y", "uvlock", "weight" }) {
            if (apply.get(k) != null) out.put(k, apply.get(k));
        }
        return out;
    }

    // --- geometry helpers ---

    private static double[][] corners(double[] from, double[] to, Map<String, Object> rot) {
        double[][] c = new double[8][];
        for (int i = 0; i < 8; i++) {
            double x = ((i & 1) != 0) ? to[0] : from[0];
            double y = ((i & 2) != 0) ? to[1] : from[1];
            double z = ((i & 4) != 0) ? to[2] : from[2];
            c[i] = new double[] { x, y, z };
        }
        if (rot != null) {
            double[] origin = vec(rot.get("origin"));
            String axis = String.valueOf(rot.get("axis"));
            double ang = num(rot.get("angle")) * Math.PI / 180.0;
            for (double[] p : c) rotate(p, origin, axis, ang);
        }
        return c;
    }

    private static void rotate(double[] p, double[] o, String axis, double a) {
        double x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
        double ca = Math.cos(a), sa = Math.sin(a);
        double nx = x, ny = y, nz = z;
        if ("x".equals(axis)) { ny = y * ca - z * sa; nz = y * sa + z * ca; }
        else if ("y".equals(axis)) { nx = x * ca - z * sa; nz = x * sa + z * ca; }
        else { nx = x * ca - y * sa; ny = x * sa + y * ca; }
        p[0] = nx + o[0]; p[1] = ny + o[1]; p[2] = nz + o[2];
    }

    /**
     * Corner index: bit0=x, bit1=y, bit2=z (0 = from, 1 = to). These four corners per face, and their
     * order, are vanilla's {@code net.minecraft.client.renderer.FaceInfo} vertex order (read out of
     * the client jar), and the uv rectangle is indexed per vertex by {@code CuboidFace.UVs}:
     * (minU,minV),(minU,maxV),(maxU,maxV),(maxU,minV). Getting either wrong rotates/mirrors the
     * texture - the side faces here used to be rotated 90 degrees, and the up face too.
     */
    private static double[][] faceCorners(String dir, double[][] c) {
        switch (dir) {
            case "down":  return new double[][] { c[4], c[0], c[1], c[5] };
            case "up":    return new double[][] { c[2], c[6], c[7], c[3] };
            case "north": return new double[][] { c[3], c[1], c[0], c[2] };
            case "south": return new double[][] { c[6], c[4], c[5], c[7] };
            case "west":  return new double[][] { c[2], c[0], c[4], c[6] };
            default:      return new double[][] { c[7], c[5], c[1], c[3] }; // east
        }
    }

    /** Vanilla {@code FaceBakery.defaultFaceUV}, one layout per face (u1,v1,u2,v2). */
    private static double[] defaultUv(String dir, double[] from, double[] to) {
        switch (dir) {
            case "down":  return new double[] { from[0], 16 - to[2], to[0], 16 - from[2] };
            case "up":    return new double[] { from[0], from[2], to[0], to[2] };
            case "north": return new double[] { 16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1] };
            case "south": return new double[] { from[0], 16 - to[1], to[0], 16 - from[1] };
            case "west":  return new double[] { from[2], 16 - to[1], to[2], 16 - from[1] };
            default:      return new double[] { 16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1] }; // east
        }
    }

    private static String follow(Map<String, String> tex, String val) {
        if (val == null) return null;
        for (int i = 0; i < 8 && val.startsWith("#"); i++) {
            val = tex.get(val.substring(1));
            if (val == null) return null;
        }
        return val;
    }

    private static double[] vec(Object o) {
        List<?> l = (List<?>) o;
        return new double[] { num(l.get(0)), num(l.get(1)), num(l.get(2)) };
    }

    private static double[] vec4(Object o) {
        List<?> l = (List<?>) o;
        return new double[] { num(l.get(0)), num(l.get(1)), num(l.get(2)), num(l.get(3)) };
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    private static double round(double v) {
        return Math.round(v * 64.0) / 64.0; // 1/64 precision - plenty for 0..16 models
    }

    private static String stripNs(String s) {
        return s.contains(":") ? s.substring(s.indexOf(':') + 1) : s;
    }

    private static Map<String, Object> parse(byte[] b) {
        return BiomeTints.Json.parse(new String(b, StandardCharsets.UTF_8));
    }

    private static byte[] readJar(String path) {
        try {
            ZipEntry e = JAR.getEntry(path);
            if (e == null) return null;
            try (InputStream in = JAR.getInputStream(e)) { return in.readAllBytes(); }
        } catch (Exception e) {
            return null;
        }
    }

    /** Chest-type block -> its entity texture name (entity/chest/<name>.png), or null. */
    private static String chestTextureFor(String base) {
        switch (base) {
            case "chest": return "normal";
            case "trapped_chest": return "trapped";
            case "ender_chest": return "ender";
            case "copper_chest": return "copper";
            case "exposed_copper_chest": return "copper_exposed";
            case "weathered_copper_chest": return "copper_weathered";
            case "oxidized_copper_chest": return "copper_oxidized";
            default: return null;
        }
    }

    /**
     * A chest model, matching vanilla's {@code ChestModel.createBodyLayer} / {@code createDoubleBody*Layer}
     * (body + lid + lock) with per-face UVs from {@code ModelPart.Cube}'s box layout (read out of the
     * client jar), in the 0..64 space of the entity texture. {@code type} is single/left/right. The
     * model's front (the lock) is +Z, so the blockstate just rotates it onto FACING.
     */
    private static List<Object> chestModel(String texPath, String type) {
        List<Object> faces = new ArrayList<>();
        // {from x,y,z, to x,y,z, texOffs u,v}
        double[][] boxes;
        if (type.equals("right")) {        // east half: body extends to x=16, seam lock at the east edge
            boxes = new double[][] {
                { 1, 0, 1, 16, 10, 15, 0, 19 },
                { 1, 9, 1, 16, 14, 15, 0, 0 },
                { 15, 7, 15, 16, 11, 16, 0, 0 },
            };
        } else if (type.equals("left")) {  // west half
            boxes = new double[][] {
                { 0, 0, 1, 15, 10, 15, 0, 19 },
                { 0, 9, 1, 15, 14, 15, 0, 0 },
                { 0, 7, 15, 1, 11, 16, 0, 0 },
            };
        } else {
            boxes = new double[][] {
                { 1, 0, 1, 15, 10, 15, 0, 19 },
                { 1, 9, 1, 15, 14, 15, 0, 0 },
                { 7, 7, 15, 9, 11, 16, 0, 0 },
            };
        }
        for (double[] b : boxes) {
            double[] from = { b[0], b[1], b[2] }, to = { b[3], b[4], b[5] };
            double w = to[0] - from[0], h = to[1] - from[1], d = to[2] - from[2];
            double ou = b[6], ov = b[7];
            double[][] cs = corners(from, to, null);
            for (String dir : new String[] { "down", "up", "west", "north", "east", "south" }) {
                double[][] quad = faceCorners(dir, cs);
                List<Object> c = new ArrayList<>();
                for (double[] p : quad) c.add(List.of(round(p[0]), round(p[1]), round(p[2])));
                double[] uv = cubeUv(dir, w, h, d, ou, ov);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("c", c);
                f.put("uv", List.of(round(uv[0]), round(uv[1]), round(uv[2]), round(uv[3])));
                f.put("t", texPath);
                faces.add(f);
            }
        }
        return faces;
    }

    /** Register one chest variant (model synthesised once per type) and add its blockstate entry. */
    private static void chestVariant(Map<String, Object> variants, String base, String variant,
                                     String type, String key, int y) {
        boolean single = type.equals("single");
        String modelName = "minecraft:block/gt_chest_" + base + (single ? "" : "_" + type);
        if (!OUT_MODELS.containsKey(modelName)) {
            String tex = "entity/chest/" + variant + (single ? "" : "_" + type);
            OUT_MODELS.put(modelName, Map.of("faces", chestModel(tex, type)));
        }
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("model", modelName);
        if (y != 0) apply.put("y", y);
        variants.put(key, apply);
    }

    /** Per-face UV rectangle (u1,v1,u2,v2) of a box in ModelPart.Cube's texture layout. */
    private static double[] cubeUv(String dir, double w, double h, double d, double ou, double ov) {
        switch (dir) {
            case "down":  return new double[] { ou + d, ov, ou + d + w, ov + d };
            case "up":    return new double[] { ou + d + w, ov, ou + d + 2 * w, ov + d };
            case "west":  return new double[] { ou, ov + d, ou + d, ov + d + h };
            case "north": return new double[] { ou + d, ov + d, ou + d + w, ov + d + h };
            case "east":  return new double[] { ou + d + w, ov + d, ou + 2 * d + w, ov + d + h };
            default:      return new double[] { ou + 2 * d + w, ov + d, ou + 2 * d + 2 * w, ov + d + h }; // south
        }
    }

    /** Every vanilla blockstate in the jar, so blocks that simply aren't in our index yet still render. */
    private static Set<String> allBlockstates() {
        Set<String> out = new LinkedHashSet<>();
        String prefix = "assets/minecraft/blockstates/";
        java.util.Enumeration<? extends ZipEntry> e = JAR.entries();
        while (e.hasMoreElements()) {
            String n = e.nextElement().getName();
            if (n.startsWith(prefix) && n.endsWith(".json"))
                out.add("minecraft:" + n.substring(prefix.length(), n.length() - 5));
        }
        return out;
    }

    private static Set<String> blocksInIndex(String db) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT surface_block FROM chunks WHERE surface_block IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT data FROM chunk_voxels WHERE data IS NOT NULL");
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
                        int brace = name.indexOf('[');
                        out.add(brace >= 0 ? name.substring(0, brace) : name);
                        o += n;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  (chunk_voxels not available: " + e.getMessage() + ")");
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
        } catch (Exception e) { return null; } finally { inf.end(); }
        return bos.toByteArray();
    }

    // --- json writing ---

    private static void appendModel(StringBuilder sb, String name, Map<String, Object> model) {
        sb.append("    ").append(q(name)).append(": [");
        List<Object> faces = (List<Object>) model.get("faces");
        for (int i = 0; i < faces.size(); i++) {
            appendFace(sb, (Map<String, Object>) faces.get(i));
            if (i + 1 < faces.size()) sb.append(",");
        }
        sb.append("]");
    }

    private static void appendFace(StringBuilder sb, Map<String, Object> f) {
        sb.append("{\"c\":[");
        List<Object> cs = (List<Object>) f.get("c");
        for (int i = 0; i < cs.size(); i++) {
            appendJson(sb, cs.get(i));
            if (i + 1 < cs.size()) sb.append(",");
        }
        sb.append("],\"uv\":");
        appendJson(sb, f.get("uv"));
        if (f.get("t") != null) sb.append(",\"t\":").append(q((String) f.get("t")));
        if (f.get("ti") != null) sb.append(",\"ti\":").append(f.get("ti"));
        if (f.get("cf") != null) sb.append(",\"cf\":").append(q((String) f.get("cf")));
        sb.append("}");
    }

    @SuppressWarnings("unchecked")
    private static void appendJson(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { sb.append(q((String) o)); return; }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Map) {
            sb.append("{");
            int i = 0;
            Map<String, Object> m = (Map<String, Object>) o;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append(q(e.getKey())).append(":");
                appendJson(sb, e.getValue());
                if (++i < m.size()) sb.append(",");
            }
            sb.append("}");
            return;
        }
        if (o instanceof List) {
            sb.append("[");
            List<Object> l = (List<Object>) o;
            for (int i = 0; i < l.size(); i++) { appendJson(sb, l.get(i)); if (i + 1 < l.size()) sb.append(","); }
            sb.append("]");
            return;
        }
        sb.append(q(String.valueOf(o)));
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private ModelCompiler() {}
}
