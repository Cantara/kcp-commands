package com.cantara.kcp.commands;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 *   POST /hook    — process a PreToolUse hook call, return enriched JSON
 *   GET  /health  — liveness probe (returns "ok")
 *
 * Usage:
 *   java -jar kcp-commands-daemon.jar [--port 7734] [--filter-script /path/to/cli.js]
 */
public class KcpCommandsDaemon {

    static final int DEFAULT_PORT = 7734;

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = DEFAULT_PORT;
        String filterScript = resolveFilterScript();

        // Parse args
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i]))          port = Integer.parseInt(args[i + 1]);
            if ("--filter-script".equals(args[i])) filterScript = args[i + 1];
        }

        var resolver  = new ManifestResolver();
        var generator = new ManifestGenerator();
        var handler   = new HookHandler(resolver, generator, filterScript);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 16);
        server.createContext("/hook",   handler);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        // Virtual threads: lightweight concurrency for concurrent Claude sessions
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.err.printf("[kcp-commands] Daemon started on localhost:%d%n", port);
        System.err.printf("[kcp-commands] Filter script: %s%n", filterScript);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[kcp-commands] Shutting down...");
            server.stop(1);
        }));

        // Block main thread — virtual thread executor keeps the server alive
        Thread.currentThread().join();
    }

    /**
     * Locate the Node.js filter script relative to the daemon JAR,
     * so the Phase B pipe command uses the correct absolute path.
     * Falls back to searching PATH via 'which kcp-commands'.
     */
    private static String resolveFilterScript() {
        // If running from the repo: java/target/ → ../../dist/cli.js
        try {
            Path jarDir = Path.of(KcpCommandsDaemon.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = jarDir.getParent().getParent().getParent().resolve("dist/cli.js");
            if (candidate.toFile().exists()) return candidate.toAbsolutePath().toString();
        } catch (Exception ignored) {}

        // Fallback: assume kcp-commands is on PATH
        return "kcp-commands";
    }
}
