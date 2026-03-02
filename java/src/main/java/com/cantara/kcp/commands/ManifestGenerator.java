package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Auto-generates a minimal command manifest by running {@code <cmd> --help}.
 * The generated manifest is saved to ~/.kcp/commands/ for future sessions,
 * avoiding repeated --help invocations.
 */
public class ManifestGenerator {

    private static final Pattern FLAG_LINE = Pattern.compile("^\\s{0,4}(-{1,2}[\\w-]+(?:,\\s*-{1,2}[\\w-]+)?)\\s+(.*)");
    private static final Path USER_DIR = Path.of(System.getProperty("user.home"), ".kcp", "commands");

    /**
     * Generate a manifest for {@code cmd} (optionally with {@code subcommand}).
     * Returns null if --help invocation fails or produces unusable output.
     */
    public CommandManifest generate(String cmd, String subcommand) {
        String invocation = subcommand != null
                ? cmd + " " + subcommand + " --help"
                : cmd + " --help";

        String helpText = runHelp(invocation);
        if (helpText == null || helpText.isBlank()) return null;

        String[] lines = helpText.lines().filter(l -> !l.isBlank()).toArray(String[]::new);
        if (lines.length == 0) return null;

        String description = lines[0].trim();

        List<CommandFlag> flags = new ArrayList<>();
        for (String line : lines) {
            var m = FLAG_LINE.matcher(line);
            if (m.find()) {
                String flag = m.group(1).trim();
                String desc = m.group(2).trim();
                if (desc.length() > 80) desc = desc.substring(0, 80);
                flags.add(new CommandFlag(flag, desc, null));
                if (flags.size() >= 8) break;
            }
        }

        CommandManifest manifest = new CommandManifest(
                cmd, subcommand, "all", description,
                null, flags, List.of(), null, true
        );

        saveGenerated(cmd, subcommand, manifest, description, flags);
        return manifest;
    }

    private String runHelp(String invocation) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", invocation);
            pb.redirectErrorStream(true); // some tools write help to stderr
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** Persist generated manifest as YAML to ~/.kcp/commands/ */
    private void saveGenerated(String cmd, String subcommand,
                               CommandManifest manifest, String description,
                               List<CommandFlag> flags) {
        try {
            Files.createDirectories(USER_DIR);
            String key = subcommand != null ? cmd + "-" + subcommand : cmd;
            Path outPath = USER_DIR.resolve(key + ".yaml");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("command", cmd);
            if (subcommand != null) data.put("subcommand", subcommand);
            data.put("platform", "all");
            data.put("description", description);
            data.put("generated", true);
            data.put("generated_at", Instant.now().toString());

            List<Map<String, Object>> flagList = new ArrayList<>();
            for (CommandFlag f : flags) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("flag", f.flag());
                fm.put("description", f.description());
                flagList.add(fm);
            }
            Map<String, Object> syntax = new LinkedHashMap<>();
            syntax.put("key_flags", flagList);
            data.put("syntax", syntax);

            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setWidth(100);
            new Yaml(opts).dump(data, Files.newBufferedWriter(outPath));
        } catch (IOException e) {
            // Non-fatal — manifest still returned for this session
        }
    }
}
