package club.footlickers.groundtruth;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Own SQLite DB, separate from the world save - GroundTruth never reads
 * or writes anything the live server owns except through the normal Bukkit chunk API.
 *
 * Concurrency (fixed 2026-09-20 after a real watchdog kill):
 *  - The DB runs in WAL mode with synchronous=NORMAL, so writers don't fsync per statement and
 *    readers never block on an in-progress write.
 *  - WRITES go through `conn` guarded by this object's monitor.
 *  - READS go through a SEPARATE `readConn` guarded by its own lock, so a `/groundtruth status`
 *    (or find) on the main thread can never queue behind the dump's write task and stall the
 *    server tick - which is exactly what tripped Paper's watchdog before.
 */
public class Storage {

    private Connection conn;      // writes only
    private Connection readConn;  // reads only
    private final String url;
    private final Object readLock = new Object();
    private final Logger log;

    public Storage(File dataFolder, Logger log) throws SQLException {
        this.log = log;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "groundtruth.db");
        this.url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.conn = DriverManager.getConnection(url);
        this.readConn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        try (Statement st = readConn.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
    }

    /**
     * Reopen both connections. A long-lived SQLite connection goes stale if another process (the
     * offline dumper, or a sqlite3 CLI) checkpoints/deletes the -wal/-shm out from under it, after
     * which every statement fails with SQLITE_CORRUPT even though the file itself is fine. Reopening
     * recovers without a server restart.
     */
    private synchronized void reopen() {
        log.warning("[GroundTruth] database connection went stale (likely an external dump/checkpoint) - reopening");
        try { conn.close(); } catch (SQLException ignored) {}
        try { readConn.close(); } catch (SQLException ignored) {}
        try {
            conn = DriverManager.getConnection(url);
            readConn = DriverManager.getConnection(url);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            try (Statement st = readConn.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
            }
        } catch (SQLException e) {
            log.severe("[GroundTruth] failed to reopen the database: " + e.getMessage());
        }
    }

    private static boolean isStale(SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("malformed") || m.contains("corrupt") || m.contains("closed")
                || m.contains("disk i/o") || m.contains("no such table");
    }

