package com.cantara.kcp.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UsageLogger — validates that inject events are written to SQLite
 * and that schema migration handles pre-existing tables missing session_id.
 */
class UsageLoggerTest {

    @TempDir
    Path tempDir;

    private Path originalDbPath;
    private Path testDbPath;

    @BeforeEach
    void setUp() throws Exception {
        // Save original static dbPath and redirect to temp location
        originalDbPath = UsageLogger.dbPath;
        testDbPath = tempDir.resolve("usage.db");
        UsageLogger.dbPath = testDbPath;

        // Reset the initialized flag so ensureSchema() runs fresh each test
        resetInitialized();
    }

    @AfterEach
    void tearDown() throws Exception {
        UsageLogger.dbPath = originalDbPath;
        resetInitialized();
    }

    @Test
    void logInject_writesEventToDb() throws Exception {
        UsageLogger.logInject("test-session-123", "/home/user/my-project",
                "docker", 2000);

        // Wait for the virtual thread to complete
        Thread.sleep(500);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT event_type, unit_id, project, session_id, token_estimate " +
                     "FROM usage_events WHERE event_type = 'inject'")) {

            assertTrue(rs.next(), "Expected at least one inject event row");
            assertEquals("inject", rs.getString("event_type"));
            assertEquals("docker", rs.getString("unit_id"));
            assertEquals("my-project", rs.getString("project"));
            assertEquals("test-session-123", rs.getString("session_id"));
            assertEquals(500, rs.getInt("token_estimate")); // 2000 / 4 = 500
            assertFalse(rs.next(), "Expected exactly one row");
        }
    }

    @Test
    void logInject_extractsProjectBasename() throws Exception {
        UsageLogger.logInject("s1", "/a/b/deep-nested-project", "npm-install", 400);
        Thread.sleep(500);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT project FROM usage_events WHERE event_type = 'inject'")) {
            assertTrue(rs.next());
            assertEquals("deep-nested-project", rs.getString("project"));
        }
    }

    @Test
    void logInject_handlesNullProjectDir() throws Exception {
        UsageLogger.logInject("s2", null, "git", 100);
        Thread.sleep(500);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT project FROM usage_events WHERE event_type = 'inject'")) {
            assertTrue(rs.next());
            assertEquals("unknown", rs.getString("project"));
        }
    }

    @Test
    void logInject_handlesBlankProjectDir() throws Exception {
        UsageLogger.logInject("s3", "  ", "aws", 200);
        Thread.sleep(500);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT project FROM usage_events WHERE event_type = 'inject'")) {
            assertTrue(rs.next());
            assertEquals("unknown", rs.getString("project"));
        }
    }

    @Test
    void ensureSchema_migratesOldDbMissingSessionId() throws Exception {
        // Create a DB with old schema (no session_id column)
        Files.createDirectories(testDbPath.getParent());
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDbPath);
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE usage_events (
                    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp            TEXT    NOT NULL,
                    event_type           TEXT    NOT NULL,
                    project              TEXT,
                    query                TEXT,
                    unit_id              TEXT,
                    result_count         INTEGER,
                    token_estimate       INTEGER,
                    manifest_token_total INTEGER
                )""");
            // Insert a pre-existing row to prove migration preserves data
            st.executeUpdate(
                "INSERT INTO usage_events (timestamp, event_type, project, unit_id) " +
                "VALUES ('2026-01-01T00:00:00Z', 'search', 'old-proj', 'xorcery')");
        }

        // Now call logInject — this triggers ensureSchema() which should add session_id
        UsageLogger.logInject("new-session", "/home/user/proj", "git", 800);
        Thread.sleep(500);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDbPath);
             Statement st = conn.createStatement()) {

            // Verify session_id column exists by querying it
            try (ResultSet rs = st.executeQuery(
                    "SELECT session_id FROM usage_events WHERE event_type = 'search'")) {
                assertTrue(rs.next());
                assertNull(rs.getString("session_id")); // old row has null session_id
            }

            // Verify the new inject row was written successfully with session_id
            try (ResultSet rs = st.executeQuery(
                    "SELECT session_id, unit_id FROM usage_events WHERE event_type = 'inject'")) {
                assertTrue(rs.next(), "Inject event should exist after migration");
                assertEquals("new-session", rs.getString("session_id"));
                assertEquals("git", rs.getString("unit_id"));
            }

            // Verify old data survived migration
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM usage_events")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1)); // old search + new inject
            }
        }
    }

    @Test
    void logInject_tokenEstimateMinimumIsOne() throws Exception {
        UsageLogger.logInject("s4", "/tmp/x", "curl", 0);
        Thread.sleep(500);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT token_estimate FROM usage_events WHERE event_type = 'inject'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("token_estimate")); // Math.max(1, 0/4)
        }
    }

    /**
     * Reset the private volatile 'initialized' flag via reflection so each test
     * starts with a clean schema state.
     */
    private void resetInitialized() throws Exception {
        Field f = UsageLogger.class.getDeclaredField("initialized");
        f.setAccessible(true);
        f.set(null, false);
    }
}
