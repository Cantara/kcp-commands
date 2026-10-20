package com.cantara.kcp.commands;

import com.cantara.kcp.commands.model.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Auto-generates a minimal command manifest by running {@code <cmd> [<subcommand>] --help}.
 * The generated manifest is saved to ~/.kcp/commands/ for future sessions,
 * avoiding repeated --help invocations.
 *
 * <p>Running a command the agent has not run yet is only safe if the command treats
 * {@code --help} as a request for help. So the probe is deliberately narrow:
 * <ul>
 *   <li>it runs the program directly, never through a shell, so text from the agent's command
 *       line ({@code VAR=value}, {@code for x in ...}) is never interpreted;</li>
 *   <li>it only runs a program it finds on PATH, and never a script (a file starting with
 *       {@code #!}): a script that does not parse {@code --help} would simply do its real work;</li>
 *   <li>stdin is closed and the probe is killed after a short timeout, so a program that waits
 *       for input cannot hang the daemon;</li>
 *   <li>output that does not look like help (an error, a command's real output) is not saved.</li>
 * </ul>
 */
public class ManifestGenerator {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private static final Pattern FLAG_LINE = Pattern.compile("^\\s{0,4}(-{1,2}[\\w-]+(?:,\\s*-{1,2}[\\w-]+)?)\\s+(.*)");
    /** A plain program or subcommand name: no path, no '=', no shell syntax. */
    private static final Pattern PLAIN_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]*$");
    private static final Pattern USAGE = Pattern.compile("(?i)\\busage\\b");
    private static final Path DEFAULT_USER_DIR = Path.of(System.getProperty("user.home"), ".kcp", "commands");

    private final Path userDir;
    private final String searchPath;
    private final Duration timeout;

    public ManifestGenerator() {
        this(DEFAULT_USER_DIR, System.getenv("PATH"), DEFAULT_TIMEOUT);
    }

    /** For tests: where manifests are saved, which PATH is searched, and how long a probe may run. */
    ManifestGenerator(Path userDir, String searchPath, Duration timeout) {
        this.userDir = userDir;
        this.searchPath = searchPath == null ? "" : searchPath;
        this.timeout = timeout;
    }

    /**
     * Generate a manifest for {@code cmd} (optionally with {@code subcommand}).
     * Returns null when the command is not safe to probe, the probe fails, or its output is
     * not recognisable help text.
     */
    public CommandManifest generate(String cmd, String subcommand) {
        if (cmd == null || !PLAIN_NAME.matcher(cmd).matches()) return null;
        if (subcommand != null && !PLAIN_NAME.matcher(subcommand).matches()) return null;

        Path program = findOnPath(cmd);
        if (program == null || isScript(program)) return null;

        List<String> argv = new ArrayList<>();
        argv.add(program.toString());
        if (subcommand != null) argv.add(subcommand);
        argv.add("--help");

        String helpText = runHelp(argv, timeout);
        if (helpText == null || helpText.isBlank()) return null;

        String[] lines = helpText.lines().filter(l -> !l.isBlank()).toArray(String[]::new);
        if (lines.length == 0) return null;

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
        if (!looksLikeHelp(helpText)) return null;

        String description = lines[0].trim();
        CommandManifest manifest = new CommandManifest(
                cmd, subcommand, "all", description,
                null, flags, List.of(), null, true
        );

        saveGenerated(cmd, subcommand, manifest, description, flags);
        return manifest;
    }

    /**
     * Help text names its usage or lists flags. Anything else (an error such as "command not
     * found", a command's real output) is not a manifest and must not be cached as one.
     */
    static boolean looksLikeHelp(String text) {
        if (USAGE.matcher(text).find()) return true;
        return text.lines().anyMatch(l -> FLAG_LINE.matcher(l).find());
    }

    /** The executable regular file named {@code cmd} in the first PATH directory that has one. */
    Path findOnPath(String cmd) {
        for (String dir : searchPath.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir, cmd);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p;
        }
        return null;
    }

    /** True when the file (after following symlinks) starts with "#!". Unreadable counts as a script. */
    static boolean isScript(Path program) {
        try (InputStream in = Files.newInputStream(program)) {
            byte[] head = in.readNBytes(2);
            return head.length == 2 && head[0] == '#' && head[1] == '!';
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Run {@code argv} with stdin closed, stdout and stderr together (some tools print help on
     * stderr), at most {@code timeout}. Returns null if it could not start or did not finish.
     */
    static String runHelp(List<String> argv, Duration timeout) {
        Process proc;
        try {
            proc = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (IOException e) {
            return null;
        }
        try {
            proc.getOutputStream().close(); // stdin: EOF at once
        } catch (IOException ignored) {
            // nothing to do: the process is still bounded by the timeout below
        }
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = proc.getInputStream()) {
                return in.readNBytes(MAX_OUTPUT_BYTES);
            } catch (IOException e) {
                return new byte[0];
            }
        });
        try {
            if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            return new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (Exception e) {
            proc.destroyForcibly();
            return null;
        }
    }

    /** Persist generated manifest as YAML to ~/.kcp/commands/ */
    private void saveGenerated(String cmd, String subcommand,
                               CommandManifest manifest, String description,
                               List<CommandFlag> flags) {
        try {
            Files.createDirectories(userDir);
            String key = subcommand != null ? cmd + "-" + subcommand : cmd;
            Path outPath = userDir.resolve(key + ".yaml");

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
            try (var w = Files.newBufferedWriter(outPath)) {
                new Yaml(opts).dump(data, w);
            }
        } catch (IOException e) {
            // Non-fatal — manifest still returned for this session
        }
    }
}
