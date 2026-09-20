package club.footlickers.groundtruth;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * M-Log event log - a SEPARATE SQLite database ({@code groundtruth-log.db}) from the map DB, because
 * logging is write-heavy and the map DB is read-mostly. Keeping it separate also means the logger can
 * ship on its own.
 *
 * <p>Nothing here ever touches the game thread's critical path: listeners call the {@code log*}
 * methods, which only enqueue a row. A single daemon writer thread drains the queue in batches inside
 * one transaction. If the queue ever fills, rows are DROPPED (and counted) rather than blocking the
 * tick - a missing log line is always better than a lagging server.
 */
public class LogDb implements AutoCloseable {

    private static final int QUEUE_CAP = 200_000;
    private static final int BATCH_MAX = 4_000;

    private final Connection writeConn;
    private final Connection readConn;
    private final Object readLock = new Object();
    private final Logger log;

    private final BlockingQueue<Object[]> queue = new ArrayBlockingQueue<>(QUEUE_CAP);
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private final Thread writer;

    public LogDb(File dataFolder, Logger log) throws SQLException {
        this.log = log;
        if (!dataFolder.exists()) dataFolder.mkdirs();
        File dbFile = new File(dataFolder, "groundtruth-log.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.writeConn = DriverManager.getConnection(url);
        this.readConn = DriverManager.getConnection(url);
        try (Statement st = writeConn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=10000");
        }
        try (Statement st = readConn.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
        writer = new Thread(this::writerLoop, "GroundTruth-LogWriter");
        writer.setDaemon(true);
        writer.start();
    }

    private void migrate() throws SQLException {
        try (Statement st = writeConn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS log_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                    "world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, " +
                    "action TEXT NOT NULL, actor_kind TEXT, actor_id TEXT, actor_name TEXT, " +
                    "cause_kind TEXT, cause_id TEXT, cause_name TEXT, " +
                    "target TEXT, before TEXT, after TEXT, meta TEXT, session_id INTEGER, " +
                    "reverted INTEGER NOT NULL DEFAULT 0, reverted_by INTEGER, reverted_at INTEGER)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_log_pos    ON log_events(world, x, z, ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_log_actor  ON log_events(actor_id, ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_log_ts     ON log_events(ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_log_action ON log_events(action, ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_log_target ON log_events(target, ts)");
            st.execute("CREATE TABLE IF NOT EXISTS log_sessions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, name TEXT, " +
                    "join_ts INTEGER NOT NULL, quit_ts INTEGER, ip TEXT, world TEXT, " +
                    "last_x INTEGER, last_y INTEGER, last_z INTEGER)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sessions_uuid ON log_sessions(uuid, join_ts)");
            st.execute("CREATE TABLE IF NOT EXISTS log_players (" +
                    "uuid TEXT PRIMARY KEY, name TEXT, first_seen INTEGER, last_seen INTEGER, " +
                    "play_seconds INTEGER NOT NULL DEFAULT 0, last_ip TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS log_positions (" +
                    "uuid TEXT NOT NULL, ts INTEGER NOT NULL, world TEXT NOT NULL, " +
                    "x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pos_uuid_ts  ON log_positions(uuid, ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pos_world_ts ON log_positions(world, ts)");
        }
    }

    // --- enqueue API (called from the game thread; never blocks) --------------------------------

    /** kind = 'event' | 'session_start' | 'session_end' | 'position' | 'player_seen' */
    private void offer(Object[] row) {
        if (!queue.offer(row)) dropped.incrementAndGet();
    }

    public void logEvent(long ts, String world, int x, int y, int z, String action,
                         String actorKind, String actorId, String actorName,
                         String causeKind, String causeId, String causeName,
                         String target, String before, String after, String meta, long sessionId) {
        offer(new Object[] { "event", ts, world, x, y, z, action, actorKind, actorId, actorName,
                causeKind, causeId, causeName, target, before, after, meta, sessionId });
    }

    public void sessionStart(String uuid, String name, String ip, String world, long ts) {
        offer(new Object[] { "session_start", uuid, name, ip, world, ts });
    }

    public void sessionEnd(String uuid, long ts, String world, int x, int y, int z) {
        offer(new Object[] { "session_end", uuid, ts, world, x, y, z });
    }

    public void playerSeen(String uuid, String name, String ip, long ts) {
        offer(new Object[] { "player_seen", uuid, name, ip, ts });
    }

    public void position(String uuid, String world, int x, int y, int z, long ts) {
        offer(new Object[] { "position", uuid, world, x, y, z, ts });
    }

    // --- writer thread --------------------------------------------------------------------------

    private void writerLoop() {
        List<Object[]> batch = new ArrayList<>(BATCH_MAX);
        while (running || !queue.isEmpty()) {
            try {
                Object[] first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH_MAX - 1);
                writeBatch(batch);
                written.addAndGet(batch.size());
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warning("[GroundTruth] log write failed: " + e.getMessage());
                batch.clear();
            }
        }
    }

