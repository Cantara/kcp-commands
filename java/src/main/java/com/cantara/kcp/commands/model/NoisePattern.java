package com.cantara.kcp.commands.model;

public record NoisePattern(
        String pattern,
        String reason   // nullable
) {}
