package com.cantara.kcp.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends tool-call events to ~/.kcp/events.jsonl in JSONL format.
 * <p>
 * Written by kcp-commands on every Phase A Bash hook call.
 * Consumed by kcp-memory v0.2.0 to build a tool-level episodic index.
 * <p>
 * Design constraints:
 * - Always non-blocking: spawns a virtual thread, never blocks the hook response
 * - Never throws: all errors are silently swallowed
 * - File-safe: uses a process-wide lock to prevent interleaved JSONL lines
 */
public class EventLogger {

    private static final Path   EVENTS_FILE        = Path.of(System.getProperty("user.home") + "/.kcp/events.jsonl");
    private static final int    MAX_COMMAND_LENGTH  = 500;
    private static final ObjectMapper MAPPER        = new ObjectMapper();
    private static final ReentrantLock WRITE_LOCK   = new ReentrantLock();

    /**
     * Log a Bash tool-call event asynchronously.
     * Returns immediately; the write happens on a virtual thread.
     *
     * @param sessionId   Claude Code session UUID (may be empty if not present in hook input)
     * @param projectDir  Working directory at time of the tool call (cwd)
     * @param command     Raw Bash command (truncated to 500 chars)
     * @param manifestKey kcp-commands manifest key that was resolved, or null if none
     */
    public static void log(String sessionId, String projectDir, String command, String manifestKey) {
        Thread.ofVirtual().start(() -> {
            try {
                Files.createDirectories(EVENTS_FILE.getParent());

                ObjectNode event = MAPPER.createObjectNode();
                event.put("ts",          Instant.now().toString());
                event.put("session_id",  sessionId  != null ? sessionId  : "");
                event.put("project_dir", projectDir != null ? projectDir : "");
                event.put("tool",        "Bash");

                String cmd = command != null ? command : "";
                if (cmd.length() > MAX_COMMAND_LENGTH) cmd = cmd.substring(0, MAX_COMMAND_LENGTH);
                event.put("command", cmd);

                if (manifestKey != null) event.put("manifest_key", manifestKey);
                else                     event.putNull("manifest_key");

                byte[] line = (MAPPER.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);

                WRITE_LOCK.lock();
                try (FileOutputStream fos = new FileOutputStream(EVENTS_FILE.toFile(), true)) {
                    fos.write(line);
                } finally {
                    WRITE_LOCK.unlock();
                }
            } catch (IOException ignored) {
                // Never propagate — event logging must never break the hook
            }
        });
    }
}
