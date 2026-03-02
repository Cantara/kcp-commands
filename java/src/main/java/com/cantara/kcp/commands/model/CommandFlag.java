package com.cantara.kcp.commands.model;

public record CommandFlag(
        String flag,
        String description,
        String useWhen   // nullable
) {}
