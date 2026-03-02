package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.CommandManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves command manifests through the three-level lookup chain:
 *   1. .kcp/commands/<key>.yaml        — project-local override
 *   2. ~/.kcp/commands/<key>.yaml      — user-level (generated manifests saved here)
 *   3. classpath:commands/<key>.yaml   — primed/bundled manifests
 *
 * Primed manifests are cached in memory at startup. Filesystem manifests
 * are re-read on each resolve (picks up user edits without daemon restart).
 */
public class ManifestResolver {

    private final Path userDir;
    private final Map<String, CommandManifest> primedCache = new ConcurrentHashMap<>();

    public ManifestResolver() {
        this.userDir = Path.of(System.getProperty("user.home"), ".kcp", "commands");
        preloadPrimed();
    }

    /**
     * Resolve a manifest by key (e.g. "ls", "git-log", "docker-ps").
     * Returns null if no manifest found at any level.
     */
    public CommandManifest resolve(String key) {
        String filename = key + ".yaml";

        // 1. Project-local
        Path projectPath = Path.of(System.getProperty("user.dir"), ".kcp", "commands", filename);
        CommandManifest m = tryFile(projectPath);
        if (m != null) return m;

        // 2. User-level
        Path userPath = userDir.resolve(filename);
        m = tryFile(userPath);
        if (m != null) return m;

        // 3. Primed (classpath, pre-loaded at startup)
        return primedCache.get(key);
    }

    private CommandManifest tryFile(Path path) {
        if (!Files.exists(path)) return null;
        try (InputStream is = Files.newInputStream(path)) {
            return ManifestParser.parse(is);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[kcp-commands] Warning: could not parse " + path + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Pre-load all bundled manifests from the classpath into memory.
     * Called once at daemon startup — eliminates per-request classpath scanning.
     */
    private void preloadPrimed() {
        // Known primed manifest keys — extend this list as more are added
        String[] primedKeys = {
                "ls", "ps", "find",
                "git-log", "git-diff", "git-status", "git-branch",
                "docker-ps", "docker-logs",
                "mvn", "npm", "gradle"
        };

        int loaded = 0;
        for (String key : primedKeys) {
            String resource = "commands/" + key + ".yaml";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (is != null) {
                    primedCache.put(key, ManifestParser.parse(is));
                    loaded++;
                }
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("[kcp-commands] Warning: could not load primed manifest " + key + ": " + e.getMessage());
            }
        }
        System.err.println("[kcp-commands] Loaded " + loaded + " primed manifests");
    }
}
