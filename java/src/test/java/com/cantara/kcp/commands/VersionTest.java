package com.cantara.kcp.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The daemon must report the version it was built as. VERSION was a hand-written constant, "0.23.0",
 * that no release ever bumped, so /version, the startup line and the update check all said 0.23.0
 * whatever jar was running. It now comes from the build (kcp.version, set from the git tag by the
 * release workflow), which surefire passes to this test as kcp.expected.version.
 */
class VersionTest {

    @Test
    void reports_the_version_it_was_built_as() {
        String expected = System.getProperty("kcp.expected.version");
        assertNotNull(expected, "surefire must pass kcp.expected.version");
        assertEquals(expected, KcpCommandsDaemon.VERSION);
    }

    @Test
    void version_is_a_plain_semver() {
        assertTrue(KcpCommandsDaemon.VERSION.matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?"),
                "unexpected version string: " + KcpCommandsDaemon.VERSION);
    }
}
