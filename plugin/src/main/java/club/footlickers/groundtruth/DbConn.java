package club.footlickers.groundtruth;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A SQLite connection that is opened on first use and closed again once it has been idle for a while.
 *
 * <p>The databases run in {@code journal_mode=TRUNCATE}, not WAL. WAL memory-maps a wal-index
 * ({@code groundtruth.db-shm}) which several processes share - the plugin, the Python web service and
 * the offline dumper all open this database - and that shared mapping is what kept faulting with
 * SIGBUS inside the native driver, killing the whole JVM. Four crashes (2026-09-22, twice on
 * 2026-09-23, once on 2026-09-24) faulted at the same offset in that mapping. {@code
 * PRAGMA mmap_size=0} does not cover the wal-index. Without WAL there is no wal-index to fault on.
 *
 * <p>Closing when idle is kept anyway: it bounds how long a stale connection can linger, and it is
 * what lets a long-running process pick up a schema or journal-mode change made underneath it.
 */
final class DbConn {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("GroundTruth");

    private final String url;
    private final String[] pragmas;
    private final long idleMillis;
    private final Object lock = new Object();
    private Connection c;
    private long lastUsed;

    DbConn(String url, long idleMillis, String... pragmas) {
        this.url = url;
        this.idleMillis = idleMillis;
        this.pragmas = pragmas;
    }

    /** The connection, opening it if needed. Callers must not close it. */
    Connection get() throws SQLException {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (c != null && now - lastUsed > idleMillis) {
                closeLocked();
            }
            if (c == null || c.isClosed()) {
                c = DriverManager.getConnection(url);
                try (Statement st = c.createStatement()) {
                    for (String p : pragmas) {
                        try {
                            st.execute(p);
                        } catch (SQLException e) {
                            // a pragma that cannot be applied (a mode change while another process
                            // holds the database) must not take the connection down with it
                            LOG.warning("[GroundTruth] pragma failed (" + p + "): " + e.getMessage());
                        }
                    }
                }
            }
            lastUsed = now;
            return c;
        }
    }

    /** Close now if it has been idle longer than the timeout. Used by the periodic sweep. */
    void closeIfIdle() {
        synchronized (lock) {
            if (c != null && System.currentTimeMillis() - lastUsed > idleMillis) {
                closeLocked();
            }
        }
    }

    /** Close now; the next {@link #get()} reopens. */
    void close() {
        synchronized (lock) {
            closeLocked();
        }
    }

    private void closeLocked() {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // closing a connection that is already gone is not worth reporting
            }
            c = null;
        }
    }
}
