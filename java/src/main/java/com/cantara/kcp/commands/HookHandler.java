package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.CommandFlag;
import com.cantara.kcp.commands.model.CommandManifest;
import com.cantara.kcp.commands.model.ParsedCommand;
import com.cantara.kcp.commands.model.PreferredInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP handler for POST /hook
 *
 * Receives the Claude Code PreToolUse hook JSON, processes it through the
 * manifest resolver, and returns a hookSpecificOutput response.
 *
 * On exit 0 (success) with JSON output, Claude Code injects additionalContext
 * into Claude's context before the tool runs, and optionally modifies the
 * command via updatedInput (Phase B filtering).
 */
public class HookHandler implements HttpHandler {

    private final ManifestResolver resolver;
    private final ManifestGenerator generator;
    private final ObjectMapper mapper;
    private final String filterScriptPath;

    public HookHandler(ManifestResolver resolver, ManifestGenerator generator,
                       String filterScriptPath) {
        this.resolver = resolver;
        this.generator = generator;
        this.mapper = new ObjectMapper();
        this.filterScriptPath = filterScriptPath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        byte[] body = exchange.getRequestBody().readAllBytes();
        JsonNode input;
        try {
            input = mapper.readTree(body);
        } catch (Exception e) {
            sendEmpty(exchange);
            return;
        }

        // Only intercept Bash tool calls
        if (!"Bash".equals(input.path("tool_name").asText())) {
            sendEmpty(exchange);
            return;
        }

        String command = input.path("tool_input").path("command").asText();
        if (command.isBlank()) {
            sendEmpty(exchange);
            return;
        }

        ParsedCommand parsed = CommandParser.parse(command);
        if (parsed == null) {
            sendEmpty(exchange);
            return;
        }

        CommandManifest manifest = resolver.resolve(parsed.key());
        if (manifest == null) {
            manifest = generator.generate(parsed.cmd(), parsed.subcommand());
        }
        if (manifest == null) {
            sendEmpty(exchange);
            return;
        }

        sendResponse(exchange, buildResponse(manifest, command, parsed));
    }

    private ObjectNode buildResponse(CommandManifest manifest, String command, ParsedCommand parsed) {
        String additionalContext = buildAdditionalContext(manifest);

        ObjectNode hookOutput = mapper.createObjectNode();
        hookOutput.put("hookEventName", "PreToolUse");
        hookOutput.put("permissionDecision", "allow");
        hookOutput.put("additionalContext", additionalContext);

        // Phase B: pipe through Node.js filter for large-output commands
        var schema = manifest.outputSchema();
        if (schema != null && schema.enableFilter() && CommandParser.isFilterable(command)) {
            String wrapped = command + " | node \"" + filterScriptPath + "\" filter \"" + parsed.key() + "\"";
            ObjectNode updatedInput = mapper.createObjectNode();
            updatedInput.put("command", wrapped);
            hookOutput.set("updatedInput", updatedInput);
        }

        ObjectNode response = mapper.createObjectNode();
        response.set("hookSpecificOutput", hookOutput);
        return response;
    }

    private String buildAdditionalContext(CommandManifest manifest) {
        StringBuilder sb = new StringBuilder();

        String name = manifest.subcommand() != null
                ? manifest.command() + " " + manifest.subcommand()
                : manifest.command();

        sb.append("[kcp] ").append(name).append(": ").append(manifest.description()).append('\n');

        if (manifest.usage() != null) {
            sb.append("Usage: ").append(manifest.usage()).append('\n');
        }

        var flags = manifest.keyFlags();
        if (!flags.isEmpty()) {
            sb.append("Key flags:\n");
            flags.stream().limit(5).forEach(f -> {
                sb.append("  ").append(f.flag()).append(": ").append(f.description());
                if (f.useWhen() != null) sb.append("  → ").append(f.useWhen());
                sb.append('\n');
            });
        }

        var prefs = manifest.preferredInvocations();
        if (!prefs.isEmpty()) {
            sb.append("Prefer:\n");
            prefs.stream().limit(3).forEach(p ->
                    sb.append("  ").append(p.invocation())
                            .append("  # ").append(p.useWhen()).append('\n'));
        }

        if (manifest.generated()) {
            sb.append("(auto-generated manifest — improve it at ~/.kcp/commands/)\n");
        }

        return sb.toString().trim();
    }

    /** Send a 204 No Content — tells the hook client to let the tool run unchanged. */
    private void sendEmpty(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void sendResponse(HttpExchange exchange, ObjectNode response) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
