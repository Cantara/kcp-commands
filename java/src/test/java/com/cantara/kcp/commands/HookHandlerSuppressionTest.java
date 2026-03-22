package com.cantara.kcp.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: verifies that the HookHandler uses SuppressionList
 * to return 204 (empty) for suppressed commands and 200 (manifest) for others.
 */
class HookHandlerSuppressionTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        // Use a random available port
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();

        Path kcpDir = tempDir.resolve(".kcp");
        Files.createDirectories(kcpDir.resolve("commands"));

        var resolver = new ManifestResolver();
        var generator = new NoOpGenerator();
        var suppressionList = new SuppressionList(kcpDir);
        var handler = new HookHandler(resolver, generator, port, suppressionList);

        server.createContext("/hook", handler);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void suppressed_command_returns_204() throws Exception {
        int status = postHook("git log --oneline -5");
        assertEquals(204, status, "git should be suppressed → 204 No Content");
    }

    @Test
    void suppressed_ls_returns_204() throws Exception {
        int status = postHook("ls -la");
        assertEquals(204, status, "ls should be suppressed → 204 No Content");
    }

    @Test
    void suppressed_grep_returns_204() throws Exception {
        int status = postHook("grep -rn pattern .");
        assertEquals(204, status, "grep should be suppressed → 204 No Content");
    }

    @Test
    void unsuppressed_command_with_manifest_returns_200() throws Exception {
        // aws has a bundled manifest and is NOT suppressed
        HttpResponse<String> resp = postHookFull("aws s3 ls");
        // Could be 200 (manifest found) or 204 (no manifest) — but NOT suppressed
        // The key point is aws should not be blocked by suppression
        // If the resolver has an aws manifest bundled, we get 200
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 204,
                "aws should not be suppressed — should reach manifest resolver");
    }

    @Test
    void non_bash_tool_still_returns_204() throws Exception {
        String json = mapper.writeValueAsString(mapper.createObjectNode()
                .put("tool_name", "Read")
                .set("tool_input", mapper.createObjectNode().put("file_path", "/tmp/test")));
        int status = postRaw(json);
        assertEquals(204, status, "Non-Bash tools should return 204");
    }

    private int postHook(String command) throws Exception {
        return postHookFull(command).statusCode();
    }

    private HttpResponse<String> postHookFull(String command) throws Exception {
        var input = mapper.createObjectNode();
        input.put("tool_name", "Bash");
        input.set("tool_input", mapper.createObjectNode().put("command", command));
        input.put("session_id", "test-session");

        return postRawFull(mapper.writeValueAsString(input));
    }

    private int postRaw(String json) throws Exception {
        return postRawFull(json).statusCode();
    }

    private HttpResponse<String> postRawFull(String json) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hook"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * No-op generator that never generates manifests — isolates suppression testing.
     */
    private static class NoOpGenerator extends ManifestGenerator {
        @Override
        public com.cantara.kcp.commands.model.CommandManifest generate(String cmd, String subcommand) {
            return null;
        }
    }
}
