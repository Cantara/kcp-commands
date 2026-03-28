package com.cantara.kcp.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * kcp-commands daemon — persistent HTTP server on localhost:7734.
 *
 * Serves Claude Code PreToolUse hook requests. Stays alive across many
 * tool calls, eliminating JVM startup overhead compared to a per-call
 * process.
 *
 * Endpoints:
 *   POST /hook       — Phase A: resolve manifest, inject additionalContext + wrap Phase B command
 *   POST /filter/{k} — Phase B: apply noise filter + deviation detection; returns filtered text
 *   GET  /health     — liveness probe (returns "ok")
 *   GET  /version    — current version + update availability (reads from shared cache)
 *
 * Usage:
 *   java -jar kcp-commands-daemon.jar [--port 7734]
 *   java -jar kcp-commands-daemon.jar --check-update
 *   java -jar kcp-commands-daemon.jar --update
 */
public class KcpCommandsDaemon {

    static final int    DEFAULT_PORT = 7734;
    public static final String VERSION = "0.21.0";

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--update"        -> { runUpdate(true);  return; }
                case "--check-update"  -> { runUpdate(false); return; }
                case "--port" -> {
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                }
            }
        }

        startDaemon(port);
    }

    // ------------------------------------------------------------------
    // Daemon
    // ------------------------------------------------------------------

    private static void startDaemon(int port) throws IOException, InterruptedException {
        var resolver      = new ManifestResolver();
        var generator     = new ManifestGenerator();
        var hookHandler   = new HookHandler(resolver, generator, port);
        var filterHandler = new FilterHandler(resolver);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 16);
        server.createContext("/hook",    hookHandler);
        server.createContext("/filter/", filterHandler);
        server.createContext("/health",  exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/version", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String latestCmd     = "";
            boolean updateAvail  = false;
            try {
                Path cache = Path.of(System.getProperty("user.home"), ".kcp", "last-update-check");
                if (Files.exists(cache)) {
                    var cached = new ObjectMapper().readTree(cache.toFile());
                    latestCmd   = cached.path("latestCommands").asText("");
                    updateAvail = !latestCmd.isEmpty() && !latestCmd.equals(VERSION);
                }
            } catch (Exception ignored) {}
            String json = String.format(
                    "{\"version\":\"%s\",\"latest\":\"%s\",\"updateAvailable\":%b}",
                    VERSION, latestCmd, updateAvail);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.err.printf("[kcp-commands] v%s — daemon started on localhost:%d%n", VERSION, port);
        System.err.printf("[kcp-commands] Endpoints: /hook  /filter/{key}  /health  /version%n");
        System.err.printf("[kcp-commands] To update: java -jar ~/.kcp/kcp-commands-daemon.jar --update%n");

        // Startup update check — non-blocking, once per 24h
        Thread.ofVirtual().start(() -> {
            try {
                String currentMem = readCachedMemVersion();
                UpdateChecker.Versions v = new UpdateChecker().checkIfDue(VERSION, currentMem);
                if (v.commandsOutdated())
                    System.err.printf("[kcp-commands] Update available: %s → %s  (run --update)%n",
                            VERSION, v.latestCommands());
                if (v.memoryOutdated())
                    System.err.printf("[kcp-commands] kcp-memory update available: %s → %s%n",
                            v.currentMemory(), v.latestMemory());
            } catch (Exception e) {
                System.err.println("[kcp-commands] Update check failed: " + e.getMessage());
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[kcp-commands] Shutting down...");
            server.stop(1);
        }));

        Thread.currentThread().join();
    }

    // ------------------------------------------------------------------
    // Update mode — runs before daemon starts
    // ------------------------------------------------------------------

    private static void runUpdate(boolean doDownload) throws IOException, InterruptedException {
        UpdateChecker checker = new UpdateChecker();
        String currentMem = readCachedMemVersion();

        System.out.println("Checking for updates...");
        UpdateChecker.Versions v = checker.checkNow(VERSION, currentMem);

        System.out.printf("  kcp-commands  installed: %-10s  latest: %-10s  %s%n",
                v.currentCommands(), v.latestCommands(),
                v.commandsOutdated() ? "<-- UPDATE AVAILABLE" : "up to date");
        System.out.printf("  kcp-memory    installed: %-10s  latest: %-10s  %s%n",
                v.currentMemory(), v.latestMemory(),
                v.memoryOutdated() ? "<-- UPDATE AVAILABLE" : "up to date");
        System.out.println();

        if (!v.anyOutdated()) {
            System.out.println("Everything up to date.");
            return;
        }
        if (!doDownload) {
            System.exit(1); // non-zero = update available (scriptable)
        }

        System.out.print("Download and install? [y/N] ");
        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
            System.out.println("Cancelled.");
            return;
        }

        Path kcpDir = Path.of(System.getProperty("user.home"), ".kcp");

        if (v.commandsOutdated()) {
            System.out.printf("Downloading kcp-commands-daemon.jar v%s...%n", v.latestCommands());
            checker.downloadJar(UpdateChecker.CMD_REPO, UpdateChecker.CMD_JAR);
            System.out.println("  done.");
        }
        if (v.memoryOutdated() && Files.exists(kcpDir.resolve(UpdateChecker.MEM_JAR))) {
            System.out.printf("Downloading kcp-memory-daemon.jar v%s...%n", v.latestMemory());
            checker.downloadJar(UpdateChecker.MEM_REPO, UpdateChecker.MEM_JAR);
            System.out.println("  done.");
        }

        System.out.println("\nUpdated. Restart daemons to apply:");
        System.out.println("  pkill -f kcp-commands-daemon; nohup java -jar ~/.kcp/kcp-commands-daemon.jar > /tmp/kcp-commands.log 2>&1 &");
        if (v.memoryOutdated() && Files.exists(kcpDir.resolve(UpdateChecker.MEM_JAR)))
            System.out.println("  pkill -f 'kcp-memory.*daemon'; nohup java -jar ~/.kcp/kcp-memory-daemon.jar daemon > /tmp/kcp-memory-daemon.log 2>&1 &");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Best-effort read of kcp-memory's version from the shared cache. */
    private static String readCachedMemVersion() {
        try {
            Path cache = Path.of(System.getProperty("user.home"), ".kcp", "last-update-check");
            if (Files.exists(cache))
                return new ObjectMapper().readTree(cache.toFile()).path("latestMemory").asText("unknown");
        } catch (Exception ignored) {}
        return "unknown";
    }
}
