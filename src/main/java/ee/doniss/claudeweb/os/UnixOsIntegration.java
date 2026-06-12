package ee.doniss.claudeweb.os;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Shared base for the Unix CLI/file-manager integrations (macOS, Linux). The terminal launch mirrors
 * the Windows approach: the claude command is written into a generated launcher script and the
 * terminal is pointed at that file, so the command is never re-parsed by a shell (no token leaks into
 * claude's input) and a non-ASCII working dir survives. Subclasses only decide how to open a terminal
 * at that launcher and how to open / reveal in the platform file manager.
 */
abstract class UnixOsIntegration implements OsIntegration {

    protected final ProcessRunner runner;

    protected UnixOsIntegration(ProcessRunner runner) {
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
    public String resumeCommand(String sessionId, boolean fork) {
        return "claude --resume " + sessionId + (fork ? " --fork-session" : "");
    }

    @Override
    public boolean supportsScheduling() {
        // Scheduling is Windows-only (Task Scheduler). The capabilities endpoint hides it elsewhere.
        return false;
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    /** Open a terminal window that runs {@code launcher} (an executable script). */
    protected abstract void openTerminal(String dir, Path launcher);

    /** Launcher file suffix — {@code .command} on macOS (so Terminal runs it), {@code .sh} on Linux. */
    protected String launcherSuffix() {
        return ".sh";
    }

    private void launchClaude(String claudeCommand, String workingDir) {
        String dir = isDir(workingDir) ? workingDir : System.getProperty("user.home");
        openTerminal(dir, writeLauncher(dir, claudeCommand));
    }

    private Path writeLauncher(String dir, String claudeCommand) {
        // `exec "$SHELL"` keeps the window open after claude exits (the Unix analogue of `cmd /k`).
        String content = "#!/bin/sh\n"
                + "cd " + shellSingleQuote(dir) + " || exit 1\n"
                + claudeCommand + "\n"
                + "exec \"${SHELL:-/bin/sh}\"\n";
        try {
            Path file = Files.createTempFile("claude-launch-", launcherSuffix());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            file.toFile().setExecutable(true, false);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Wrap a value in a POSIX single-quoted string literal (safe for arbitrary paths). */
    protected static String shellSingleQuote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    protected static boolean isDir(String path) {
        return path != null && !path.isBlank() && new File(path).isDirectory();
    }

    /** First directory on {@code PATH} containing an executable named {@code exe}, or {@code null}. */
    protected static String findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, exe);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getPath();
            }
        }
        return null;
    }

    /** Default {@code PATH}-scan predicate; tests inject a fake to stay deterministic off-platform. */
    static final Predicate<String> ON_PATH = exe -> findOnPath(exe) != null;
}