    private void writeBatch(List<Object[]> batch) throws SQLException {
        synchronized (this) {
            writeConn.setAutoCommit(false);
            try (PreparedStatement ev = writeConn.prepareStatement(
                        "INSERT INTO log_events (ts,world,x,y,z,action,actor_kind,actor_id,actor_name," +
                        "cause_kind,cause_id,cause_name,target,before,after,meta,session_id) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement ss = writeConn.prepareStatement(
                        "INSERT INTO log_sessions (uuid,name,join_ts,ip,world) VALUES (?,?,?,?,?)");
                 PreparedStatement se = writeConn.prepareStatement(
                        "UPDATE log_sessions SET quit_ts=?, world=?, last_x=?, last_y=?, last_z=? " +
                        "WHERE id=(SELECT id FROM log_sessions WHERE uuid=? AND quit_ts IS NULL " +
                        "ORDER BY join_ts DESC LIMIT 1)");
                 PreparedStatement ps = writeConn.prepareStatement(
                        "INSERT INTO log_players (uuid,name,first_seen,last_seen,last_ip) VALUES (?,?,?,?,?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_seen=excluded.last_seen, " +
                        "last_ip=excluded.last_ip");
                 PreparedStatement po = writeConn.prepareStatement(
                        "INSERT INTO log_positions (uuid,world,x,y,z,ts) VALUES (?,?,?,?,?,?)")) {
                for (Object[] r : batch) {
                    String kind = (String) r[0];
                    switch (kind) {
                        case "event":
                            ev.setLong(1, (Long) r[1]);
                            ev.setString(2, (String) r[2]);
                            ev.setInt(3, (Integer) r[3]); ev.setInt(4, (Integer) r[4]); ev.setInt(5, (Integer) r[5]);
                            ev.setString(6, (String) r[6]);
                            ev.setString(7, (String) r[7]); ev.setString(8, (String) r[8]); ev.setString(9, (String) r[9]);
                            ev.setString(10, (String) r[10]); ev.setString(11, (String) r[11]); ev.setString(12, (String) r[12]);
                            ev.setString(13, (String) r[13]); ev.setString(14, (String) r[14]); ev.setString(15, (String) r[15]);
                            ev.setString(16, (String) r[16]);
                            ev.setLong(17, (Long) r[17]);
                            ev.addBatch();
                            break;
                        case "session_start":
                            ss.setString(1, (String) r[1]); ss.setString(2, (String) r[2]);
                            ss.setString(3, (String) r[3]); ss.setString(4, (String) r[4]);
                            ss.setLong(5, (Long) r[5]);
                            ss.executeUpdate();
                            break;
                        case "session_end":
                            se.setLong(1, (Long) r[2]);
                            se.setString(2, (String) r[3]);
                            se.setInt(3, (Integer) r[4]); se.setInt(4, (Integer) r[5]); se.setInt(5, (Integer) r[6]);
                            se.setString(6, (String) r[1]);
                            se.executeUpdate();
                            break;
                        case "player_seen":
                            ps.setString(1, (String) r[1]); ps.setString(2, (String) r[2]);
                            ps.setLong(3, (Long) r[4]); ps.setLong(4, (Long) r[4]); ps.setString(5, (String) r[3]);
                            ps.executeUpdate();
                            break;
                        case "position":
                            po.setString(1, (String) r[1]); po.setString(2, (String) r[2]);
                            po.setInt(3, (Integer) r[3]); po.setInt(4, (Integer) r[4]); po.setInt(5, (Integer) r[5]);
                            po.setLong(6, (Long) r[6]);
                            po.addBatch();
                            break;
                        default:
                    }
                }
                ev.executeBatch();
                po.executeBatch();
                writeConn.commit();
            } catch (SQLException e) {
                writeConn.rollback();
                throw e;
            } finally {
                writeConn.setAutoCommit(true);
            }
        }
    }

    // --- reads (separate connection + lock, so a lookup never queues behind the writer) ----------

    public static class Hit {
        public long ts, id;
        public String world, action, actorKind, actorId, actorName, target, before, after, meta;
        public int x, y, z;
    }

