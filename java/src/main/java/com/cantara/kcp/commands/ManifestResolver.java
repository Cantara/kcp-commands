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
     *
     * Keys are read from commands/index.txt (one key per line, # comments allowed).
     * Add a line to index.txt whenever a new manifest is added to commands/.
     */
    private void preloadPrimed() {
        InputStream idx = getClass().getClassLoader().getResourceAsStream("commands/index.txt");
        if (idx == null) {
            System.err.println("[kcp-commands] Warning: commands/index.txt not found in classpath");
            return;
        }

        int loaded = 0;
        try (java.util.Scanner scanner = new java.util.Scanner(idx, java.nio.charset.StandardCharsets.UTF_8)) {
            while (scanner.hasNextLine()) {
                String key = scanner.nextLine().trim();
                if (key.isEmpty() || key.startsWith("#")) continue;

                String resource = "commands/" + key + ".yaml";
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
                    if (is != null) {
                        primedCache.put(key, ManifestParser.parse(is));
                        loaded++;
                    } else {
                        System.err.println("[kcp-commands] Warning: manifest not found for key '" + key + "'");
                    }
                } catch (IOException | IllegalArgumentException e) {
                    System.err.println("[kcp-commands] Warning: could not load primed manifest " + key + ": " + e.getMessage());
                }
            }
        }
        System.err.println("[kcp-commands] Loaded " + loaded + " primed manifests");
    }
}
