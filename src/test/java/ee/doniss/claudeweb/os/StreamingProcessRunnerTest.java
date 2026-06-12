package ee.doniss.claudeweb.os;

import ee.doniss.claudeweb.os.StreamingProcessRunner.InteractiveHandle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises the interactive (keep-stdin-open) mode against a real child process — a second JVM
 * running {@link EchoStdinMain} — because the contract under test is exactly the OS-level pipe
 * behaviour: multiple writes over time, per-line flushing, UTF-8 fidelity, EOF shutdown.
 */
@Timeout(30)
class StreamingProcessRunnerTest {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "runner-test");
        t.setDaemon(true);
        return t;
    });

    @AfterAll
    static void shutdownExecutor() {
        EXECUTOR.shutdownNow();
    }

    private static List<String> echoCommand() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"),
                EchoStdinMain.class.getName());
    }

    @Test
    void interactiveProcessEchoesMultipleWritesAndExitsCleanlyOnStdinClose() throws Exception {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        InteractiveHandle h = new StreamingProcessRunner()
                .startInteractive(echoCommand(), null, lines::add, EXECUTOR);
        try {
            h.writeLine("hello");
            assertEquals("echo:hello", lines.poll(15, TimeUnit.SECONDS),
                    "first line is flushed through immediately, not buffered until exit");

            h.writeLine("привет мир"); // Cyrillic must survive the pipe (UTF-8, not the argv codepage)
            assertEquals("echo:привет мир", lines.poll(15, TimeUnit.SECONDS));

            h.writeLine("third");
            assertEquals("echo:third", lines.poll(15, TimeUnit.SECONDS),
                    "the process stays alive across writes — that is the whole point of interactive mode");

            h.closeStdin();
            Integer exit = h.exit().get(15, TimeUnit.SECONDS);
            assertNotNull(exit);
            assertEquals(0, exit, "EOF on stdin is the polite shutdown signal");
        } finally {
            StreamingProcessRunner.killTree(h.process());
        }
    }

    @Test
    void closeStdinIsIdempotent() throws Exception {
        InteractiveHandle h = new StreamingProcessRunner()
                .startInteractive(echoCommand(), null, line -> { }, EXECUTOR);
        try {
            h.closeStdin();
            h.closeStdin(); // second close must not throw
            assertEquals(0, h.exit().get(15, TimeUnit.SECONDS));
        } finally {
            StreamingProcessRunner.killTree(h.process());
        }
    }
}
