package ee.doniss.claudeweb.os;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Thin, mockable wrapper around {@link ProcessBuilder}. */
@Component
public class ProcessRunner {

    public record Result(boolean ok, String output) {
    }

    /** Fire-and-forget GUI / terminal launch (no output captured). */
    public void launch(List<String> command, String workingDir) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null && !workingDir.isBlank()) {
            File dir = new File(workingDir);
            if (dir.isDirectory()) {
                pb.directory(dir);
            }
        }
        try {
            pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Run synchronously, wait up to 60s. {@code output} is clean stdout on success (so callers can
     * parse JSON); on failure stderr is appended for diagnostics. stderr is drained on a separate
     * thread to avoid pipe-buffer deadlock.
     */
    public Result run(List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        try {
            Process p = pb.start();
            AtomicReference<byte[]> errBytes = new AtomicReference<>(new byte[0]);
            Thread errDrain = new Thread(() -> {
                try {
                    errBytes.set(p.getErrorStream().readAllBytes());
                } catch (IOException ignore) {
                    // process ended
                }
            });
            errDrain.setDaemon(true);
            errDrain.start();

            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            errDrain.join(2000);
            String err = new String(errBytes.get(), StandardCharsets.UTF_8);

            if (!done) {
                p.destroyForcibly();
                return new Result(false, (out + "\n" + err + "\n(timed out)").strip());
            }
            boolean ok = p.exitValue() == 0;
            return new Result(ok, (ok ? out : out + "\n" + err).strip());
        } catch (IOException e) {
            return new Result(false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        }
    }
}
