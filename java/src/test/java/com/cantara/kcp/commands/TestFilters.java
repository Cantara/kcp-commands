package com.cantara.kcp.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** A stand-in for an installed ~/.kcp/kcp-filter: the hook only rewrites when the filter is there. */
final class TestFilters {

    private TestFilters() {
    }

    /** Creates an executable kcp-filter in the given directory and returns its path. */
    static String installed(Path kcpDir) throws IOException {
        Files.createDirectories(kcpDir);
        Path filter = kcpDir.resolve("kcp-filter");
        Files.writeString(filter, "#!/bin/sh\ncat\n");
        try {
            Files.setPosixFilePermissions(filter, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            filter.toFile().setExecutable(true);
        }
        return filter.toString();
    }
}
