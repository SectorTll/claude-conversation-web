package ee.doniss.claudeweb.os;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Launches a process and streams its stdout line-by-line as it arrives, instead of buffering the
 * whole output and waiting like {@link ProcessRunner#run}. Used for long-running, streaming
 * {@code claude -p} chat turns.
 *
 * <p>The prompt is written to the child's stdin as UTF-8 (and the stream closed so the CLI sees EOF
 * and starts the turn): this avoids the Windows argv codepage mangling that would corrupt Cyrillic
 * prompts if passed as a command-line argument. stdout/stderr are read as UTF-8; stderr is kept on a
 * separate stream so a CLI warning there never corrupts the NDJSON on stdout.
 */
@Component
public class StreamingProcessRunner {

    /** Max stderr retained for diagnostics (a runaway child must not OOM us). */
    private static final int MAX_STDERR = 16_384;

    /** A running process: the {@link Process} (for cancellation) and a future of its exit code. */
    public static sealed class Handle permits InteractiveHandle {
        private final Process process;
        private final CompletableFuture<Integer> exit;
        private final StringBuilder stderr;

        Handle(Process process, CompletableFuture<Integer> exit, StringBuilder stderr) {
            this.process = process;
            this.exit = exit;
            this.stderr = stderr;
        }

        public Process process() { return process; }

        /** Completes with the exit code once stdout reaches EOF and the process ends. */
        public CompletableFuture<Integer> exit() { return exit; }

        /** Accumulated stderr so far (UTF-8, truncated), for error diagnostics. */
        public String stderr() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }
    }

    /**
     * A {@link Handle} whose stdin stays open for the process' lifetime, for long-lived children
     * that consume a line-based protocol (the chat sidecar). Writes are serialized and flushed per
     * line so a message is never interleaved or stuck in a buffer.
     */
    public static final class InteractiveHandle extends Handle {
        private final OutputStream stdin;

        InteractiveHandle(Process process, CompletableFuture<Integer> exit, StringBuilder stderr) {
            super(process, exit, stderr);
            this.stdin = process.getOutputStream();
        }

        /** Write one protocol line (UTF-8 + {@code \n}, flushed). Throws if the child is gone. */
        public void writeLine(String line) {
            try {
                synchronized (stdin) {
                    stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("sidecar stdin write failed", e);
            }
        }

        /** Close stdin so the child sees EOF (the polite shutdown signal). Idempotent. */
        public void closeStdin() {
            try {
                synchronized (stdin) {
                    stdin.close();
                }
            } catch (IOException ignore) {
                // already closed / child gone
            }
        }
    }

    /**
     * Start {@code command} in {@code workingDir}, feed {@code stdin} (may be {@code null}), and
     * invoke {@code onStdoutLine} for every line of stdout. Reader tasks run on {@code executor}.
     */
    public Handle start(List<String> command, String workingDir, String stdin,
                        Consumer<String> onStdoutLine, Executor executor) {
        Process p = launch(command, workingDir);

        // Feed the prompt via stdin as UTF-8, then close so the CLI sees EOF and runs the turn.
        // Prompts are small (well under the OS pipe buffer), so writing inline can't deadlock.
        try (OutputStream os = p.getOutputStream()) {
            if (stdin != null) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignore) {
            // child may have already exited; the exit code will surface the failure
        }

        StringBuilder stderr = new StringBuilder();
        CompletableFuture<Integer> exit = new CompletableFuture<>();

        executor.execute(() -> drainStderr(p, stderr));
        executor.execute(() -> readStdout(p, onStdoutLine, exit));

        return new Handle(p, exit, stderr);
    }

    /**
     * Like {@link #start} but stdin is kept open for ongoing {@link InteractiveHandle#writeLine}
     * calls — the mode used for the long-lived chat sidecar.
     */
    public InteractiveHandle startInteractive(List<String> command, String workingDir,
                                              Consumer<String> onStdoutLine, Executor executor) {
        Process p = launch(command, workingDir);

        StringBuilder stderr = new StringBuilder();
        CompletableFuture<Integer> exit = new CompletableFuture<>();

        executor.execute(() -> drainStderr(p, stderr));
        executor.execute(() -> readStdout(p, onStdoutLine, exit));

        return new InteractiveHandle(p, exit, stderr);
    }

    private static Process launch(List<String> command, String workingDir) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null && !workingDir.isBlank()) {
            File dir = new File(workingDir);
            if (dir.isDirectory()) {
                pb.directory(dir);
            }
        }
        try {
            return pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Terminate a process and its descendants forcibly. The headless {@code claude} spawns children,
     * so destroying only the parent can orphan the real work — reach the whole tree.
     */
    public static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void readStdout(Process p, Consumer<String> onStdoutLine, CompletableFuture<Integer> exit) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    onStdoutLine.accept(line);
                } catch (RuntimeException ignore) {
                    // a consumer error (e.g. the emitter was closed) must not stop us draining the pipe
                }
            }
        } catch (IOException ignore) {
            // pipe closed / process gone
        }
        try {
            exit.complete(p.waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit.complete(-1);
        }
    }

    private void drainStderr(Process p, StringBuilder stderr) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (stderr) {
                    if (stderr.length() < MAX_STDERR) {
                        stderr.append(line).append('\n');
                    }
                }
            }
        } catch (IOException ignore) {
            // process gone
        }
    }
}
