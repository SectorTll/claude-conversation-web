package ee.doniss.claudeweb.os;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Test fixture for {@code StreamingProcessRunnerTest}: a child process that echoes every stdin line
 * back as {@code echo:<line>} and exits 0 on EOF. Launched as a second JVM so the interactive
 * (long-lived, bidirectional) process contract is exercised for real, cross-platform. I/O is
 * explicitly UTF-8 on both sides — the parent writes/reads UTF-8 and the platform default codepage
 * (notably on Windows) must not corrupt the round-trip.
 */
public final class EchoStdinMain {

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            out.println("echo:" + line);
        }
    }
}
