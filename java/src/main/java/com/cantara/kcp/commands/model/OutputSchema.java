package com.cantara.kcp.commands.model;

import java.util.List;

public record OutputSchema(
        boolean enableFilter,
        List<NoisePattern> noisePatterns,
        int maxLines,                  // 0 = unlimited
        String truncationMessage       // nullable
) {}
