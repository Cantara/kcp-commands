package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.CommandManifest;
import com.cantara.kcp.commands.model.NoisePattern;
import com.cantara.kcp.commands.model.OutputSchema;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * Inspects filter stats against the manifest schema and records deviations.
 *
 * Deviation types:
 *   truncation      — raw output well above the max_lines cap
 *                     → auto-tunes a user-level manifest with headroom
 *   stale_pattern   — a noise_pattern matched zero lines (possibly outdated)
 *                     → logged only; human should review
 *   over_configured — max_lines is set but output is tiny (< 10% of cap)
 *                     → logged only; suggests loosening the schema
 *
 * Equivalent to typescript/src/deviation.ts.
 */
public class DeviationDetector {

    private static final Path KCP_DIR       = Path.of(System.getProperty("user.home"), ".kcp");
    private static final Path DEVIATION_LOG = KCP_DIR.resolve("deviations.log");
    private static final Path USER_DIR      = KCP_DIR.resolve("commands");

    /**
     * Statistics produced by the filter step, passed to deviation detection.
     *
     * @param key          manifest key (e.g. "ps", "git-log")
     * @param rawLines     non-blank lines before noise removal
     * @param filteredLines lines after noise removal, before max_lines cap
     * @param wasTruncated filtered count exceeded max_lines
     * @param patternHits  pattern string → number of lines matched
     * @param manifest     resolved manifest (for schema access and auto-tuning)
     */
    public record FilterStats(
            String key,
            int rawLines,
            int filteredLines,
            boolean wasTruncated,
            Map<String, Integer> patternHits,
            CommandManifest manifest
    ) {}

    public static void check(FilterStats stats) {
        OutputSchema schema = stats.manifest().outputSchema();
        if (schema == null || !schema.enableFilter()) return;

        List<String> findings = new ArrayList<>();

        // 1. Significant truncation: actual output well above the configured cap.
        //    Threshold: 1.5× to avoid noise from occasional outlier runs.
        if (stats.wasTruncated() && schema.maxLines() > 0
                && stats.rawLines() > schema.maxLines() * 1.5) {
            findings.add("truncation: raw_lines=" + stats.rawLines()
                    + ", max_lines=" + schema.maxLines());
        }

        // 2. Stale noise patterns: patterns that never matched.
        //    Only flag when output is substantial (≥ 10 lines) to avoid false positives.
        if (stats.rawLines() >= 10) {
            for (Map.Entry<String, Integer> entry : stats.patternHits().entrySet()) {
                if (entry.getValue() == 0) {
                    findings.add("stale_pattern: \"" + entry.getKey()
                            + "\" matched 0/" + stats.rawLines() + " lines");
                }
            }
        }

        // 3. Over-configured: max_lines set but output is tiny.
        if (!stats.wasTruncated() && schema.maxLines() > 0
                && stats.rawLines() > 0
                && stats.rawLines() < schema.maxLines() / 10.0) {
            findings.add("over_configured: max_lines=" + schema.maxLines()
                    + " but only " + stats.rawLines() + " raw lines");
        }

        if (findings.isEmpty()) return;

        logDeviation(stats.key(), findings);

        // Auto-tune only on significant truncation — the only case where we know
        // the right direction (up) with high confidence.
        if (stats.wasTruncated() && schema.maxLines() > 0
                && stats.rawLines() > schema.maxLines() * 1.5) {
            tuneLocalManifest(stats);
        }
    }

    private static void logDeviation(String key, List<String> findings) {
        try {
            Files.createDirectories(KCP_DIR);
            String line = "[" + Instant.now() + "] " + key + ": "
                    + String.join(" | ", findings) + "\n";
            Files.writeString(DEVIATION_LOG, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Non-fatal — deviation logging must never break the filter output
        }
    }

    /**
     * Write an adjusted manifest to ~/.kcp/commands/{key}.yaml.
     * Sets max_lines to rawLines × 1.3 rounded up to the nearest 25,
     * giving 30% headroom without excessive over-allocation.
     *
     * This is a user-level override — it takes precedence over the primed
     * manifest and is picked up on the next hook invocation without restart.
     */
    private static void tuneLocalManifest(FilterStats stats) {
        int rawLines = stats.rawLines();
        int tuned = (int) (Math.ceil(rawLines * 1.3 / 25.0) * 25);

        CommandManifest m = stats.manifest();
        OutputSchema oldSchema = m.outputSchema();

        // Reconstruct full manifest as a YAML map (mirrors the manifest file format)
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("command", m.command());
        if (m.subcommand() != null) yaml.put("subcommand", m.subcommand());
        yaml.put("platform", m.platform());
        yaml.put("description", m.description());

        // syntax block
        if (m.usage() != null || !m.keyFlags().isEmpty() || !m.preferredInvocations().isEmpty()) {
            Map<String, Object> syntaxMap = new LinkedHashMap<>();
            if (m.usage() != null) syntaxMap.put("usage", m.usage());

            if (!m.keyFlags().isEmpty()) {
                List<Map<String, Object>> flags = m.keyFlags().stream().map(f -> {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("flag", f.flag());
                    fm.put("description", f.description());
                    if (f.useWhen() != null) fm.put("use_when", f.useWhen());
                    return fm;
                }).toList();
                syntaxMap.put("key_flags", flags);
            }

            if (!m.preferredInvocations().isEmpty()) {
                List<Map<String, Object>> prefs = m.preferredInvocations().stream().map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("invocation", p.invocation());
                    pm.put("use_when", p.useWhen());
                    return pm;
                }).toList();
                syntaxMap.put("preferred_invocations", prefs);
            }

            yaml.put("syntax", syntaxMap);
        }

        // output_schema block with updated max_lines
        Map<String, Object> schemaMap = new LinkedHashMap<>();
        schemaMap.put("enable_filter", true);

        if (oldSchema.noisePatterns() != null && !oldSchema.noisePatterns().isEmpty()) {
            List<Map<String, Object>> patterns = oldSchema.noisePatterns().stream().map(np -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("pattern", np.pattern());
                if (np.reason() != null) pm.put("reason", np.reason());
                return pm;
            }).toList();
            schemaMap.put("noise_patterns", patterns);
        }

        schemaMap.put("max_lines", tuned);
        if (oldSchema.truncationMessage() != null) {
            schemaMap.put("truncation_message", oldSchema.truncationMessage());
        }

        yaml.put("output_schema", schemaMap);

        try {
            Files.createDirectories(USER_DIR);
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setWidth(100);
            Yaml dumper = new Yaml(opts);
            Files.writeString(USER_DIR.resolve(stats.key() + ".yaml"), dumper.dump(yaml));
        } catch (Exception ignored) {
            // Non-fatal
        }
    }
}
