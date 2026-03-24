package com.cantara.kcp.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Checks GitHub releases for kcp-commands and kcp-memory updates.
 * Rate-limited to once per 24h via ~/.kcp/last-update-check (shared with kcp-memory).
 */
public class UpdateChecker {

    private static final Logger   LOG      = Logger.getLogger(UpdateChecker.class.getName());
    static final         String   CMD_REPO = "Cantara/kcp-commands";
    static final         String   MEM_REPO = "Cantara/kcp-memory";
    static final         String   CMD_JAR  = "kcp-commands-daemon.jar";
    static final         String   MEM_JAR  = "kcp-memory-daemon.jar";
    private static final Path     KCP_DIR  = Path.of(System.getProperty("user.home"), ".kcp");
    private static final Path     CACHE    = KCP_DIR.resolve("last-update-check");
    private static final Duration INTERVAL = Duration.ofHours(24);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Versions(
        String currentCommands, String latestCommands,
        String currentMemory,   String latestMemory
    ) {
        boolean commandsOutdated() { return latestCommands != null && !latestCommands.equals(currentCommands); }
        boolean memoryOutdated()   { return latestMemory   != null && !latestMemory.equals(currentMemory); }
        boolean anyOutdated()      { return commandsOutdated() || memoryOutdated(); }
    }

    /** Check with 24h rate limit. Uses cached values if within window. */
    public Versions checkIfDue(String currentCmd, String currentMem) {
        try {
            if (Files.exists(CACHE)) {
                JsonNode c = mapper.readTree(CACHE.toFile());
                String at = c.path("checkedAt").asText("");
                if (!at.isEmpty() && Instant.now().isBefore(Instant.parse(at).plus(INTERVAL))) {
                    return new Versions(
                        currentCmd, c.path("latestCommands").asText(currentCmd),
                        currentMem, c.path("latestMemory").asText(currentMem)
                    );
                }
            }
        } catch (Exception e) {
            LOG.fine("Cache read failed: " + e.getMessage());
        }
        return checkNow(currentCmd, currentMem);
    }

    /** Force fresh GitHub API check, ignoring the 24h rate limit. */
    public Versions checkNow(String currentCmd, String currentMem) {
        String latestCmd = fetchLatestTag(CMD_REPO);
        String latestMem = fetchLatestTag(MEM_REPO);
        String resolvedCmd = latestCmd != null ? latestCmd : currentCmd;
        String resolvedMem = latestMem != null ? latestMem : currentMem;
        writeCache(resolvedCmd, resolvedMem);
        return new Versions(currentCmd, resolvedCmd, currentMem, resolvedMem);
    }

    /** Download a JAR to ~/.kcp/ using .tmp staging and .bak for rollback. */
    public void downloadJar(String repo, String jarName) throws IOException, InterruptedException {
        String url  = "https://github.com/" + repo + "/releases/latest/download/" + jarName;
        Path target = KCP_DIR.resolve(jarName);
        Path tmp    = KCP_DIR.resolve(jarName + ".tmp");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));

        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download failed: HTTP " + resp.statusCode());
        }
        // Validate: must be a JAR/ZIP (magic bytes PK)
        try (InputStream is = Files.newInputStream(tmp)) {
            byte[] hdr = is.readNBytes(4);
            if (hdr.length < 4 || hdr[0] != 'P' || hdr[1] != 'K') {
                Files.deleteIfExists(tmp);
                throw new IOException("Downloaded file is not a valid JAR");
            }
        }
        // Backup + atomic swap
        Path bak = KCP_DIR.resolve(jarName + ".bak");
        if (Files.exists(target)) Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Fetch latest release tag from GitHub. Returns bare version (no "v" prefix), or null on failure. */
    public String fetchLatestTag(String repo) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String tag = mapper.readTree(resp.body()).path("tag_name").asText("");
            return tag.startsWith("v") ? tag.substring(1) : tag;
        } catch (Exception e) {
            LOG.fine("GitHub API unreachable for " + repo + ": " + e.getMessage());
            return null;
        }
    }

    private void writeCache(String latestCmd, String latestMem) {
        try {
            Files.createDirectories(KCP_DIR);
            ObjectNode n = mapper.createObjectNode();
            n.put("checkedAt",      Instant.now().toString());
            n.put("latestCommands", latestCmd);
            n.put("latestMemory",   latestMem);
            mapper.writeValue(CACHE.toFile(), n);
        } catch (Exception e) {
            LOG.fine("Cache write failed: " + e.getMessage());
        }
    }
}
