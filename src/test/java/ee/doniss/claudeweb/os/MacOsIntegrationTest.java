package ee.doniss.claudeweb.os;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacOsIntegrationTest {

    /** Captures the launched command instead of spawning a real process. */
    static final class CapturingRunner extends ProcessRunner {
        List<String> command;
        String dir;

        @Override
        public void launch(List<String> command, String workingDir) {
            this.command = command;
            this.dir = workingDir;
        }
    }

    private final CapturingRunner runner = new CapturingRunner();
    private final MacOsIntegration os = new MacOsIntegration(runner);

    /** The terminal is opened via `open -a Terminal <launcher.command>`; read that launcher. */
    private String launcherContent() throws IOException {
        assertEquals(List.of("open", "-a", "Terminal").toString(),
                runner.command.subList(0, 3).toString());
        String launcher = runner.command.get(runner.command.size() - 1);
        assertTrue(launcher.endsWith(".command"), launcher);
        return Files.readString(Path.of(launcher), StandardCharsets.UTF_8);
    }

    @Test
    void resumeLauncherRunsClaudeResume() throws IOException {
        os.resume("abc-123", System.getProperty("user.home"), false);
        String content = launcherContent();
        assertTrue(content.contains("claude --resume abc-123"), content);
        assertFalse(content.contains("--fork-session"), content);
        assertTrue(content.startsWith("#!/bin/sh"), content);
        assertTrue(content.contains("exec \"${SHELL:-/bin/sh}\""), "keeps the window open");
    }

    @Test
    void forkLauncherAddsFlag() throws IOException {
        os.resume("abc-123", System.getProperty("user.home"), true);
        assertTrue(launcherContent().contains("claude --resume abc-123 --fork-session"));
    }

    @Test
    void newSessionLauncherRunsPlainClaude() throws IOException {
        os.newSession(System.getProperty("user.home"));
        String content = launcherContent();
        assertTrue(content.contains("\nclaude\n"), content);
        assertFalse(content.contains("--resume"), content);
    }

    @Test
    void revealUsesOpenDashR() {
        os.revealFile("/Users/x/.claude/projects/p/s.jsonl");
        assertEquals(List.of("open", "-R", "/Users/x/.claude/projects/p/s.jsonl"), runner.command);
    }

    @Test
    void openFolderUsesOpen() {
        String home = System.getProperty("user.home");
        os.openFolder(home);
        assertEquals(List.of("open", home), runner.command);
    }

    @Test
    void resumeCommandString() {
        assertEquals("claude --resume abc", os.resumeCommand("abc", false));
        assertEquals("claude --resume abc --fork-session", os.resumeCommand("abc", true));
    }
}
