package ee.doniss.claudeweb.os;

import ee.doniss.claudeweb.support.OsActionUnsupportedException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxOsIntegrationTest {

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

    /** Build an integration that believes exactly {@code present} executables are on PATH. */
    private LinuxOsIntegration with(String... present) {
        Predicate<String> onPath = Set.of(present)::contains;
        return new LinuxOsIntegration(runner, onPath);
    }

    private String launcherContent() throws IOException {
        String launcher = runner.command.get(runner.command.size() - 1);
        assertTrue(launcher.endsWith(".sh"), launcher);
        return Files.readString(Path.of(launcher), StandardCharsets.UTF_8);
    }

    @Test
    void resumeUsesXtermDashE() throws IOException {
        with("xterm").resume("abc-123", System.getProperty("user.home"), false);
        assertEquals("xterm", runner.command.get(0));
        assertEquals("-e", runner.command.get(1));
        assertTrue(launcherContent().contains("claude --resume abc-123"));
    }

    @Test
    void gnomeTerminalUsesDoubleDash() {
        with("gnome-terminal").newSession(System.getProperty("user.home"));
        assertEquals("gnome-terminal", runner.command.get(0));
        assertEquals("--", runner.command.get(1));
    }

    @Test
    void kittyTakesProgramWithoutFlag() {
        with("kitty").newSession(System.getProperty("user.home"));
        assertEquals("kitty", runner.command.get(0));
        // No run-flag: kitty <launcher>
        assertTrue(runner.command.get(1).endsWith(".sh"), runner.command.toString());
    }

    @Test
    void firstTerminalInProbeOrderWins() {
        // konsole comes before xterm in the probe list, so it should be chosen when both exist.
        with("konsole", "xterm").newSession(System.getProperty("user.home"));
        assertEquals("konsole", runner.command.get(0));
    }

    @Test
    void noTerminalFoundThrowsUnsupported() {
        LinuxOsIntegration os = with(/* nothing on PATH */);
        assertThrows(OsActionUnsupportedException.class,
                () -> os.newSession(System.getProperty("user.home")));
    }

    @Test
    void openFolderUsesXdgOpen() {
        String home = System.getProperty("user.home");
        with("xdg-open").openFolder(home);
        assertEquals(List.of("xdg-open", home), runner.command);
    }

    @Test
    void revealUsesDbusShowItemsWhenAvailable() {
        with("dbus-send").revealFile("/home/x/.claude/projects/p/s.jsonl");
        assertEquals("dbus-send", runner.command.get(0));
        assertTrue(runner.command.contains("org.freedesktop.FileManager1.ShowItems"), runner.command.toString());
        assertTrue(runner.command.stream().anyMatch(a -> a.startsWith("array:string:file:")),
                runner.command.toString());
    }

    @Test
    void revealFallsBackToParentFolderWithoutDbus() {
        // No dbus-send on PATH: open the containing directory instead (parent must exist).
        Path file = Path.of(System.getProperty("user.home"), "s.jsonl");
        with(/* no dbus-send */).revealFile(file.toString());
        assertEquals(List.of("xdg-open", file.getParent().toString()), runner.command);
    }

    @Test
    void resumeCommandString() {
        LinuxOsIntegration os = with("xterm");
        assertEquals("claude --resume abc", os.resumeCommand("abc", false));
        assertEquals("claude --resume abc --fork-session", os.resumeCommand("abc", true));
        assertTrue(os.isSupported());
        assertFalse(os.supportsScheduling());
    }
}
