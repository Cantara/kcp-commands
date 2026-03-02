package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.ParsedCommand;

import java.util.Set;

/**
 * Parses a shell command string into a (cmd, subcommand, key) triple
 * for manifest lookup.
 */
public class CommandParser {

    private static final Set<String> GIT_SUBCOMMANDS =
            Set.of("log", "diff", "status", "branch", "show", "blame", "stash");

    private static final Set<String> DOCKER_SUBCOMMANDS =
            Set.of("ps", "logs", "exec", "run", "images", "inspect");

    /**
     * Parse the base command from a shell string.
     * Returns null for complex expressions (subshells, etc.) that should pass through unchanged.
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

        // Compound commands: git <subcommand>, docker <subcommand>
        if (cmd.equals("git") && parts.length > 1 && GIT_SUBCOMMANDS.contains(parts[1])) {
            String sub = parts[1];
            return new ParsedCommand("git-" + sub, "git", sub);
        }
        if (cmd.equals("docker") && parts.length > 1 && DOCKER_SUBCOMMANDS.contains(parts[1])) {
            String sub = parts[1];
            return new ParsedCommand("docker-" + sub, "docker", sub);
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
