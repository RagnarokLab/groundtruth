package club.footlickers.groundtruth;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;

/**
 * The map's data endpoints, served straight out of the plugin.
 *
 * <p>These are the endpoints the web viewer actually draws from: per-chunk terrain (with the surface
 * and ground layers), per-block pixel grids, the voxel/LOD data and the 2D detail grid. Binary layouts
 * are byte-for-byte what the old Python service produced, so the client needs no changes.
 *
 * <p>It reuses the dumper's own colour and biome-tint code (now compiled into the plugin), so the
 * colours here cannot drift from what the offline dumps write.
 */
public final class MapData {

    private final File dbFile;
    private final File tilesDir;
    private final MapColours colours;
    private final DbConn ro;   // read-only, closed when idle (see DbConn)
    private final Object lock = new Object();
    // cached "visited + radius" scope (rebuilt at most every 30s; every map request asks for it)
    private volatile java.util.Set<Long> scopeCache;
    private volatile long scopeCacheAt;
    private volatile String scopeCacheWorld;
    private volatile int scopeCacheRadius = -1;

    public MapData(File dataFolder, File tilesDir, MapColours colours) {
        this.dbFile = new File(dataFolder, "groundtruth.db");
        this.tilesDir = tilesDir;
        this.colours = colours;
        // busy_timeout matters because the dumpers and the plugin write to this database too; without
        // it a momentary write lock surfaced as an HTTP 500 on the first request after a restart.
        this.ro = new DbConn("jdbc:sqlite:file:" + dbFile.getAbsolutePath() + "?mode=ro", 45_000,
                "PRAGMA busy_timeout=15000", "PRAGMA mmap_size=0");
    }

    /** Close the read connection if it has gone idle. */
    public void closeIdle() {
        ro.closeIfIdle();
    }

    private Connection conn() throws SQLException {
        return ro.get();
    }

    /** Load the colour table and biome tints once, from the same files the dumper uses. */
    private void ensureTables() {
        synchronized (lock) {
            colours.load(tilesDir);
        }
    }

    /**
     * Open the read connection up front so the first map request doesn't pay the lazy-init cost (and
     * so a momentary write lock at startup can't surface as a one-off 500 on the first request).
     */
    public void warmup() {
        try {
            synchronized (lock) {
                conn();
                colours.load(tilesDir);
            }
        } catch (Exception e) {
            // not fatal - the first request will retry
        }
    }

