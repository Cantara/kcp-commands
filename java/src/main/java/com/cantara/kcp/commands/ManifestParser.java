package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.*;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Parses a command manifest YAML file into a CommandManifest record.
 * Uses raw Map access rather than SnakeYAML bean mapping to stay
 * compatible with Java records.
 */
public class ManifestParser {

    private static final Yaml YAML = new Yaml();

    @SuppressWarnings("unchecked")
    public static CommandManifest parse(InputStream is) {
        Map<String, Object> data = YAML.load(is);
        if (data == null) throw new IllegalArgumentException("Empty manifest");

        String command    = required(data, "command");
        String subcommand = (String) data.get("subcommand");
        String platform   = (String) data.getOrDefault("platform", "all");
        String description = required(data, "description");
        boolean generated = Boolean.TRUE.equals(data.get("generated"));

        // syntax block
        Map<String, Object> syntax = (Map<String, Object>) data.getOrDefault("syntax", Map.of());
        String usage = (String) syntax.get("usage");

        List<CommandFlag> keyFlags = parseFlags((List<Map<String, Object>>) syntax.getOrDefault("key_flags", List.of()));
        List<PreferredInvocation> preferred = parsePreferred((List<Map<String, Object>>) syntax.getOrDefault("preferred_invocations", List.of()));

        // output_schema block
        OutputSchema outputSchema = null;
        if (data.containsKey("output_schema")) {
            outputSchema = parseOutputSchema((Map<String, Object>) data.get("output_schema"));
        }

        return new CommandManifest(command, subcommand, platform, description,
                usage, keyFlags, preferred, outputSchema, generated);
    }

    private static List<CommandFlag> parseFlags(List<Map<String, Object>> list) {
        return list.stream().map(m -> new CommandFlag(
                (String) m.get("flag"),
                (String) m.getOrDefault("description", ""),
                (String) m.get("use_when")
        )).toList();
    }

    private static List<PreferredInvocation> parsePreferred(List<Map<String, Object>> list) {
        return list.stream().map(m -> new PreferredInvocation(
                (String) m.get("invocation"),
                (String) m.getOrDefault("use_when", "")
        )).toList();
    }

    @SuppressWarnings("unchecked")
    private static OutputSchema parseOutputSchema(Map<String, Object> m) {
        boolean enableFilter = Boolean.TRUE.equals(m.get("enable_filter"));
        int maxLines = (int) m.getOrDefault("max_lines", 0);
        String truncMsg = (String) m.get("truncation_message");

        List<NoisePattern> noisePatterns = List.of();
        if (m.containsKey("noise_patterns")) {
            noisePatterns = ((List<Map<String, Object>>) m.get("noise_patterns")).stream()
                    .map(n -> new NoisePattern(
                            (String) n.get("pattern"),
                            (String) n.get("reason")
                    )).toList();
        }

        return new OutputSchema(enableFilter, noisePatterns, maxLines, truncMsg);
    }

    private static String required(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (!(val instanceof String s)) throw new IllegalArgumentException("Missing required field: " + key);
        return s;
    }
}
