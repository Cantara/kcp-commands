package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.CommandManifest;
import com.cantara.kcp.commands.model.NoisePattern;
import com.cantara.kcp.commands.model.OutputSchema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * HTTP handler for POST /filter/{key}
 *
 * Accepts raw command output in the request body, applies the manifest's
 * noise filter and truncation, runs deviation detection, and returns the
 * filtered text. Equivalent to running:
 *   cmd | node cli.js filter <key>
 *
 * When the daemon is running, HookHandler wraps Phase B commands as:
 *   cmd | curl -s -X POST "http://localhost:{port}/filter/{key}" --data-binary @-
 *
 * This removes the Node.js dependency for Phase B when the Java daemon is active.
 */
public class FilterHandler implements HttpHandler {

    private static final String PREFIX = "/filter/";

    private final ManifestResolver resolver;

    public FilterHandler(ManifestResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        // Extract key from URI path: /filter/<key>
        String path = exchange.getRequestURI().getPath();
        String key = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
        if (key.isBlank()) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        String rawInput = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        CommandManifest manifest = resolver.resolve(key);
        String result = (manifest != null && manifest.outputSchema() != null)
                ? applyFilter(key, rawInput, manifest)
                : rawInput;

        sendText(exchange, result);
    }

    /**
     * Apply noise filtering + truncation from the manifest's output_schema.
     * Mirrors the logic in typescript/src/filter.ts.
     */
    private String applyFilter(String key, String rawInput, CommandManifest manifest) {
        OutputSchema schema = manifest.outputSchema();
        int maxLines = schema.maxLines() > 0 ? schema.maxLines() : Integer.MAX_VALUE;

        String[] lines = rawInput.split("\n", -1);

        // Count non-blank raw lines (before filtering) for deviation stats
        int rawLineCount = 0;
        for (String line : lines) {
            if (!line.isBlank()) rawLineCount++;
        }

        // Build compiled patterns with hit counters
        List<NoisePattern> noisePatternDefs =
                schema.noisePatterns() != null ? schema.noisePatterns() : List.of();
        Pattern[] compiled = new Pattern[noisePatternDefs.size()];
        int[] hits = new int[noisePatternDefs.size()];
        for (int i = 0; i < noisePatternDefs.size(); i++) {
            compiled[i] = Pattern.compile(noisePatternDefs.get(i).pattern());
        }

        // Filter: strip blank lines and lines matched by any noise pattern
        List<String> filtered = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (line.isBlank()) continue;
            boolean matched = false;
            for (int i = 0; i < compiled.length; i++) {
                if (compiled[i].matcher(line).find()) {
                    hits[i]++;
                    matched = true;
                    break;
                }
            }
            if (!matched) filtered.add(line);
        }

        boolean wasTruncated = filtered.size() > maxLines;
        List<String> output = wasTruncated ? filtered.subList(0, maxLines) : filtered;
        int remaining = filtered.size() - output.size();

        StringBuilder sb = new StringBuilder(String.join("\n", output));
        if (remaining > 0) {
            String msg = (schema.truncationMessage() != null
                    ? schema.truncationMessage()
                    : "... {remaining} more lines.")
                    .replace("{remaining}", String.valueOf(remaining));
            sb.append('\n').append(msg);
        }
        sb.append('\n');

        // Build per-pattern hit map for deviation detection
        Map<String, Integer> patternHits = new LinkedHashMap<>();
        for (int i = 0; i < noisePatternDefs.size(); i++) {
            patternHits.put(noisePatternDefs.get(i).pattern(), hits[i]);
        }

        // Deviation detection — non-blocking, never throws
        DeviationDetector.FilterStats stats = new DeviationDetector.FilterStats(
                key, rawLineCount, filtered.size(), wasTruncated, patternHits, manifest);
        try {
            DeviationDetector.check(stats);
        } catch (Exception ignored) {}

        return sb.toString();
    }

    private void sendText(HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }
}
