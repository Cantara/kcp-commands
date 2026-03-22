package com.cantara.kcp.commands;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the command suppression list.
 *
 * Suppression skips manifest lookup for well-known commands (git, ls, grep, etc.)
 * that add token overhead with zero value to capable agents.
 */
class SuppressionListTest {

    @TempDir
    Path tempDir;

    private Path kcpDir;
    private Path commandsDir;

    @BeforeEach
    void setUp() throws IOException {
        kcpDir = tempDir.resolve(".kcp");
        commandsDir = kcpDir.resolve("commands");
        Files.createDirectories(commandsDir);
    }

    // ── Default suppression list ──────────────────────────────────────────────

    @Test
    void defaultList_suppresses_git() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("git", null));
    }

    @Test
    void defaultList_suppresses_git_subcommand() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("git", "log"));
        assertTrue(list.isSuppressed("git", "status"));
        assertTrue(list.isSuppressed("git", "diff"));
    }

    @Test
    void defaultList_suppresses_gh() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("gh", null));
        assertTrue(list.isSuppressed("gh", "issue"));
        assertTrue(list.isSuppressed("gh", "pr"));
    }

    @Test
    void defaultList_suppresses_coreutils() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("ls", null));
        assertTrue(list.isSuppressed("cat", null));
        assertTrue(list.isSuppressed("head", null));
        assertTrue(list.isSuppressed("tail", null));
        assertTrue(list.isSuppressed("grep", null));
        assertTrue(list.isSuppressed("sed", null));
        assertTrue(list.isSuppressed("awk", null));
        assertTrue(list.isSuppressed("find", null));
        assertTrue(list.isSuppressed("echo", null));
        assertTrue(list.isSuppressed("printf", null));
        assertTrue(list.isSuppressed("wc", null));
        assertTrue(list.isSuppressed("sort", null));
        assertTrue(list.isSuppressed("uniq", null));
        assertTrue(list.isSuppressed("cut", null));
        assertTrue(list.isSuppressed("tr", null));
        assertTrue(list.isSuppressed("xargs", null));
    }

    @Test
    void defaultList_suppresses_filesystem() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("cd", null));
        assertTrue(list.isSuppressed("pwd", null));
        assertTrue(list.isSuppressed("mkdir", null));
        assertTrue(list.isSuppressed("rm", null));
        assertTrue(list.isSuppressed("rmdir", null));
        assertTrue(list.isSuppressed("mv", null));
        assertTrue(list.isSuppressed("cp", null));
        assertTrue(list.isSuppressed("touch", null));
        assertTrue(list.isSuppressed("chmod", null));
        assertTrue(list.isSuppressed("chown", null));
        assertTrue(list.isSuppressed("ln", null));
    }

    @Test
    void defaultList_suppresses_network() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("curl", null));
        assertTrue(list.isSuppressed("wget", null));
        assertTrue(list.isSuppressed("ssh", null));
        assertTrue(list.isSuppressed("scp", null));
        assertTrue(list.isSuppressed("rsync", null));
    }

    @Test
    void defaultList_suppresses_system() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("ps", null));
        assertTrue(list.isSuppressed("kill", null));
        assertTrue(list.isSuppressed("top", null));
        assertTrue(list.isSuppressed("df", null));
        assertTrue(list.isSuppressed("du", null));
        assertTrue(list.isSuppressed("uname", null));
    }

    @Test
    void defaultList_suppresses_runtimes() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("python3", null));
        assertTrue(list.isSuppressed("python", null));
        assertTrue(list.isSuppressed("node", null));
        assertTrue(list.isSuppressed("java", null));
    }

    @Test
    void defaultList_suppresses_shells() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("bash", null));
        assertTrue(list.isSuppressed("sh", null));
        assertTrue(list.isSuppressed("zsh", null));
        assertTrue(list.isSuppressed("fish", null));
        assertTrue(list.isSuppressed("dash", null));
    }

    @Test
    void defaultList_suppresses_shell_builtins() {
        var list = new SuppressionList(kcpDir);
        assertTrue(list.isSuppressed("which", null));
        assertTrue(list.isSuppressed("type", null));
        assertTrue(list.isSuppressed("command", null));
        assertTrue(list.isSuppressed("env", null));
        assertTrue(list.isSuppressed("export", null));
        assertTrue(list.isSuppressed("source", null));
        assertTrue(list.isSuppressed("eval", null));
    }

    // ── NOT suppressed by default ─────────────────────────────────────────────

    @Test
    void defaultList_does_not_suppress_cloud_tools() {
        var list = new SuppressionList(kcpDir);
        assertFalse(list.isSuppressed("aws", null));
        assertFalse(list.isSuppressed("aws", "s3"));
        assertFalse(list.isSuppressed("kubectl", null));
        assertFalse(list.isSuppressed("kubectl", "get"));
        assertFalse(list.isSuppressed("helm", null));
        assertFalse(list.isSuppressed("terraform", null));
        assertFalse(list.isSuppressed("ansible", null));
    }

    @Test
    void defaultList_does_not_suppress_kcp_tools() {
        var list = new SuppressionList(kcpDir);
        assertFalse(list.isSuppressed("kcp-memory", null));
        assertFalse(list.isSuppressed("kcp-commands", null));
        assertFalse(list.isSuppressed("synthesis", null));
    }

    @Test
    void defaultList_does_not_suppress_container_tools() {
        var list = new SuppressionList(kcpDir);
        assertFalse(list.isSuppressed("docker", null));
        assertFalse(list.isSuppressed("docker", "ps"));
        assertFalse(list.isSuppressed("podman", null));
    }

    @Test
    void defaultList_does_not_suppress_build_tools() {
        var list = new SuppressionList(kcpDir);
        assertFalse(list.isSuppressed("mvn", null));
        assertFalse(list.isSuppressed("gradle", null));
    }

    @Test
    void defaultList_does_not_suppress_unknown_commands() {
        var list = new SuppressionList(kcpDir);
        assertFalse(list.isSuppressed("my-custom-tool", null));
        assertFalse(list.isSuppressed("some-obscure-cli", null));
    }

    // ── Custom suppress.txt overrides ─────────────────────────────────────────

    @Test
    void customFile_replaces_default_list() throws IOException {
        Files.writeString(kcpDir.resolve("suppress.txt"), "docker\nnpm\n");
        var list = new SuppressionList(kcpDir);

        // Custom list entries ARE suppressed
        assertTrue(list.isSuppressed("docker", null));
        assertTrue(list.isSuppressed("docker", "ps"));
        assertTrue(list.isSuppressed("npm", null));

        // Default list entries are NOT suppressed (replaced, not merged)
        assertFalse(list.isSuppressed("git", null));
        assertFalse(list.isSuppressed("ls", null));
    }

    @Test
    void customFile_ignores_comments_and_blanks() throws IOException {
        Files.writeString(kcpDir.resolve("suppress.txt"),
                "# My custom suppression list\n\ngit\n# Not ls\ngrep\n\n");
        var list = new SuppressionList(kcpDir);

        assertTrue(list.isSuppressed("git", null));
        assertTrue(list.isSuppressed("grep", null));
        // ls was not in the custom file
        assertFalse(list.isSuppressed("ls", null));
    }

    // ── Suppression is unconditional ─────────────────────────────────────────

    @Test
    void suppression_wins_even_with_manifest_file() throws IOException {
        // Shipped manifests (ls.yaml, git-log.yaml, grep.yaml) exist in commands dir
        // but suppression is unconditional — manifests are reference, not for injection
        Files.writeString(commandsDir.resolve("ls.yaml"),
                "command: ls\ndescription: my custom ls manifest\n");
        Files.writeString(commandsDir.resolve("git-log.yaml"),
                "command: git\nsubcommand: log\ndescription: custom git log\n");
        Files.writeString(commandsDir.resolve("grep.yaml"),
                "command: grep\ndescription: grep manifest\n");
        var list = new SuppressionList(kcpDir);

        // All suppressed regardless of manifest files
        assertTrue(list.isSuppressed("ls", null));
        assertTrue(list.isSuppressed("git", "log"));
        assertTrue(list.isSuppressed("git", null));
        assertTrue(list.isSuppressed("grep", null));
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void null_command_is_not_suppressed() {
        var list = new SuppressionList(kcpDir);
        assertFalse(list.isSuppressed(null, null));
    }

    @Test
    void empty_command_is_not_suppressed() {
        var list = new SuppressionList(kcpDir);
        assertFalse(list.isSuppressed("", null));
    }

    @Test
    void missing_kcp_dir_uses_defaults() {
        Path nonexistent = tempDir.resolve("nonexistent");
        var list = new SuppressionList(nonexistent);
        assertTrue(list.isSuppressed("git", null));
        assertFalse(list.isSuppressed("aws", null));
    }
}
