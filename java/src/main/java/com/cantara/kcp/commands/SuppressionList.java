package com.cantara.kcp.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Built-in suppression list for well-known commands.
 *
 * When a command's base name is in the suppression list, the daemon returns
 * empty output immediately — skipping manifest lookup and auto-generation.
 * Suppression is unconditional: even if a yaml file exists for the command,
 * suppression wins. This is intentional — kcp-commands ships manifests for many
 * common commands as reference, but capable agents don't need them injected.
 *
 * This eliminates 5-8K tokens/session of noise from manifests for commands that
 * capable agents already know (git, ls, grep, etc.).
 *
 * Configuration:
 * - Default list is built-in (see {@link #DEFAULT_SUPPRESSED})
 * - Override via ~/.kcp/suppress.txt (one prefix per line, # comments, replaces default)
 * - To get a manifest for a suppressed command, remove it from suppress.txt
 *   (or create a custom suppress.txt that omits that command)
 */
public class SuppressionList {

    /**
     * Default suppressed commands — well-known to capable agents, manifests add zero value.
     */
    static final Set<String> DEFAULT_SUPPRESSED = Set.of(
            // Version control
            "git", "gh",
            // Text processing
            "ls", "cat", "head", "tail", "grep", "sed", "awk", "find",
            "echo", "printf", "wc", "sort", "uniq", "cut", "tr", "xargs",
            // Filesystem
            "cd", "pwd", "mkdir", "rm", "rmdir", "mv", "cp", "touch",
            "chmod", "chown", "ln",
            // Network
            "curl", "wget", "ssh", "scp", "rsync",
            // System
            "ps", "kill", "top", "df", "du", "uname",
            // Runtimes (bare invocations)
            "python3", "node", "java",
            // Shell builtins
            "which", "type", "command", "env", "export", "source", "eval"
    );

    private final Set<String> suppressed;

    public SuppressionList(Path kcpDir) {
        this.suppressed = loadSuppressionList(kcpDir);
    }

    /**
     * Check if a command should be suppressed (skip manifest lookup).
     *
     * Suppression is unconditional — if the base command is in the suppression list,
     * no manifest is injected regardless of whether a yaml file exists. Shipped manifests
     * for well-known commands (git, ls, grep, etc.) are reference material, not intended
     * for injection. To unsuppress a command, remove it from the suppression list by
     * providing a custom ~/.kcp/suppress.txt.
     *
     * @param cmd        base command (e.g. "git", "ls")
     * @param subcommand optional subcommand (e.g. "log", "status"), may be null
     * @return true if the command should be suppressed
     */
    public boolean isSuppressed(String cmd, String subcommand) {
        if (cmd == null || cmd.isEmpty()) {
            return false;
        }
        return suppressed.contains(cmd);
    }

    /**
     * Load suppression list from ~/.kcp/suppress.txt if it exists,
     * otherwise return the default built-in list.
     */
    private static Set<String> loadSuppressionList(Path kcpDir) {
        Path suppressFile = kcpDir.resolve("suppress.txt");
        if (!Files.exists(suppressFile)) {
            return DEFAULT_SUPPRESSED;
        }

        try {
            return Files.readAllLines(suppressFile).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            System.err.println("[kcp-commands] Warning: could not read " + suppressFile + ": " + e.getMessage());
            return DEFAULT_SUPPRESSED;
        }
    }
}
