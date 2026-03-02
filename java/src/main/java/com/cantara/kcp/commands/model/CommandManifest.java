package com.cantara.kcp.commands.model;

import java.util.List;

public record CommandManifest(
        String command,
        String subcommand,        // nullable
        String platform,          // "linux" | "darwin" | "all"
        String description,
        String usage,             // nullable — from syntax.usage
        List<CommandFlag> keyFlags,
        List<PreferredInvocation> preferredInvocations,
        OutputSchema outputSchema, // nullable
        boolean generated
) {}