    /** Recent events matching an optional player / radius / time window, newest first. */
    public List<Hit> lookup(String world, String playerFilter, int cx, int cz, int radius,
                            long sinceTs, String actionFilter, int limit) {
        List<Hit> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id,ts,world,x,y,z,action,actor_kind,actor_id,actor_name,target,before,after,meta " +
                "FROM log_events WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (world != null) { sql.append(" AND world=?"); args.add(world); }
        if (playerFilter != null) {
            sql.append(" AND (actor_name LIKE ? OR actor_id=?)");
            args.add("%" + playerFilter + "%"); args.add(playerFilter);
        }
        if (radius > 0) {
            sql.append(" AND x>=? AND x<=? AND z>=? AND z<=?"); // radius is in blocks
            args.add(cx - radius); args.add(cx + radius); args.add(cz - radius); args.add(cz + radius);
        }
        if (sinceTs > 0) { sql.append(" AND ts>=?"); args.add(sinceTs); }
        if (actionFilter != null) { sql.append(" AND action=?"); args.add(actionFilter); }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        args.add(limit);
        synchronized (readLock) {
            try (PreparedStatement ps = readConn.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Hit h = new Hit();
                        h.id = rs.getLong("id"); h.ts = rs.getLong("ts"); h.world = rs.getString("world");
                        h.x = rs.getInt("x"); h.y = rs.getInt("y"); h.z = rs.getInt("z");
                        h.action = rs.getString("action");
                        h.actorKind = rs.getString("actor_kind"); h.actorId = rs.getString("actor_id");
                        h.actorName = rs.getString("actor_name");
                        h.target = rs.getString("target"); h.before = rs.getString("before");
                        h.after = rs.getString("after"); h.meta = rs.getString("meta");
                        out.add(h);
                    }
                }
            } catch (SQLException e) {
                log.warning("[GroundTruth] log lookup failed: " + e.getMessage());
            }
        }
        return out;
    }

    /** Block place/break events that a rollback could undo, newest first. */
    public List<Hit> findRevertible(String world, String player, int cx, int cz, int radius,
                                    long sinceTs, int limit) {
        List<Hit> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id,ts,world,x,y,z,action,actor_name,target,before,after FROM log_events " +
                "WHERE reverted=0 AND action IN ('block-place','block-break')");
        List<Object> args = new ArrayList<>();
        if (world != null) { sql.append(" AND world=?"); args.add(world); }
        if (player != null) { sql.append(" AND actor_name=?"); args.add(player); }
        if (radius > 0) {
            sql.append(" AND x>=? AND x<=? AND z>=? AND z<=?");
            args.add(cx - radius); args.add(cx + radius); args.add(cz - radius); args.add(cz + radius);
        }
        if (sinceTs > 0) { sql.append(" AND ts>=?"); args.add(sinceTs); }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 50000)));
        synchronized (readLock) {
            try (PreparedStatement ps = readConn.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Hit h = new Hit();
                        h.id = rs.getLong("id"); h.ts = rs.getLong("ts"); h.world = rs.getString("world");
                        h.x = rs.getInt("x"); h.y = rs.getInt("y"); h.z = rs.getInt("z");
                        h.action = rs.getString("action"); h.actorName = rs.getString("actor_name");
                        h.target = rs.getString("target"); h.before = rs.getString("before");
                        h.after = rs.getString("after");
                        out.add(h);
                    }
                }
            } catch (SQLException e) {
                log.warning("[GroundTruth] rollback query failed: " + e.getMessage());
            }
        }
        return out;
    }

    /** Mark events reverted and record the rollback itself; returns how many were marked. */
    public synchronized int markReverted(List<Long> ids, String actorId, String actorName, String query) {
        if (ids.isEmpty()) return 0;
        try {
            writeConn.setAutoCommit(false);
            long rollbackId;
            try (PreparedStatement ps = writeConn.prepareStatement(
                    "INSERT INTO log_events (ts,world,x,y,z,action,actor_kind,actor_id,actor_name,target,meta) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, "-"); ps.setInt(3, 0); ps.setInt(4, 0); ps.setInt(5, 0);
                ps.setString(6, "rollback");
                ps.setString(7, "player"); ps.setString(8, actorId); ps.setString(9, actorName);
                ps.setString(10, null); ps.setString(11, query);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rollbackId = rs.next() ? rs.getLong(1) : 0; }
            }
            try (PreparedStatement ps = writeConn.prepareStatement(
                    "UPDATE log_events SET reverted=1, reverted_by=?, reverted_at=? WHERE id=?")) {
                for (Long id : ids) {
                    ps.setLong(1, rollbackId);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setLong(3, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            writeConn.commit();
            return ids.size();
        } catch (SQLException e) {
            try { writeConn.rollback(); } catch (SQLException ignored) {}
            log.warning("[GroundTruth] markReverted failed: " + e.getMessage());
            return 0;
        } finally {
            try { writeConn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    public long eventCount() {        synchronized (readLock) {
            try (Statement st = readConn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM log_events")) {
                return rs.next() ? rs.getLong(1) : -1;
            } catch (SQLException e) { return -1; }
        }
    }

    public long written() { return written.get(); }
    public long dropped() { return dropped.get(); }
    public int queueDepth() { return queue.size(); }

    @Override
    public void close() {
        running = false;
        try { writer.join(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { writeConn.close(); } catch (SQLException ignored) {}
        try { readConn.close(); } catch (SQLException ignored) {}
    }
}
