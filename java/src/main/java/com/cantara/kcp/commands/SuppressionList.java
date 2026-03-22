package com.cantara.kcp.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * Built-in suppression list for well-known commands.
 *
 * When a command's base name is in the suppression list and has no user-provided
 * manifest (yaml file in ~/.kcp/commands/ or .kcp/commands/), the daemon returns
 * empty output immediately — skipping manifest lookup and auto-generation.
 *
 * This eliminates 5-8K tokens/session of noise from manifests for commands that
 * capable agents already know (git, ls, grep, etc.).
 *
 * Configuration:
 * - Default list is built-in (see {@link #DEFAULT_SUPPRESSED})
 * - Override via ~/.kcp/suppress.txt (one prefix per line, # comments, replaces default)
 * - If a suppressed command has a yaml file in ~/.kcp/commands/ or .kcp/commands/,
 *   the manifest wins (user explicitly opted in)
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
    private final Path userCommandsDir;
    private final Path projectCommandsDir;

    /**
     * Create a suppression list using the given kcp directory (~/.kcp) and
     * the current working directory for project-local manifest lookups.
     */
    public SuppressionList(Path kcpDir) {
        this(kcpDir, Path.of(System.getProperty("user.dir")));
    }

    /**
     * Create a suppression list with explicit project root for testing.
     *
     * @param kcpDir      path to ~/.kcp (contains suppress.txt and commands/)
     * @param projectRoot path to project root (contains .kcp/commands/)
     */
    public SuppressionList(Path kcpDir, Path projectRoot) {
        this.userCommandsDir = kcpDir.resolve("commands");
        this.projectCommandsDir = projectRoot.resolve(".kcp").resolve("commands");
        this.suppressed = loadSuppressionList(kcpDir);
    }

    /**
     * Check if a command should be suppressed (skip manifest lookup).
     *
     * A command is suppressed when:
     * 1. Its base command is in the suppression list, AND
     * 2. No user-provided manifest yaml exists for it
     *
     * @param cmd        base command (e.g. "git", "ls")
     * @param subcommand optional subcommand (e.g. "log", "status"), may be null
     * @return true if the command should be suppressed
     */
    public boolean isSuppressed(String cmd, String subcommand) {
        if (cmd == null || cmd.isEmpty()) {
            return false;
        }

        if (!suppressed.contains(cmd)) {
            return false;
        }

        // Check if user has explicitly provided a manifest — manifest wins over suppression
        if (subcommand != null) {
            String compoundKey = cmd + "-" + subcommand;
            if (hasUserManifest(compoundKey)) {
                return false;
            }
        }
        if (hasUserManifest(cmd)) {
            return false;
        }

        return true;
    }

    /**
     * Check if a yaml manifest exists in user-level or project-local commands directory.
     */
    private boolean hasUserManifest(String key) {
        String filename = key + ".yaml";

        // Project-local
        Path projectPath = projectCommandsDir.resolve(filename);
        if (Files.exists(projectPath)) {
            return true;
        }

        // User-level
        Path userPath = userCommandsDir.resolve(filename);
        return Files.exists(userPath);
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
