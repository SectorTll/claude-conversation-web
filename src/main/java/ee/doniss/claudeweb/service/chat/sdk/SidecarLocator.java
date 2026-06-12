package ee.doniss.claudeweb.service.chat.sdk;

import ee.doniss.claudeweb.config.ClaudeProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Finds the sidecar bundle to run, in order: explicit {@code claude.chat.sidecar-path} config →
 * the dev checkout's {@code sidecar/dist/sidecar.mjs} (instant rebuild loop, no extraction) →
 * the bundle baked into the jar, extracted to {@code _claude-sidecar/} next to the server
 * (overwritten on every start so a new jar never runs a stale bundle).
 */
@Component
public class SidecarLocator {

    private static final String RESOURCE = "/sidecar/sidecar.mjs";

    private final ClaudeProperties props;

    /** Set after the first extraction this run — later calls reuse the file without re-copying. */
    private boolean extractedThisRun;

    public SidecarLocator(ClaudeProperties props) {
        this.props = props;
    }

    /**
     * Resolve a bare executable name to an absolute path via PATH (+PATHEXT on Windows). The
     * sidecar's SDK spawns the CLI itself and a bare name has proven flaky there (and its launch
     * errors get reported misleadingly as "not found") — handing it a concrete path removes the
     * whole class. Names that already contain a path separator are returned as-is.
     */
    public static String resolveExecutable(String configured) {
        if (configured == null || configured.isBlank()
                || configured.contains("/") || configured.contains("\\")) {
            return configured;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return configured;
        }
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String[] exts = windows ? new String[]{".exe", ".cmd", ".bat", ""} : new String[]{""};
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : exts) {
                Path candidate = Path.of(dir, configured + ext);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }
        return configured; // not on PATH — let the spawn produce the real error
    }

    /** Absolute path of the bundle to run; throws if none can be found. */
    public Path locate() {
        String explicit = props.getChat().getSidecarPath();
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("claude.chat.sidecar-path does not exist: " + p);
            }
            return p;
        }

        Path dev = Path.of(System.getProperty("user.dir"), "sidecar", "dist", "sidecar.mjs");
        if (Files.isRegularFile(dev)) {
            return dev.toAbsolutePath().normalize();
        }

        return extracted();
    }

    private synchronized Path extracted() {
        Path target = Path.of(System.getProperty("user.dir"), "_claude-sidecar", "sidecar.mjs")
                .toAbsolutePath().normalize();
        if (extractedThisRun) {
            return target;
        }
        // Always overwrite on the first use of a run: a leftover from a previous (older) jar must
        // never outlive an upgrade.
        try (InputStream in = SidecarLocator.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("sidecar bundle not found: not in " + RESOURCE
                        + " on the classpath, no sidecar/dist/sidecar.mjs in " + System.getProperty("user.dir")
                        + ", and claude.chat.sidecar-path is not set");
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            extractedThisRun = true;
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
