package com.cantara.kcp.commands;

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
 * Integration tests for the filter-only path for suppressed commands.
 *
 * Suppressed commands with enableFilter=true get Phase B output filtering
 * (updatedInput wrapped with curl pipe) but NO Phase A context injection.
 * Suppressed commands with enableFilter=false (or no manifest) still get 204.
 */
class HookHandlerFilterOnlyTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
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
    void suppressed_find_with_filter_returns_200_with_pipe() throws Exception {
        // find has enableFilter: true
        var resp = post("find . -name '*.java'");
        assertEquals(200, resp.statusCode());
        var hookOut = mapper.readTree(resp.body()).path("hookSpecificOutput");
        assertTrue(hookOut.path("updatedInput").path("command").asText().contains("/filter/find"),
                "find should be piped through /filter/find");
        assertTrue(hookOut.path("additionalContext").isMissingNode() || hookOut.path("additionalContext").isNull(),
                "no context should be injected for filter-only path");
    }

    @Test
    void suppressed_command_with_redirect_skips_filter() throws Exception {
        // Commands with > redirect are not filterable — should stay 204
        int status = postStatus("find . -name '*.java' > output.txt");
        assertEquals(204, status, "redirect makes command non-filterable → 204");
    }

    @Test
    void suppressed_ssh_no_filter_returns_204() throws Exception {
        // ssh is suppressed and has no enableFilter manifest
        int status = postStatus("ssh user@host ls");
        assertEquals(204, status, "ssh has no filter manifest → pure suppression");
    }

    @Test
    void suppressed_curl_no_filter_returns_204() throws Exception {
        // curl is suppressed and its manifest has enableFilter: false
        int status = postStatus("curl -s https://example.com");
        assertEquals(204, status, "curl is suppressed with no filter → 204");
    }

    @Test
    void filter_only_response_has_allow_permission() throws Exception {
        var resp = post("grep -rn TODO .");
        assertEquals(200, resp.statusCode());
        var hookOut = mapper.readTree(resp.body()).path("hookSpecificOutput");
        assertEquals("allow", hookOut.path("permissionDecision").asText(),
                "filter-only response must set permissionDecision=allow");
    }

    @Test
    void filter_only_pipe_uses_correct_port() throws Exception {
        var resp = post("find /src -name '*.go'");
        if (resp.statusCode() == 200) {
            var cmd = mapper.readTree(resp.body())
                    .path("hookSpecificOutput")
                    .path("updatedInput")
                    .path("command").asText();
            assertTrue(cmd.contains("localhost:" + port),
                    "filter URL should use the daemon's own port");
        }
    }

    private HttpResponse<String> post(String command) throws Exception {
        var input = mapper.createObjectNode();
        input.put("tool_name", "Bash");
        input.set("tool_input", mapper.createObjectNode().put("command", command));
        input.put("session_id", "test-filter-session");
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hook"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input)))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private int postStatus(String command) throws Exception {
        return post(command).statusCode();
    }

    private static class NoOpGenerator extends ManifestGenerator {
        @Override
        public com.cantara.kcp.commands.model.CommandManifest generate(String cmd, String subcommand) {
            return null;
        }
    }
}
