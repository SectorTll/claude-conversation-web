package ee.doniss.claudeweb.os;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Windows implementation of the CLI/Explorer actions. Ports the desktop {@code CliLauncher}.
 *
 * <p>The terminal launch writes the claude command into a generated {@code .cmd} launcher and opens
 * it in a new window via {@code cmd /c start "" cmd /k "<launcher>"}. Two reasons: (1) bare
 * {@code ProcessBuilder}/CreateProcess attaches the child to our console and shows no window (and
 * can't run the {@code wt.exe} app-execution alias, which needs ShellExecute that {@code start}
 * provides); (2) putting the command in a file means it is never re-parsed by the shell, so tokens
 * never leak into claude's input. Process launches go through {@link ProcessRunner} (mockable).
 */
public class WindowsOsIntegration implements OsIntegration {

    private final ProcessRunner runner;

    public WindowsOsIntegration(ProcessRunner runner) {
        this.runner = runner;
    }

    @Override
    public void resume(String sessionId, String workingDir, boolean fork) {
        launchClaude("claude --resume " + sessionId + (fork ? " --fork-session" : ""), workingDir);
    }

    @Override
    public void newSession(String workingDir) {
        launchClaude("claude", workingDir);
    }

    @Override
    public void openFolder(String path) {
        if (path != null && !path.isBlank() && new File(path).isDirectory()) {
            runner.launch(List.of("explorer.exe", path), null);
        }
    }

    @Override
    public void revealFile(String path) {
        if (path != null && !path.isBlank()) {
            // explorer parses "/select,<path>" as a single token (the .jsonl path has no spaces).
            runner.launch(List.of("explorer.exe", "/select," + path), null);
        }
    }

    @Override
    public String resumeCommand(String sessionId, boolean fork) {
        return "claude --resume " + sessionId + (fork ? " --fork-session" : "");
    }

    @Override
    public boolean supportsScheduling() {
        return true;
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    private void launchClaude(String claudeCommand, String workingDir) {
        String dir = workingDir != null && new File(workingDir).isDirectory()
                ? workingDir
                : System.getProperty("user.home");
        Path launcher = writeLauncher(dir, claudeCommand);
        // Empty start title is the reliable form; cmd /k keeps the window open after claude exits.
        // With Windows Terminal set as the default terminal, this console is hosted in wt anyway.
        runner.launch(List.of("cmd.exe", "/c", "start", "", "cmd", "/k", launcher.toString()), dir);
    }

    private Path writeLauncher(String dir, String claudeCommand) {
        String content = "@echo off\r\n"
                + "chcp 65001 >nul\r\n"   // UTF-8 console so non-ASCII paths in `cd` survive
                + "title Claude\r\n"
                + "cd /d \"" + dir + "\"\r\n"
                + claudeCommand + "\r\n";
        try {
            Path file = Files.createTempFile("claude-launch-", ".cmd");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
