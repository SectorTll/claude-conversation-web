package ee.doniss.claudeweb.os;

import ee.doniss.claudeweb.os.StreamingProcessRunner.InteractiveHandle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Fakes for tests that need an {@link InteractiveHandle} without spawning a real child process
 * (its constructor is package-private to this package on purpose). The fake process captures
 * everything written to its stdin so protocol lines can be asserted on.
 */
public final class TestProcesses {

    private TestProcesses() {
    }

    public static InteractiveHandle interactiveHandle(FakeProcess process) {
        return new InteractiveHandle(process, new CompletableFuture<>(), new StringBuilder());
    }

    /** An "alive" process whose stdin is a capture buffer; {@code destroyForcibly} just flips alive. */
    public static final class FakeProcess extends Process {
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private volatile boolean alive = true;

        /** Everything written to the process' stdin so far, as UTF-8. */
        public String stdinText() {
            return stdin.toString(StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
            return this;
        }

        /** The default implementation needs a real PID — fakes have no descendants. */
        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }
}