    private void migrate() throws SQLException {
        synchronized (this) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS chunks (" +
                        "world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, " +
                        "biome TEXT, inhabited_time INTEGER, indexed_at INTEGER, " +
                        "PRIMARY KEY (world, cx, cz))");
                st.execute("CREATE TABLE IF NOT EXISTS structures (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, world TEXT NOT NULL, type TEXT NOT NULL, " +
                        "min_x INTEGER, min_y INTEGER, min_z INTEGER, " +
                        "max_x INTEGER, max_y INTEGER, max_z INTEGER, " +
                        "first_seen INTEGER, " +
                        "UNIQUE(world, type, min_x, min_y, min_z))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_structures_world_type ON structures(world, type)");
            // Player waypoints (public or private), set from in-game or the website.
            st.execute("CREATE TABLE IF NOT EXISTS waypoints (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, name TEXT NOT NULL, world TEXT NOT NULL, " +
                    "x INTEGER, y INTEGER, z INTEGER, colour TEXT, icon TEXT, " +
                    "public INTEGER NOT NULL DEFAULT 0, created_ts INTEGER, updated_ts INTEGER, " +
                    "UNIQUE(uuid, name))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_waypoints_public ON waypoints(public, world)");
            // Which chunks each player has actually been in - powers the optional "render only visited
            // areas" mode (off by default) and "who has been here" investigations.
            st.execute("CREATE TABLE IF NOT EXISTS chunk_visits (" +
                    "uuid TEXT NOT NULL, world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, " +
                    "first_seen INTEGER, last_seen INTEGER, visits INTEGER NOT NULL DEFAULT 1, " +
                    "PRIMARY KEY (uuid, world, cx, cz))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_visits_chunk ON chunk_visits(world, cx, cz)");
            // Per-block layers, mirroring the offline dumper's schema exactly.
            st.execute("CREATE TABLE IF NOT EXISTS chunk_pixels (" +
                    "world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, " +
                    "rgb BLOB, hgt BLOB, ground_hgt BLOB, PRIMARY KEY (world, cx, cz))");
            st.execute("CREATE TABLE IF NOT EXISTS chunk_voxels (" +
                    "world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, " +
                    "data BLOB, indexed_at INTEGER, PRIMARY KEY (world, cx, cz))");
            st.execute("CREATE TABLE IF NOT EXISTS chunk_voxels_lod (" +
                    "world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, lod INTEGER NOT NULL, " +
                    "data BLOB, indexed_at INTEGER, PRIMARY KEY (world, cx, cz, lod))");
            // Same lookup-index the dumper creates: the map queries LOD blobs by (world, lod, cx, cz),
            // which the primary key can't satisfy without a full scan.
            st.execute("CREATE INDEX IF NOT EXISTS idx_voxels_lod_lookup ON chunk_voxels_lod (world, lod, cx, cz)");
        }
            // Terrain-layer columns (added 2026-09-20 for the map). Safe no-ops on a DB that already
            // has them; lets an older DB be upgraded in place. The offline dumper writes the same columns.
            addColumn("chunks", "surface_block", "TEXT");
            addColumn("chunks", "surface_y", "INTEGER");
            // Terrain-only height (added 2026-09-21): median of the per-column ground heights, so a
            // treetop or a sky island cannot spike the relief. surface_y stays the true high point.
            // The offline dumper computes the same values block by block.
            addColumn("chunks", "ground_y", "INTEGER");
            addColumn("chunks", "ground_block", "TEXT");
            // The plugin now writes the dumper's per-block layers too, so a server that only ever runs
            // the plugin still gets per-block detail and 3D. Same schema as the dumper's Db.
            addColumn("chunk_pixels", "ground_hgt", "BLOB");
        }
    }

    private void addColumn(String table, String col, String type) {
        try {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getColumns(null, null, table, col)) {
                if (rs.next()) return;
            }
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
            }
        } catch (SQLException e) {
            // column already exists / race - harmless
        }
    }

    public void upsertChunk(String world, int cx, int cz, String biome, long inhabitedTime,
                            String surfaceBlock, Integer surfaceY,
                            String groundBlock, Integer groundY) {
        String sql = "INSERT INTO chunks (world, cx, cz, biome, inhabited_time, indexed_at, surface_block, "
                + "surface_y, ground_block, ground_y) VALUES (?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(world, cx, cz) DO UPDATE SET biome=excluded.biome, " +
                "inhabited_time=excluded.inhabited_time, indexed_at=excluded.indexed_at, " +
                "surface_block=excluded.surface_block, surface_y=excluded.surface_y, " +
                "ground_block=COALESCE(excluded.ground_block, chunks.ground_block), " +
                "ground_y=COALESCE(excluded.ground_y, chunks.ground_y)";
        synchronized (this) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, world);
                    ps.setInt(2, cx);
                    ps.setInt(3, cz);
                    ps.setString(4, biome);
                    ps.setLong(5, inhabitedTime);
                    ps.setLong(6, System.currentTimeMillis() / 1000L);
                    ps.setString(7, surfaceBlock);
                    if (surfaceY == null) ps.setNull(8, java.sql.Types.INTEGER); else ps.setInt(8, surfaceY);
                    ps.setString(9, groundBlock);
                    if (groundY == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, groundY);
                    ps.executeUpdate();
                    return;
                } catch (SQLException e) {
                    if (attempt == 0 && isStale(e)) { reopen(); continue; }
                    log.warning("[GroundTruth] chunk upsert failed for " + world + " " + cx + "," + cz + ": " + e.getMessage());
                    return;
                }
            }
        }
    }

    /**
     * Write the per-block layers for one chunk: the 16x16 pixel grid, the voxel blob and any LOD
     * levels. Bytes are already in the dumper's formats, so this only compresses and stores.
     */
    public void upsertChunkDetail(String world, int cx, int cz, byte[] rgb, byte[] hgt, byte[] groundHgt,
                                  byte[] voxels, java.util.Map<Integer, byte[]> lods) {
        synchronized (this) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (rgb != null) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT OR REPLACE INTO chunk_pixels (world,cx,cz,rgb,hgt,ground_hgt) "
                                        + "VALUES (?,?,?,?,?,?)")) {
                            ps.setString(1, world);
                            ps.setInt(2, cx);
                            ps.setInt(3, cz);
                            ps.setBytes(4, club.footlickers.groundtruth.DetailBuilder.deflate(rgb));
                            ps.setBytes(5, club.footlickers.groundtruth.DetailBuilder.deflate(hgt));
                            ps.setBytes(6, groundHgt == null ? null
                                    : club.footlickers.groundtruth.DetailBuilder.deflate(groundHgt));
                            ps.executeUpdate();
                        }
                    }
                    long now = System.currentTimeMillis() / 1000L;
                    if (voxels != null) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT OR REPLACE INTO chunk_voxels (world,cx,cz,data,indexed_at) "
                                        + "VALUES (?,?,?,?,?)")) {
                            ps.setString(1, world);
                            ps.setInt(2, cx);
                            ps.setInt(3, cz);
                            ps.setBytes(4, club.footlickers.groundtruth.DetailBuilder.deflate(voxels));
                            ps.setLong(5, now);
                            ps.executeUpdate();
                        }
                    }
                    if (lods != null && !lods.isEmpty()) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT OR REPLACE INTO chunk_voxels_lod (world,cx,cz,lod,data,indexed_at) "
                                        + "VALUES (?,?,?,?,?,?)")) {
                            for (java.util.Map.Entry<Integer, byte[]> e : lods.entrySet()) {
                                ps.setString(1, world);
                                ps.setInt(2, cx);
                                ps.setInt(3, cz);
                                ps.setInt(4, e.getKey());
                                ps.setBytes(5, club.footlickers.groundtruth.DetailBuilder.deflate(e.getValue()));
                                ps.setLong(6, now);
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                    }
                    return;
                } catch (SQLException e) {
                    if (attempt == 0 && isStale(e)) { reopen(); continue; }
                    log.warning("[GroundTruth] detail write failed for " + world + " " + cx + "," + cz
                            + ": " + e.getMessage());
                    return;
                }
            }
        }
    }

    public boolean isChunkIndexed(String world, int cx, int cz) {
        synchronized (readLock) {
            String sql = "SELECT 1 FROM chunks WHERE world=? AND cx=? AND cz=?";
            try (PreparedStatement ps = readConn.prepareStatement(sql)) {
                ps.setString(1, world);
                ps.setInt(2, cx);
                ps.setInt(3, cz);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                return false;
            }
        }
    }

    /** All indexed (cx,cz) for a world packed as (cx<<32)|(cz & 0xffffffff) - lets the offline dumper
        skip already-indexed chunks in memory instead of a synchronous DB read per candidate. */
    public Set<Long> chunkKeys(String world) {
        Set<Long> out = new HashSet<>();
        synchronized (readLock) {
            try (PreparedStatement ps = readConn.prepareStatement("SELECT cx, cz FROM chunks WHERE world=?")) {
                ps.setString(1, world);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(((long) rs.getInt(1) << 32) | (rs.getInt(2) & 0xffffffffL));
                    }
                }
            } catch (SQLException e) {
                log.warning("[GroundTruth] chunkKeys failed for " + world + ": " + e.getMessage());
            }
        }
        return out;
    }

    public void insertStructureIfNew(String world, String type,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        String sql = "INSERT OR IGNORE INTO structures " +
                "(world, type, min_x, min_y, min_z, max_x, max_y, max_z, first_seen) VALUES (?,?,?,?,?,?,?,?,?)";
        synchronized (this) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, world);
                    ps.setString(2, type);
                    ps.setInt(3, minX);
                    ps.setInt(4, minY);
                    ps.setInt(5, minZ);
                    ps.setInt(6, maxX);
                    ps.setInt(7, maxY);
                    ps.setInt(8, maxZ);
                    ps.setLong(9, System.currentTimeMillis() / 1000L);
                    ps.executeUpdate();
                    return;
                } catch (SQLException e) {
                    if (attempt == 0 && isStale(e)) { reopen(); continue; }
                    log.warning("[GroundTruth] structure insert failed for " + type + ": " + e.getMessage());
                    return;
                }
            }
        }
    }

    public static class StructureHit {
        public final String type;
        public final int x, y, z;
        public final int minX, minY, minZ, maxX, maxY, maxZ;
        public StructureHit(String type, int x, int y, int z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.type = type; this.x = x; this.y = y; this.z = z;
            this.minX = minX; this.minY = minY; this.minZ = minZ; this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    /** One world and its row counts, for the web API. */
    public static final class WorldRow {
        public String world;
        public long chunks, structures;
    }

    /** Every indexed world with its chunk and structure counts. */
    public List<WorldRow> worlds() {
        List<WorldRow> out = new ArrayList<>();
        synchronized (readLock) {
            try (Statement st = readConn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT c.world, COUNT(*), "
                       + "(SELECT COUNT(*) FROM structures s WHERE s.world=c.world) "
                       + "FROM chunks c GROUP BY c.world ORDER BY 2 DESC")) {
                while (rs.next()) {
                    WorldRow w = new WorldRow();
                    w.world = rs.getString(1);
                    w.chunks = rs.getLong(2);
                    w.structures = rs.getLong(3);
                    out.add(w);
                }
            } catch (SQLException e) {
                // report what we can rather than failing the request
            }
        }
        return out;
    }

    /** Nearest matches to (originX, originZ) in a world, by center-of-bbox distance. */
    public List<StructureHit> findNearest(String world, String typeFilter, int originX, int originZ, int limit) {
        List<StructureHit> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT type, (min_x+max_x)/2 AS cx, (min_y+max_y)/2 AS cy, (min_z+max_z)/2 AS cz, " +
                "min_x, min_y, min_z, max_x, max_y, max_z FROM structures WHERE world=?");
        if (typeFilter != null) sql.append(" AND type LIKE ?");
        synchronized (readLock) {
            try (PreparedStatement ps = readConn.prepareStatement(sql.toString())) {
                ps.setString(1, world);
                if (typeFilter != null) ps.setString(2, "%" + typeFilter.toUpperCase() + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    List<StructureHit> all = new ArrayList<>();
                    while (rs.next()) {
                        all.add(new StructureHit(rs.getString("type"), rs.getInt("cx"), rs.getInt("cy"), rs.getInt("cz"),
                                rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                                rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z")));
                    }
                    all.sort((a, b) -> Double.compare(dist2(a, originX, originZ), dist2(b, originX, originZ)));
                    for (int i = 0; i < Math.min(limit, all.size()); i++) results.add(all.get(i));
                }
            } catch (SQLException e) {
                log.warning("[GroundTruth] find query failed: " + e.getMessage());
            }
        }
        return results;
    }

    private static double dist2(StructureHit h, int ox, int oz) {
        double dx = h.x - ox, dz = h.z - oz;
        return dx * dx + dz * dz;
    }

    /** Record that a player has been in a chunk (first/last seen + a visit count). Cheap upsert. */
    public void recordVisit(String uuid, String world, int cx, int cz) {
        String sql = "INSERT INTO chunk_visits (uuid,world,cx,cz,first_seen,last_seen,visits) VALUES (?,?,?,?,?,?,1) " +
                "ON CONFLICT(uuid,world,cx,cz) DO UPDATE SET last_seen=excluded.last_seen, visits=visits+1";
        long now = System.currentTimeMillis() / 1000L;
        synchronized (this) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uuid);
                    ps.setString(2, world);
                    ps.setInt(3, cx);
                    ps.setInt(4, cz);
                    ps.setLong(5, now);
                    ps.setLong(6, now);
                    ps.executeUpdate();
                    return;
                } catch (SQLException e) {
                    if (attempt == 0 && isStale(e)) { reopen(); continue; }
                    return;
                }
            }
        }
    }

    public static class Waypoint {
        public final String uuid, name, world;
        public final int x, y, z;
        public final boolean isPublic;
        public Waypoint(String uuid, String name, String world, int x, int y, int z, boolean isPublic) {
            this.uuid = uuid; this.name = name; this.world = world;
            this.x = x; this.y = y; this.z = z; this.isPublic = isPublic;
        }
    }

    /** Create or update a player's waypoint (name is unique per player). */
    public void putWaypoint(String uuid, String name, String world, int x, int y, int z, boolean isPublic) {
        String sql = "INSERT INTO waypoints (uuid,name,world,x,y,z,public,created_ts,updated_ts) VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(uuid,name) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, " +
                "public=excluded.public, updated_ts=excluded.updated_ts";
        long now = System.currentTimeMillis() / 1000L;
        synchronized (this) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uuid);
                    ps.setString(2, name);
                    ps.setString(3, world);
                    ps.setInt(4, x); ps.setInt(5, y); ps.setInt(6, z);
                    ps.setInt(7, isPublic ? 1 : 0);
                    ps.setLong(8, now); ps.setLong(9, now);
                    ps.executeUpdate();
                    return;
                } catch (SQLException e) {
                    if (attempt == 0 && isStale(e)) { reopen(); continue; }
                    log.warning("[GroundTruth] waypoint save failed: " + e.getMessage());
                    return;
                }
            }
        }
    }

    public boolean removeWaypoint(String uuid, String name) {
        synchronized (this) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM waypoints WHERE uuid=? AND name=?")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                log.warning("[GroundTruth] waypoint delete failed: " + e.getMessage());
                return false;
            }
        }
    }

    /** Public waypoints plus the given player's own private ones. */
    public List<Waypoint> listWaypoints(String uuid) {
        List<Waypoint> out = new ArrayList<>();
        synchronized (readLock) {
            try (PreparedStatement ps = readConn.prepareStatement(
                    "SELECT uuid,name,world,x,y,z,public FROM waypoints WHERE public=1 OR uuid=?")) {
                ps.setString(1, uuid == null ? "" : uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Waypoint(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7) == 1));
                    }
                }
            } catch (SQLException e) {
                log.warning("[GroundTruth] waypoint list failed: " + e.getMessage());
            }
        }
        return out;
    }

    public long countChunks(String world) {        return count("SELECT COUNT(*) FROM chunks WHERE world=?", world);
    }

    public long countStructures(String world) {
        return count("SELECT COUNT(*) FROM structures WHERE world=?", world);
    }

    private long count(String sql, String world) {
        synchronized (readLock) {
            try (PreparedStatement ps = readConn.prepareStatement(sql)) {
                ps.setString(1, world);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            } catch (SQLException e) {
                return -1;
            }
        }
    }

    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
        try { readConn.close(); } catch (SQLException ignored) {}
    }
}
