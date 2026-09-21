package club.footlickers.groundtruth.dumper;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * Batched SQLite writer for the dumper.
 *
 * <p>Uses the SAME schema as the GroundTruth plugin (so the plugin and the dumper share one DB),
 * with two extra columns on {@code chunks} for the terrain layer: {@code surface_block} and
 * {@code surface_y}. It creates the schema if missing and safely {@code ALTER TABLE}s the two
 * columns onto an older DB (so re-running against a live DB just adds the surface data).
 *
 * <p>Writes are batched ({@value #BATCH} rows per transaction) and committed in one transaction per
 * batch, so a full-world dump never holds a long write lock against the live server.
 */
public class Db implements AutoCloseable {

    static {
        // The bundled sqlite-jdbc driver must be registered explicitly: when the jar is shaded,
        // the library's META-INF/services entry can be lost, leaving "No suitable driver". Doing it
        // here makes the fat jar self-contained and independent of ServiceLoader discovery.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // driver truly missing - getConnection below will fail with a clear message
        }
    }

    private static final int BATCH = 20000;

    private final Connection conn;
    private final PreparedStatement chunkPs;
    private final PreparedStatement structPs;
    private final PreparedStatement pixelPs;
    private final PreparedStatement voxelPs;
    private final PreparedStatement voxelLodPs;
    private final PreparedStatement nbtClearPs;
    private final PreparedStatement nbtPs;
    private int pending = 0;

    public Db(String path) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=10000");
        }
        migrate();
        chunkPs = conn.prepareStatement(
                "INSERT INTO chunks (world,cx,cz,biome,inhabited_time,indexed_at,surface_block,surface_y," +
                "ground_block,ground_y) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(world,cx,cz) DO UPDATE SET " +
                "biome=excluded.biome, inhabited_time=excluded.inhabited_time, indexed_at=excluded.indexed_at, " +
                "surface_block=excluded.surface_block, surface_y=excluded.surface_y, " +
                "ground_block=COALESCE(excluded.ground_block, chunks.ground_block), " +
                "ground_y=COALESCE(excluded.ground_y, chunks.ground_y)");
        structPs = conn.prepareStatement(
                "INSERT OR IGNORE INTO structures (world,type,min_x,min_y,min_z,max_x,max_y,max_z,first_seen) " +
                "VALUES (?,?,?,?,?,?,?,?,?)");
        pixelPs = conn.prepareStatement(
                "INSERT OR REPLACE INTO chunk_pixels (world,cx,cz,rgb,hgt,ground_hgt) VALUES (?,?,?,?,?,?)");
        voxelPs = conn.prepareStatement(
                "INSERT OR REPLACE INTO chunk_voxels (world,cx,cz,data,indexed_at) VALUES (?,?,?,?,?)");
        voxelLodPs = conn.prepareStatement(
                "INSERT OR REPLACE INTO chunk_voxels_lod (world,cx,cz,lod,data,indexed_at) "
                + "VALUES (?,?,?,?,?,?)");
        nbtClearPs = conn.prepareStatement(
                "DELETE FROM chunk_nbt WHERE world=? AND x>=? AND x<? AND z>=? AND z<?");
        nbtPs = conn.prepareStatement(
                "INSERT OR REPLACE INTO chunk_nbt (world,kind,x,y,z,id,nbt) VALUES (?,?,?,?,?,?,?)");
        conn.setAutoCommit(false);
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS chunks (" +
                    "world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, " +
                    "biome TEXT, inhabited_time INTEGER, indexed_at INTEGER, " +
                    "PRIMARY KEY (world, cx, cz))");
            st.execute("CREATE TABLE IF NOT EXISTS structures (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, world TEXT NOT NULL, type TEXT NOT NULL, " +
                    "min_x INTEGER, min_y INTEGER, min_z INTEGER, " +
                    "max_x INTEGER, max_y INTEGER, max_z INTEGER, first_seen INTEGER, " +
                    "UNIQUE(world, type, min_x, min_y, min_z))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_structures_world_type ON structures(world, type)");
            // Per-chunk block-resolution detail (16x16 surface colours + heights), zlib-compressed.
            // This is what lets the map show builds, not just one colour per chunk.
            st.execute("CREATE TABLE IF NOT EXISTS chunk_pixels (" +
                    "world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, " +
                    "rgb BLOB, hgt BLOB, PRIMARY KEY (world, cx, cz))");
            // Full per-chunk voxel data (palette + per-column runs), zlib-compressed. This is what
            // the voxel mesher renders so trees/overhangs/builds are real 3D, not one flat column.
            st.execute("CREATE TABLE IF NOT EXISTS chunk_voxels (" +
                    "world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, " +
                    "data BLOB, indexed_at INTEGER, PRIMARY KEY (world, cx, cz))");
            // Decimated copies of the same blocks (each cell is a 2^lod cube). These are what make a
            // Minecraft-looking zoom-out possible: level 1 is 1/8 the size, level 2 is 1/64, and so on.
            st.execute("CREATE TABLE IF NOT EXISTS chunk_voxels_lod (" +
                    "world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, lod INTEGER NOT NULL, " +
                    "data BLOB, indexed_at INTEGER, PRIMARY KEY (world, cx, cz, lod))");
            // Everything else a chunk carries that we might want later: block-entity and entity NBT,
            // serialised to JSON (see Nbt.toJson). `kind` is 'block_entity' or 'entity'. Positions are
            // floored to block coords (the exact doubles remain inside `nbt`).
            st.execute("CREATE TABLE IF NOT EXISTS chunk_nbt (" +
                    "world TEXT NOT NULL, kind TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, " +
                    "z INTEGER NOT NULL, id TEXT, nbt TEXT, PRIMARY KEY (world, kind, x, y, z))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chunk_nbt_world_xz ON chunk_nbt(world, x, z)");
        }
        addColumn("chunks", "surface_block", "TEXT");
        addColumn("chunks", "surface_y", "INTEGER");
        // Terrain-only height for the same chunk: median of the per-column ground heights, so a
        // treetop or a sky island cannot spike the relief. surface_y stays the true high point.
        addColumn("chunks", "ground_y", "INTEGER");
        addColumn("chunks", "ground_block", "TEXT");
        addColumn("chunk_pixels", "ground_hgt", "BLOB");
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
            // column exists / race - fine
        }
    }

    public void upsertChunk(String world, int cx, int cz, String biome, long inhabited,
                            String surfaceBlock, Integer surfaceY,
                            String groundBlock, Integer groundY) throws SQLException {
        chunkPs.setString(1, world);
        chunkPs.setInt(2, cx);
        chunkPs.setInt(3, cz);
        chunkPs.setString(4, biome);
        chunkPs.setLong(5, inhabited);
        chunkPs.setLong(6, System.currentTimeMillis() / 1000L);
        chunkPs.setString(7, surfaceBlock);
        if (surfaceY == null) chunkPs.setNull(8, Types.INTEGER); else chunkPs.setInt(8, surfaceY);
        chunkPs.setString(9, groundBlock);
        if (groundY == null) chunkPs.setNull(10, Types.INTEGER); else chunkPs.setInt(10, groundY);
        chunkPs.addBatch();
        tick();
    }

    public void insertStructure(String world, String type,
                                int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws SQLException {
        structPs.setString(1, world);
        structPs.setString(2, type);
        structPs.setInt(3, minX); structPs.setInt(4, minY); structPs.setInt(5, minZ);
        structPs.setInt(6, maxX); structPs.setInt(7, maxY); structPs.setInt(8, maxZ);
        structPs.setLong(9, System.currentTimeMillis() / 1000L);
        structPs.addBatch();
        tick();
    }

    /** Store one chunk's 16x16 surface colour + height grid (compressed with zlib). */
    public void upsertPixels(String world, int cx, int cz, byte[] rgb, byte[] hgt, byte[] groundHgt)
            throws SQLException {
        pixelPs.setString(1, world);
        pixelPs.setInt(2, cx);
        pixelPs.setInt(3, cz);
        pixelPs.setBytes(4, deflate(rgb));
        pixelPs.setBytes(5, deflate(hgt));
        pixelPs.setBytes(6, groundHgt == null ? null : deflate(groundHgt));
        pixelPs.addBatch();
        tick();
    }

    /** Store one chunk's decimated voxel data for a LOD level (compressed with zlib). */
    public void upsertVoxelsLod(String world, int cx, int cz, int lod, byte[] data) throws SQLException {
        voxelLodPs.setString(1, world);
        voxelLodPs.setInt(2, cx);
        voxelLodPs.setInt(3, cz);
        voxelLodPs.setInt(4, lod);
        voxelLodPs.setBytes(5, deflate(data));
        voxelLodPs.setLong(6, System.currentTimeMillis() / 1000L);
        voxelLodPs.addBatch();
        tick();
    }

    /** Store one chunk's voxel palette + per-column runs (compressed with zlib). */
    public void upsertVoxels(String world, int cx, int cz, byte[] data) throws SQLException {
        voxelPs.setString(1, world);
        voxelPs.setInt(2, cx);
        voxelPs.setInt(3, cz);
        voxelPs.setBytes(4, deflate(data));
        voxelPs.setLong(5, System.currentTimeMillis() / 1000L);
        voxelPs.addBatch();
        tick();
    }

    /** Remove a chunk's NBT rows (so a re-dump reflects removals) then insert fresh ones. */
    public void clearChunkNbt(String world, int cx, int cz) throws SQLException {
        nbtClearPs.setString(1, world);
        nbtClearPs.setInt(2, cx * 16);
        nbtClearPs.setInt(3, cx * 16 + 16);
        nbtClearPs.setInt(4, cz * 16);
        nbtClearPs.setInt(5, cz * 16 + 16);
        nbtClearPs.executeUpdate();
    }

    /** Store one block-entity / entity's full NBT as JSON. */
    public void insertChunkNbt(String world, String kind, double x, double y, double z,
                               String id, String json) throws SQLException {
        nbtPs.setString(1, world);
        nbtPs.setString(2, kind);
        nbtPs.setInt(3, (int) Math.floor(x));
        nbtPs.setInt(4, (int) Math.floor(y));
        nbtPs.setInt(5, (int) Math.floor(z));
        nbtPs.setString(6, id);
        nbtPs.setString(7, json);
        nbtPs.addBatch();
        tick();
    }

    static byte[] deflate(byte[] data) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
        d.setInput(data);
        d.finish();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(data.length / 2 + 16);
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bos.write(buf, 0, n);
        }
        d.end();
        return bos.toByteArray();
    }

    private void tick() throws SQLException {
        if (++pending >= BATCH) flush();
    }

    public void flush() throws SQLException {
        chunkPs.executeBatch();
        structPs.executeBatch();
        pixelPs.executeBatch();
        voxelPs.executeBatch();
        voxelLodPs.executeBatch();
        nbtPs.executeBatch();
        conn.commit();
        pending = 0;
    }

    @Override
    public void close() throws SQLException {
        flush();
        conn.close();
    }
}
