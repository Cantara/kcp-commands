package com.cantara.kcp.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logs kcp-commands manifest injection events to ~/.kcp/usage.db (RFC-0017).
 * <p>
 * Event type: {@code inject} — written whenever a manifest hit is served to Claude Code.
 * Columns used:
 * <ul>
 *   <li>{@code unit_id}         — manifest key (e.g. "git", "docker", "npm-install")</li>
 *   <li>{@code token_estimate}  — rough token cost of the injected context (chars / 4)</li>
 *   <li>{@code project}         — basename of the working directory</li>
 *   <li>{@code session_id}      — Claude Code session UUID</li>
 * </ul>
 * Always non-blocking (virtual thread). Never throws. Never blocks the hook response.
 */
public final class UsageLogger {

    static Path dbPath = Path.of(System.getProperty("user.home"), ".kcp", "usage.db");

    private static final ReentrantLock WRITE_LOCK   = new ReentrantLock();
    private static volatile boolean    initialized  = false;

    private UsageLogger() {}

    /**
     * Log an inject event asynchronously — returns immediately.
     *
     * @param sessionId       Claude Code session UUID (may be empty)
     * @param projectDir      Working directory (cwd from hook JSON)
     * @param manifestKey     Resolved manifest key, e.g. "git", "git-log"
     * @param contextLength   Character length of the injected additionalContext
     */
    public static void logInject(String sessionId, String projectDir,
                                 String manifestKey, int contextLength) {
        Thread.ofVirtual().start(() -> {
            try {
                ensureSchema();
                String project = projectDir != null && !projectDir.isBlank()
                        ? Path.of(projectDir).getFileName().toString()
                        : "unknown";
                int tokenEstimate = Math.max(1, contextLength / 4);
                insert("inject", project, null, manifestKey, null, tokenEstimate, null, sessionId);
            } catch (Exception e) {
                System.err.println("[UsageLogger] inject failed: " + e);
            }
        });
    }

    private static void ensureSchema() throws Exception {
        if (initialized) return;
        WRITE_LOCK.lock();
        try {
            if (initialized) return;
            Files.createDirectories(dbPath.getParent());
            try (Connection conn = connect()) {
                conn.createStatement().executeUpdate("PRAGMA journal_mode=WAL");
                conn.createStatement().executeUpdate("""
                    CREATE TABLE IF NOT EXISTS usage_events (
                        id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp            TEXT    NOT NULL,
                        event_type           TEXT    NOT NULL,
                        project              TEXT,
                        query                TEXT,
                        unit_id              TEXT,
                        result_count         INTEGER,
                        token_estimate       INTEGER,
                        manifest_token_total INTEGER,
                        session_id           TEXT
                    )""");
                conn.createStatement().executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_usage_timestamp ON usage_events(timestamp)");
                conn.createStatement().executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_usage_type ON usage_events(event_type)");
                // Migration: add session_id if table was created by an older schema (kcp-mcp pre-0017)
                try { conn.createStatement().executeUpdate(
                    "ALTER TABLE usage_events ADD COLUMN session_id TEXT"); }
                catch (Exception ignored) { /* column already exists */ }
            }
            initialized = true;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static void insert(String eventType, String project, String query,
                                String unitId, Integer resultCount,
                                Integer tokenEstimate, Integer manifestTokenTotal,
                                String sessionId) throws Exception {
        WRITE_LOCK.lock();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO usage_events
                    (timestamp, event_type, project, query, unit_id,
                     result_count, token_estimate, manifest_token_total, session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, eventType);
            ps.setString(3, project);
            ps.setString(4, query);
            ps.setString(5, unitId);
            if (resultCount       != null) ps.setInt(6, resultCount);       else ps.setNull(6, java.sql.Types.INTEGER);
            if (tokenEstimate     != null) ps.setInt(7, tokenEstimate);     else ps.setNull(7, java.sql.Types.INTEGER);
            if (manifestTokenTotal!= null) ps.setInt(8, manifestTokenTotal);else ps.setNull(8, java.sql.Types.INTEGER);
            ps.setString(9, sessionId);
            ps.executeUpdate();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }
}
