package club.footlickers.groundtruth;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A SQLite connection that is opened on first use and closed again once it has been idle for a while.
 *
 * <p>SQLite in WAL mode always memory-maps the wal-index ({@code groundtruth.db-shm}); {@code
 * PRAGMA mmap_size=0} does not cover it. A process that keeps a connection open for days therefore
 * keeps that mapping alive across every checkpoint and WAL reset, and when another process (the
 * offline dumper) resets the wal-index while the mapping is still live, the next access faults with
 * SIGBUS inside the native driver - which kills the whole JVM, not just the query. Two server crashes
 * (2026-09-22 on a read from the server thread, 2026-09-23 on a web thread) faulted inside that
 * mapping. Closing when idle means any reset happens while this process holds no mapping.
 *
 * <p>It also stops the WAL growing without bound: a connection that never closes never lets SQLite
 * checkpoint, and the file had reached 2.3 GB.
 */
final class DbConn {

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
                        st.execute(p);
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
