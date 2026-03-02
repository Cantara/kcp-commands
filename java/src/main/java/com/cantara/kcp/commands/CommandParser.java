package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.ParsedCommand;

import java.util.regex.Pattern;

/**
 * Parses a shell command string into a (cmd, subcommand, key) triple
 * for manifest lookup.
 */
public class CommandParser {

    /**
     * Pattern for a word that looks like a subcommand: lowercase letters, digits, hyphens.
     * Matches "log", "diff", "get", "ps", "images", etc.
     * Rejects flags (--flag), paths (/usr/bin), filenames (foo.yaml).
     */
    private static final Pattern SUBCOMMAND_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    /**
     * Parse the base command from a shell string.
     * Returns null for complex expressions (subshells, etc.) that should pass through unchanged.
     *
     * For commands with a subcommand-shaped first argument (e.g. "git log", "kubectl get"),
     * returns a compound key ("git-log", "kubectl-get") with the subcommand field set.
     * The caller should fall back to the simple key if no compound manifest exists.
     */
    public static ParsedCommand parse(String shellCommand) {
        if (shellCommand == null || shellCommand.isBlank()) return null;

        // Skip subshells and backtick expressions — too complex to intercept safely
        if (shellCommand.contains("$(") || shellCommand.contains("`")) return null;

        // Take only the first pipeline segment
        String firstSegment = shellCommand.split("[|;&]+")[0].trim();

        // Strip leading env var assignments (FOO=bar cmd) and sudo
        String stripped = firstSegment
                .replaceAll("^(?:\\w+=\\S+\\s+)*(?:sudo\\s+)?", "")
                .trim();

        String[] parts = stripped.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) return null;

        String cmd = parts[0];

        // Compound commands: any command whose first arg looks like a subcommand word.
        // Examples: git log, docker ps, kubectl get, npm install
        if (parts.length > 1 && SUBCOMMAND_PATTERN.matcher(parts[1]).matches()) {
            String sub = parts[1];
            return new ParsedCommand(cmd + "-" + sub, cmd, sub);
        }

        return new ParsedCommand(cmd, cmd, null);
    }

    /**
     * True if the command is safe to pipe through a filter.
     * Avoids wrapping commands that write to files or use complex redirection.
     */
    public static boolean isFilterable(String command) {
        return !command.contains(">") && !command.contains("<") && !command.contains("exec");
    }
}
