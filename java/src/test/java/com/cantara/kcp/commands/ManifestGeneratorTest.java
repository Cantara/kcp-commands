package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.CommandManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The --help probe runs a command the agent has not run yet, so it must never run anything
 * that would not treat --help as a request for help.
 *
 * The scripts below write a marker file when they run. "Not run" is asserted on the marker,
 * not inferred from the return value.
 */
@DisabledOnOs(OS.WINDOWS)
class ManifestGeneratorTest {

    @TempDir Path tmp;

    private Path script(Path dir, String name, Path marker) throws IOException {
        Files.createDirectories(dir);
        Path s = dir.resolve(name);
        Files.writeString(s, "#!/bin/sh\ntouch '" + marker + "'\necho \"usage: did real work with: $*\"\n");
        Files.setPosixFilePermissions(s, PosixFilePermissions.fromString("rwxr-xr-x"));
        return s;
    }

    private ManifestGenerator generator(String path) {
        return new ManifestGenerator(tmp.resolve("manifests"), path, Duration.ofSeconds(3));
    }

    private boolean anyManifestSaved() throws IOException {
        Path dir = tmp.resolve("manifests");
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) { return s.findAny().isPresent(); }
    }

    @Test
    void aScriptOnPathIsNeverRun() throws IOException {
        Path bin = tmp.resolve("bin"), marker = tmp.resolve("ran");
        script(bin, "deploy-everything", marker);

        assertNull(generator(bin.toString()).generate("deploy-everything", null));
        assertNull(generator(bin.toString()).generate("deploy-everything", "prod"));

        assertFalse(Files.exists(marker), "the script ran");
        assertFalse(anyManifestSaved());
    }

    @Test
    void aSymlinkToAScriptIsNeverRun() throws IOException {
        Path bin = tmp.resolve("bin"), marker = tmp.resolve("ran");
        Path target = script(tmp.resolve("real"), "tool.sh", marker);
        Files.createDirectories(bin);
        Files.createSymbolicLink(bin.resolve("tool"), target);

        assertNull(generator(bin.toString()).generate("tool", null));
        assertFalse(Files.exists(marker), "the script behind the symlink ran");
    }

    @Test
    void textFromTheCommandLineIsNeverHandedToAShell() throws IOException {
        Path marker = tmp.resolve("ran");
        String path = System.getenv("PATH");
        for (String cmd : List.of("S=/tmp/x", "for", "true;touch " + marker, "true$(touch " + marker + ")",
                                  "../bin/true", "/usr/bin/true", "until")) {
            assertNull(generator(path).generate(cmd, null), cmd);
        }
        assertNull(generator(path).generate("true", "x;touch " + marker));
        assertFalse(Files.exists(marker), "shell syntax was interpreted");
        assertFalse(anyManifestSaved());
    }

    @Test
    void aCommandThatIsNotOnPathSavesNothing() throws IOException {
        assertNull(generator(System.getenv("PATH")).generate("no-such-command-for-kcp-tests", null));
        assertFalse(anyManifestSaved(), "a 'command not found' must not be cached as a manifest");
    }

    @Test
    void aRealProgramStillGetsAManifest() {
        CommandManifest m = generator(System.getenv("PATH")).generate("ls", null);
        assertNotNull(m, "ls --help is help text and must still produce a manifest");
        assertFalse(m.description().isBlank());
        assertTrue(Files.exists(tmp.resolve("manifests").resolve("ls.yaml")));
    }

    @Test
    void outputThatIsNotHelpIsNotTakenForHelp() {
        // Real outputs the old probe cached as manifests.
        assertFalse(ManifestGenerator.looksLikeHelp("bash: line 1: --help: command not found\n"));
        assertFalse(ManifestGenerator.looksLikeHelp("bash: -c: line 1: syntax error near unexpected token `--help'\n"));
        assertFalse(ManifestGenerator.looksLikeHelp("sunstone-ti\nexoreaction-canvas\n"));
        assertTrue(ManifestGenerator.looksLikeHelp("Usage: ls [OPTION]... [FILE]...\n"));
        assertTrue(ManifestGenerator.looksLikeHelp("Print stuff.\n  -n, --dry-run   show what would be done\n"));
    }

    @Test
    void aProgramThatDoesNotFinishIsKilled() {
        long start = System.nanoTime();
        assertNull(ManifestGenerator.runHelp(List.of("sleep", "30"), Duration.ofMillis(300)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "the probe waited for sleep");
    }

    @Test
    void stdinIsClosedSoAProgramReadingItDoesNotWait() {
        assertEquals("", ManifestGenerator.runHelp(List.of("cat"), Duration.ofSeconds(3)));
    }
}
