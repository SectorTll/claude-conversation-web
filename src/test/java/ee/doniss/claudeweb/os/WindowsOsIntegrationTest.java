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

class WindowsOsIntegrationTest {

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
    private final WindowsOsIntegration os = new WindowsOsIntegration(runner);

    /** The terminal is opened via `cmd /c start "" cmd /k <launcher.cmd>`; read that launcher. */
    private String launcherContent() throws IOException {
        assertEquals("cmd.exe", runner.command.get(0));
        assertTrue(runner.command.contains("start"));
        String launcher = runner.command.get(runner.command.size() - 1);
        assertTrue(launcher.endsWith(".cmd"), launcher);
        return Files.readString(Path.of(launcher), StandardCharsets.UTF_8);
    }

    @Test
    void resumeLauncherRunsClaudeResume() throws IOException {
        os.resume("abc-123", System.getProperty("user.home"), false);
        String content = launcherContent();
        assertTrue(content.contains("claude --resume abc-123"), content);
        assertFalse(content.contains("--fork-session"), content);
        assertTrue(content.contains("chcp 65001"), "forces UTF-8 console");
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
        assertTrue(content.contains("\r\nclaude\r\n"), content);
        assertFalse(content.contains("--resume"), content);
    }

    @Test
    void revealUsesExplorerSelect() {
        os.revealFile("C:\\Users\\x\\.claude\\projects\\p\\s.jsonl");
        assertEquals(
                List.of("explorer.exe", "/select,C:\\Users\\x\\.claude\\projects\\p\\s.jsonl"),
                runner.command);
    }

    @Test
    void resumeCommandString() {
        assertEquals("claude --resume abc", os.resumeCommand("abc", false));
        assertEquals("claude --resume abc --fork-session", os.resumeCommand("abc", true));
    }
}
