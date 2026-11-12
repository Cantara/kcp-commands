package com.cantara.kcp.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * The hook must never make Claude Code's permission decision.
 *
 * A PreToolUse reply with permissionDecision "allow" skips Claude Code's permission prompt for the
 * whole Bash command. The command parser only looks at the first segment, so "jq . f; rm -rf x"
 * was approved because jq has a manifest. The hook now only adds context and rewrites the command
 * for filtering; whether the command may run is left to the user's permission rules.
 *
 * The filter rewrite pipes into a dedicated kcp-filter command rather than curl, so a user can allow
 * exactly that command (Bash(~/.kcp/kcp-filter:*)) and nothing else.
 */
class HookHandlerPermissionTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private String defaultFilter;

    @BeforeEach
    void setUp() throws Exception {
        Path kcpDir = tempDir.resolve(".kcp");
        Files.createDirectories(kcpDir.resolve("commands"));
        defaultFilter = TestFilters.installed(kcpDir);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/hook", new HookHandler(new ManifestResolver(), new NoOpGenerator(), port,
                new SuppressionList(kcpDir), defaultFilter));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void compound_command_is_not_approved() throws Exception {
        JsonNode out = hookOutput("jq . data.json; rm -rf /tmp/kcp-test-does-not-exist");
        assertTrue(out.path("permissionDecision").isMissingNode(),
                "the hook must not decide permission; got " + out.path("permissionDecision"));
        assertTrue(out.path("additionalContext").asText().contains("jq"), "context is still added");
    }

    @Test
    void context_only_response_has_no_permission_decision() throws Exception {
        JsonNode out = hookOutput("jq . data.json");
        assertTrue(out.path("permissionDecision").isMissingNode());
        assertTrue(out.path("updatedInput").isMissingNode(), "jq has no filter, so the command is not rewritten");
    }

    @Test
    void filtered_command_pipes_into_kcp_filter_without_a_decision() throws Exception {
        JsonNode out = hookOutput("diff a.txt b.txt");
        assertTrue(out.path("permissionDecision").isMissingNode());
        String cmd = out.path("updatedInput").path("command").asText();
        assertEquals("diff a.txt b.txt | " + defaultFilter + " diff " + port, cmd);
        assertFalse(cmd.contains("curl"), "the rewrite must not need a broad curl permission");
    }

    @Test
    void filter_only_path_has_no_permission_decision() throws Exception {
        JsonNode out = hookOutput("find . -name '*.java'");
        assertTrue(out.path("permissionDecision").isMissingNode());
        assertEquals("find . -name '*.java' | " + defaultFilter + " find " + port,
                out.path("updatedInput").path("command").asText());
    }

    private JsonNode hookOutput(String command) throws Exception {
        var input = mapper.createObjectNode();
        input.put("tool_name", "Bash");
        input.set("tool_input", mapper.createObjectNode().put("command", command));
        input.put("session_id", "test-permission-session");
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hook"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input)))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "expected a hook reply for: " + command);
        return mapper.readTree(resp.body()).path("hookSpecificOutput");
    }

    private static class NoOpGenerator extends ManifestGenerator {
        @Override
        public com.cantara.kcp.commands.model.CommandManifest generate(String cmd, String subcommand) {
            return null;
        }
    }
}
