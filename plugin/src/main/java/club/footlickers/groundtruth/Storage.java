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
        }
            // Terrain-layer columns (added 2026-09-20 for the map). Safe no-ops on a DB that already
            // has them; lets an older DB be upgraded in place. The offline dumper writes the same columns.
            addColumn("chunks", "surface_block", "TEXT");
            addColumn("chunks", "surface_y", "INTEGER");
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
                            String surfaceBlock, Integer surfaceY) {
        String sql = "INSERT INTO chunks (world, cx, cz, biome, inhabited_time, indexed_at, surface_block, surface_y) " +
                "VALUES (?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(world, cx, cz) DO UPDATE SET biome=excluded.biome, " +
                "inhabited_time=excluded.inhabited_time, indexed_at=excluded.indexed_at, " +
                "surface_block=excluded.surface_block, surface_y=excluded.surface_y";
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