    private byte[] inflate(byte[] deflated) throws Exception {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(deflated);
        ByteArrayOutputStream out = new ByteArrayOutputStream(deflated.length * 4);
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0) break;
            out.write(buf, 0, n);
        }
        inf.end();
        return out.toByteArray();
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

    static void putI16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    static void putI32(ByteArrayOutputStream o, int v) {
        o.write((v >> 24) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    static int getI16(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }

    static int getU16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** chunk_pixels stores its heights little-endian (raw byte writes in the dumper), unlike the
     *  DataOutputStream-written voxel/LOD blobs which are big-endian. Easy to get wrong; it was. */
    static int getU16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    // --- /api/terrain ----------------------------------------------------------------------------

    /** Per-chunk terrain: i16 minCx, i16 minCz, i32 count, then i16 dx,dz,surface_y,ground_y + rgb. */
    public byte[] terrain(String world, int cx0, int cz0, int cx1, int cz1, int step, boolean wantBounds,
                          boolean deflate, int[] meta) throws Exception {
        ensureTables();
        if (cx1 < cx0) { int t = cx0; cx0 = cx1; cx1 = t; }
        if (cz1 < cz0) { int t = cz0; cz0 = cz1; cz1 = t; }
        step = Math.max(1, step);

        List<int[]> cells = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        List<String> biomes = new ArrayList<>();
        int[] ext = null;
        synchronized (lock) {
            String sql = "SELECT cx,cz,surface_y,ground_y,surface_block,biome FROM chunks WHERE world=? "
                    + "AND cx>=? AND cx<=? AND cz>=? AND cz<=? AND surface_y IS NOT NULL";
            if (step > 1) sql += " AND (cx % " + step + ")=0 AND (cz % " + step + ")=0";
            sql += " LIMIT 450000";
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                ps.setString(1, world);
                ps.setInt(2, cx0); ps.setInt(3, cx1); ps.setInt(4, cz0); ps.setInt(5, cz1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        cells.add(new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3),
                                rs.getInt(4), rs.getInt(3) });
                        blocks.add(rs.getString(5));
                        biomes.add(rs.getString(6));
                    }
                }
            }
            if (wantBounds) {
                try (PreparedStatement ps = conn().prepareStatement(
                        "SELECT MIN(cx),MAX(cx),MIN(cz),MAX(cz),COUNT(*) FROM chunks WHERE world=? "
                                + "AND surface_y IS NOT NULL")) {
                    ps.setString(1, world);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getObject(1) != null) {
                            ext = new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5) };
                        }
                    }
                }
            }
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream(cells.size() * 11 + 12);
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            int rgb = colours.blockColor(blocks.get(i));
            rgb = colours.tintColor(biomes.get(i), blocks.get(i), rgb);
            putI16(body, c[0] - cx0);
            putI16(body, c[1] - cz0);
            putI16(body, c[2]);           // surface_y
            putI16(body, c[4]);           // ground_y (falls back to surface when the backfill missed it)
            body.write((rgb >> 16) & 0xFF);
            body.write((rgb >> 8) & 0xFF);
            body.write(rgb & 0xFF);
        }

        meta[0] = cells.size();
        if (ext != null) { meta[1] = ext[0]; meta[2] = ext[1]; meta[3] = ext[2]; meta[4] = ext[3]; meta[5] = ext[4]; }
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 16);
        putI16(out, cx0);
        putI16(out, cz0);
        putI32(out, cells.size());
        body.writeTo(out);
        if (deflate) return deflate(out.toByteArray());
        return out.toByteArray();
    }

    public int[] worldExtent(String world) throws Exception {
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT MIN(cx),MAX(cx),MIN(cz),MAX(cz),COUNT(*) FROM chunks WHERE world=? "
                            + "AND surface_y IS NOT NULL")) {
                ps.setString(1, world);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getObject(1) != null) {
                        return new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5) };
                    }
                }
            }
        }
        return null;
    }

    // --- /api/pixels ----------------------------------------------------------------------------

    /** Per-block grid: i16 ox0, i16 oz0, i16 px, i32 cols, i32 rows, then i16 surface_y, ground_y + rgb. */
    public byte[] pixels(String world, int cx0, int cz0, int cx1, int cz1, int px, boolean deflate)
            throws Exception {
        if (cx1 < cx0) { int t = cx0; cx0 = cx1; cx1 = t; }
        if (cz1 < cz0) { int t = cz0; cz0 = cz1; cz1 = t; }
        px = Math.max(1, px);
        int nx = cx1 - cx0 + 1, nz = cz1 - cz0 + 1;
        if (nx * nz > 12000) throw new IllegalArgumentException("area too large");

        int w = nx * 16, h = nz * 16;
        int[] surf = new int[w * h];
        int[] ground = new int[w * h];
        byte[] rgb = new byte[w * h * 3];
        java.util.Arrays.fill(surf, Integer.MIN_VALUE);

        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT cx,cz,rgb,hgt,ground_hgt FROM chunk_pixels WHERE world=? AND cx>=? AND cx<=? "
                            + "AND cz>=? AND cz<=?")) {
                ps.setString(1, world);
                ps.setInt(2, cx0); ps.setInt(3, cx1); ps.setInt(4, cz0); ps.setInt(5, cz1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int cx = rs.getInt(1), cz = rs.getInt(2);
                        byte[] hgtB = rs.getBytes(4);
                        byte[] ghB = rs.getBytes(5);
                        byte[] rgbB = rs.getBytes(3);
                        if (hgtB == null || rgbB == null) continue;
                        byte[] hgt = inflate(hgtB);
                        byte[] gh = ghB != null ? inflate(ghB) : hgt;
                        byte[] rgbRaw = inflate(rgbB);
                        if (hgt.length < 512 || rgbRaw.length < 768) continue;
                        int bx = (cx - cx0) * 16, bz = (cz - cz0) * 16;
                        for (int zi = 0; zi < 16; zi++) {
                            for (int xi = 0; xi < 16; xi++) {
                                int src = zi * 16 + xi;
                                int dst = (bz + zi) * w + (bx + xi);
                                surf[dst] = getU16LE(hgt, src * 2) + minYFor(world);
                                ground[dst] = getU16LE(gh, src * 2) + minYFor(world);
                                rgb[dst * 3] = rgbRaw[src * 3];
                                rgb[dst * 3 + 1] = rgbRaw[src * 3 + 1];
                                rgb[dst * 3 + 2] = rgbRaw[src * 3 + 2];
                            }
                        }
                    }
                }
            }
        }

        int cols = (w + px - 1) / px, rows = (h + px - 1) / px;
        if ((long) cols * rows > 400000) throw new IllegalArgumentException("area too large");
        ByteArrayOutputStream body = new ByteArrayOutputStream(cols * rows * 7 + 20);
        int[] vals = new int[px * px];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int n = 0, rs2 = 0, gs = 0, bs = 0, cnt = 0;
                for (int dz = 0; dz < px; dz++) {
                    for (int dx = 0; dx < px; dx++) {
                        int x = c * px + dx, z = r * px + dz;
                        if (x >= w || z >= h) continue;
                        int i = z * w + x;
                        if (surf[i] == Integer.MIN_VALUE) continue;
                        vals[n++] = surf[i];
                        rs2 += rgb[i * 3] & 0xFF;
                        gs += rgb[i * 3 + 1] & 0xFF;
                        bs += rgb[i * 3 + 2] & 0xFF;
                        cnt++;
                    }
                }
                if (cnt == 0) {
                    putI16(body, Short.MIN_VALUE);
                    putI16(body, Short.MIN_VALUE);
                    body.write(0); body.write(0); body.write(0);
                    continue;
                }
                java.util.Arrays.sort(vals, 0, n);
                int med = vals[n / 2];
                // the ground layer for this cell, taken the same way
                int gn = 0;
                int[] gvals = new int[n];
                for (int dz = 0; dz < px; dz++) {
                    for (int dx = 0; dx < px; dx++) {
                        int x = c * px + dx, z = r * px + dz;
                        if (x >= w || z >= h) continue;
                        int i = z * w + x;
                        if (surf[i] == Integer.MIN_VALUE) continue;
                        gvals[gn++] = ground[i];
                    }
                }
                java.util.Arrays.sort(gvals, 0, gn);
                putI16(body, med);
                putI16(body, gn > 0 ? gvals[gn / 2] : med);
                body.write(rs2 / cnt);
                body.write(gs / cnt);
                body.write(bs / cnt);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 24);
        putI16(out, Math.floorDiv(cx0 * 16, px));
        putI16(out, Math.floorDiv(cz0 * 16, px));
        putI16(out, px);
        putI32(out, cols);
        putI32(out, rows);
        body.writeTo(out);
        return deflate ? deflate(out.toByteArray()) : out.toByteArray();
    }

    private int minYFor(String world) {
        // the dumper stores heights relative to the dimension's minimum Y
        if (world != null && (world.contains("nether") || world.contains("the_end"))) return 0;
        return -64;
    }

    // --- /api/voxels ----------------------------------------------------------------------------

    /** Real blocks: lod 0 hands back stored chunks, higher levels are assembled virtual chunks. */
    public List<Object[]> voxels(String world, int cx0, int cz0, int cx1, int cz1, int lod, int[] vmeta)
            throws Exception {
        if (cx1 < cx0) { int t = cx0; cx0 = cx1; cx1 = t; }
        if (cz1 < cz0) { int t = cz0; cz0 = cz1; cz1 = t; }
        List<Object[]> out = new ArrayList<>();
        if (lod > 0) {
            int k = 1 << lod;
            int vx0 = cx0, vz0 = cz0, vx1 = cx1, vz1 = cz1;
            vmeta[0] = k; vmeta[1] = vx0; vmeta[2] = vz0; vmeta[3] = vx1; vmeta[4] = vz1;
            for (int vx = vx0; vx <= vx1; vx++) {
                for (int vz = vz0; vz <= vz1; vz++) {
                    byte[] blob = assembleLod(world, lod, vx, vz);
                    if (blob != null) out.add(new Object[] { vx, vz, blob });
                }
            }
            return out;
        }
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT cx,cz,data FROM chunk_voxels WHERE world=? AND cx>=? AND cx<=? AND cz>=? AND cz<=?")) {
                ps.setString(1, world);
                ps.setInt(2, cx0); ps.setInt(3, cx1); ps.setInt(4, cz0); ps.setInt(5, cz1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Object[] { rs.getInt(1), rs.getInt(2), rs.getBytes(3) });
                }
            }
        }
        return out;
    }

    /** Per-chunk biome for a rectangular chunk range, keyed by ((long)cx << 32) ^ (cz & 0xffffffffL). */
    public java.util.Map<Long, String> biomes(String world, int cx0, int cz0, int cx1, int cz1)
            throws Exception {
        java.util.Map<Long, String> m = new java.util.HashMap<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT cx,cz,biome FROM chunks WHERE world=? AND cx>=? AND cx<=? AND cz>=? AND cz<=?")) {
                ps.setString(1, world);
                ps.setInt(2, cx0); ps.setInt(3, cx1); ps.setInt(4, cz0); ps.setInt(5, cz1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        m.put(((long) rs.getInt(1) << 32) ^ (rs.getInt(2) & 0xffffffffL), rs.getString(3));
                    }
                }
            }
        }
        return m;
    }

    /**
     * Chunk keys where a player has actually been, for the "only show what we've seen" setting.
     *
     * <p>Uses the chunk's {@code inhabited_time} (from the world itself) rather than our own visit
     * logging: inhabited time is stored in the chunk NBT and accumulates whenever a player is near, so
     * it covers the world's whole life, not just since logging was added. Returns null when nothing is
     * recorded, so a fresh server is not blanked out. Cached ~30s.
     */
    public java.util.Set<Long> visitedScope(String world, int radius) {
        long now = System.currentTimeMillis();
        java.util.Set<Long> cached = scopeCache;
        if (cached != null && world.equals(scopeCacheWorld) && radius == scopeCacheRadius
                && now - scopeCacheAt < 300_000) {
            return cached;
        }
        java.util.Set<Long> out = new java.util.HashSet<>();
        int inhabited = 0;
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT cx,cz FROM chunks WHERE world=? AND inhabited_time > 0")) {
                ps.setString(1, world);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int cx = rs.getInt(1), cz = rs.getInt(2);
                        inhabited++;
                        // buffer: the map extends `radius` chunks past anywhere a player has been, so
                        // it doesn't end abruptly at the edge of the explored area.
                        for (int dx = -radius; dx <= radius; dx++) {
                            for (int dz = -radius; dz <= radius; dz++) {
                                out.add(((long) (cx + dx) << 32) ^ ((cz + dz) & 0xffffffffL));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                return null;   // no chunks table yet - don't filter
            }
        }
        if (inhabited == 0) return null;   // nothing seen yet - show everything rather than a blank map
        scopeCache = out;
        scopeCacheAt = now;
        scopeCacheWorld = world;
        scopeCacheRadius = radius;
        return out;
    }

    /**
     * Compose a top-down map image around a block position straight from the rendered tile pyramid.
     * Zoom 0 is one pixel per chunk (16 blocks); each higher zoom doubles the blocks per pixel. Reads
     * only the tiles that overlap, so it is cheap. World/layer are sanitised (they become a path).
     */
    public byte[] mapImage(String world, String layer, int blockX, int blockZ, int zoom, int w, int h)
            throws Exception {
        world = world == null ? "world" : world.replaceAll("[^A-Za-z0-9_-]", "");
        layer = layer == null ? "terrain" : layer.replaceAll("[^A-Za-z0-9_-]", "");
        String meta = new String(java.nio.file.Files.readAllBytes(
                new File(tilesDir, world + "/meta.json").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        int tile = metaInt(meta, "tile", 256);
        int minCx = metaInt(meta, "minCx", 0);
        int minCz = metaInt(meta, "minCz", 0);
        int maxZoom = metaInt(meta, "maxZoom", 0);
        zoom = Math.max(0, Math.min(maxZoom, zoom));
        w = Math.max(1, Math.min(2048, w));
        h = Math.max(1, Math.min(2048, h));

        int px0 = Math.floorDiv((blockX >> 4) - minCx, 1 << zoom) - w / 2;
        int pz0 = Math.floorDiv((blockZ >> 4) - minCz, 1 << zoom) - h / 2;
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        int tx0 = Math.floorDiv(px0, tile), tx1 = Math.floorDiv(px0 + w - 1, tile);
        int tz0 = Math.floorDiv(pz0, tile), tz1 = Math.floorDiv(pz0 + h - 1, tile);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                File f = new File(tilesDir, world + "/" + layer + "/" + zoom + "/" + tx + "_" + tz + ".png");
                if (!f.isFile()) continue;
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img != null) g.drawImage(img, tx * tile - px0, tz * tile - pz0, null);
            }
        }
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 16);
        javax.imageio.ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }

    private static int metaInt(String json, String key, int def) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    /** The recorded surface for one chunk: {biome, surface_block, surface_y} or null. */
    public String[] chunkSurface(String world, int cx, int cz) throws Exception {
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT biome,surface_block,surface_y FROM chunks WHERE world=? AND cx=? AND cz=?")) {
                ps.setString(1, world); ps.setInt(2, cx); ps.setInt(3, cz);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new String[] { rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3)) };
                    }
                }
            }
        }
        return null;
    }

    /** Merge 2^lod x 2^lod stored LOD chunks into one 16x16-cell blob the mesher understands. */
    private byte[] assembleLod(String world, int lod, int vx, int vz) throws Exception {
        int k = 1 << lod;
        int n = 16 >> lod;
        List<String> palette = new ArrayList<>();
        java.util.Map<String, Integer> index = new java.util.HashMap<>();
        List<int[]>[] cols = new List[256];
        for (int i = 0; i < 256; i++) cols[i] = new ArrayList<>();

        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT cx,cz,data FROM chunk_voxels_lod WHERE world=? AND lod=? AND cx>=? AND cx<=? "
                            + "AND cz>=? AND cz<=?")) {
                ps.setString(1, world);
                ps.setInt(2, lod);
                ps.setInt(3, vx * k); ps.setInt(4, vx * k + k - 1);
                ps.setInt(5, vz * k); ps.setInt(6, vz * k + k - 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int cx = rs.getInt(1), cz = rs.getInt(2);
                        byte[] raw = rs.getBytes(3);
                        if (raw == null) continue;
                        byte[] d = inflate(raw);
                        if (d.length < 4 || d[0] != 2) continue;
                        int off = 2;
                        int palLen = getU16(d, off); off += 2;
                        String[] src = new String[palLen];
                        for (int p = 0; p < palLen; p++) {
                            int len = getU16(d, off); off += 2;
                            src[p] = new String(d, off, len, java.nio.charset.StandardCharsets.UTF_8);
                            off += len;
                        }
                        int ox = (cx - vx * k) * n, oz = (cz - vz * k) * n;
                        for (int zi = 0; zi < n; zi++) {
                            for (int xi = 0; xi < n; xi++) {
                                int runCount = getU16(d, off); off += 2;
                                List<int[]> target = cols[(oz + zi) * 16 + (ox + xi)];
                                for (int r = 0; r < runCount; r++) {
                                    int y0 = getI16(d, off);
                                    int len = getU16(d, off + 2);
                                    int pi = getU16(d, off + 4);
                                    off += 6;
                                    if (pi < 1 || pi > src.length) continue;
                                    String name = src[pi - 1];
                                    Integer idx = index.get(name);
                                    if (idx == null) { palette.add(name); idx = palette.size(); index.put(name, idx); }
                                    target.add(new int[] { y0, len, idx });
                                }
                            }
                        }
                    }
                }
            }
        }
        if (palette.isEmpty()) return null;

        ByteArrayOutputStream out = new ByteArrayOutputStream(1024 + palette.size() * 24);
        out.write(1);
        putI16(out, palette.size());
        for (String name : palette) {
            byte[] nb = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            putI16(out, nb.length);
            out.write(nb, 0, nb.length);
        }
        for (int i = 0; i < 256; i++) {
            List<int[]> runs = cols[i];
            putI16(out, runs.size());
            for (int[] r : runs) {
                putI16(out, r[0]);
                putI16(out, r[1]);
                putI16(out, r[2]);
            }
        }
        return deflate(out.toByteArray());
    }

    // --- /api/detail ----------------------------------------------------------------------------

    /** Per-chunk 2D detail for the viewer: base64 rgb + hgt, exactly as the Python service returned. */
    public String detail(String world, int cx0, int cz0, int cx1, int cz1) throws Exception {
        if (cx1 < cx0) { int t = cx0; cx0 = cx1; cx1 = t; }
        if (cz1 < cz0) { int t = cz0; cz0 = cz1; cz1 = t; }
        StringBuilder sb = new StringBuilder(1024 + (cx1 - cx0 + 1) * (cz1 - cz0 + 1) * 300);
        sb.append("{\"world\":").append(LogListener.Json.str(world)).append(",\"chunks\":[");
        boolean first = true;
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT cx,cz,rgb,hgt FROM chunk_pixels WHERE world=? AND cx>=? AND cx<=? AND cz>=? AND cz<=?")) {
                ps.setString(1, world);
                ps.setInt(2, cx0); ps.setInt(3, cx1); ps.setInt(4, cz0); ps.setInt(5, cz1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] rgb = rs.getBytes(3), hgt = rs.getBytes(4);
                        if (rgb == null || hgt == null) continue;
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"cx\":").append(rs.getInt(1)).append(",\"cz\":").append(rs.getInt(2))
                          .append(",\"rgb\":\"").append(Base64.getEncoder().encodeToString(rgb))
                          .append("\",\"hgt\":\"").append(Base64.getEncoder().encodeToString(hgt)).append("\"}");
                    }
                }
            }
        }
        return sb.append("]}").toString();
    }
}
