package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.SlashCommandInfo;
import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.support.NotFoundException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Lists the slash commands the composer can autocomplete: user-invocable skills
 * ({@code skills/<name>/SKILL.md}) and custom commands ({@code commands/**.md}), merged from the
 * Claude home and the project's own {@code .claude} directory (the project copy wins a name clash).
 * This is typing assistance only — the composer sends {@code /name args} as plain prompt text and
 * the CLI expands it itself, so the send path is untouched. The directories are tiny, so each
 * request re-scans them (no cache to invalidate when a skill is added mid-session).
 */
@Service
public class SlashCommandCatalog {

    private final ClaudeProperties props;
    private final ClaudeDataService data;

    public SlashCommandCatalog(ClaudeProperties props, ClaudeDataService data) {
        this.props = props;
        this.data = data;
    }

    /** All commands available in {@code projectId} (null/blank = user-level only), sorted by name. */
    public List<SlashCommandInfo> list(String projectId) {
        Map<String, SlashCommandInfo> byName = new TreeMap<>();
        Path home = props.getHome();
        collectSkills(home.resolve("skills"), "user", byName);
        collectCommands(home.resolve("commands"), "user", byName);
        if (projectId != null && !projectId.isBlank()) {
            try {
                Path dotClaude = Path.of(data.projectWorkingDir(projectId)).resolve(".claude");
                collectSkills(dotClaude.resolve("skills"), "project", byName);
                collectCommands(dotClaude.resolve("commands"), "project", byName);
            } catch (NotFoundException | InvalidPathException ignore) {
                // unknown project / undecodable cwd — the user-level commands still apply
            }
        }
        return List.copyOf(byName.values());
    }

    /** Skills: one directory per skill, described by its {@code SKILL.md} frontmatter. */
    private void collectSkills(Path skillsDir, String source, Map<String, SlashCommandInfo> out) {
        if (!Files.isDirectory(skillsDir)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path md = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) {
                    return;
                }
                Map<String, String> fm = frontmatter(md);
                if ("false".equalsIgnoreCase(fm.get("user-invocable"))) {
                    return; // model-only skill — typing it as /name would not resolve
                }
                String name = fm.getOrDefault("name", "").strip();
                if (name.isEmpty()) {
                    name = dir.getFileName().toString();
                }
                out.put(name, new SlashCommandInfo(name, fm.getOrDefault("description", ""),
                        fm.get("argument-hint"), source, "skill"));
            });
        } catch (IOException ignore) {
            // unreadable directory — skip this source
        }
    }

    /** Custom commands: every {@code .md} file; subdirectories namespace the name with {@code :}. */
    private void collectCommands(Path commandsDir, String source, Map<String, SlashCommandInfo> out) {
        if (!Files.isDirectory(commandsDir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(commandsDir, 4)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .forEach(f -> {
                        String rel = commandsDir.relativize(f).toString();
                        String name = rel.substring(0, rel.length() - ".md".length())
                                .replace('\\', ':').replace('/', ':');
                        Map<String, String> fm = frontmatter(f);
                        out.put(name, new SlashCommandInfo(name, fm.getOrDefault("description", ""),
                                fm.get("argument-hint"), source, "command"));
                    });
        } catch (IOException ignore) {
            // unreadable directory — skip this source
        }
    }

    /**
     * Top-level {@code key: value} pairs of a YAML frontmatter block. Hand-rolled on purpose: the
     * fields we need are flat strings, so a YAML dependency would be dead weight. Indented lines
     * (nested blocks like {@code metadata:}) are skipped; surrounding quotes are stripped.
     */
    private static Map<String, String> frontmatter(Path file) {
        Map<String, String> fm = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line = r.readLine();
            if (line == null) {
                return fm;
            }
            if (line.startsWith("﻿")) {
                line = line.substring(1); // strip a UTF-8 BOM
            }
            if (!"---".equals(line.strip())) {
                return fm;
            }
            while ((line = r.readLine()) != null) {
                if ("---".equals(line.strip())) {
                    break;
                }
                if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String value = line.substring(colon + 1).strip();
                if (value.length() >= 2
                        && (value.charAt(0) == '"' && value.endsWith("\"")
                                || value.charAt(0) == '\'' && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                fm.put(line.substring(0, colon).strip(), value);
            }
        } catch (IOException ignore) {
            // unreadable/malformed file — treated as having no frontmatter
        }
        return fm;
    }
}
