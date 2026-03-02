package com.cantara.kcp.commands;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 *
 * Usage:
 *   java -jar kcp-commands-daemon.jar [--port 7734]
 */
public class KcpCommandsDaemon {

    static final int DEFAULT_PORT = 7734;

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = DEFAULT_PORT;

        // Parse args
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) port = Integer.parseInt(args[i + 1]);
        }

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

        // Virtual threads: lightweight concurrency for concurrent Claude sessions
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.err.printf("[kcp-commands] Daemon started on localhost:%d%n", port);
        System.err.printf("[kcp-commands] Endpoints: /hook  /filter/{key}  /health%n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[kcp-commands] Shutting down...");
            server.stop(1);
        }));

        // Block main thread — virtual thread executor keeps the server alive
        Thread.currentThread().join();
    }

}

