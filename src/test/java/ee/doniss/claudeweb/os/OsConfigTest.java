package ee.doniss.claudeweb.os;

import ee.doniss.claudeweb.config.ClaudeProperties;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** {@link OsConfig} bean selection: explicit modes win; {@code auto}/null detect from {@code os.name}. */
class OsConfigTest {

    private final OsConfig config = new OsConfig();
    private final ProcessRunner runner = new ProcessRunner();

    private OsIntegration build(String mode) {
        ClaudeProperties props = new ClaudeProperties();
        props.setOsIntegration(mode);
        return config.osIntegration(props, runner);
    }

    @Test
    void windowsModeSelectsWindows() {
        assertInstanceOf(WindowsOsIntegration.class, build("windows"));
    }

    @Test
    void macModeSelectsMac() {
        assertInstanceOf(MacOsIntegration.class, build("mac"));
    }

    @Test
    void linuxModeSelectsLinux() {
        assertInstanceOf(LinuxOsIntegration.class, build("linux"));
    }

    @Test
    void noopModeSelectsNoop() {
        assertInstanceOf(NoopOsIntegration.class, build("noop"));
    }

    @Test
    void modeIsCaseInsensitive() {
        assertInstanceOf(MacOsIntegration.class, build("MAC"));
    }

    @Test
    void nullModeFallsBackToAutoDetect() {
        // null → auto; the dev/CI box is always a real OS, so the result is never Noop.
        ClaudeProperties props = new ClaudeProperties();
        props.setOsIntegration(null);
        assertFalse(config.osIntegration(props, runner) instanceof NoopOsIntegration);
    }

    @Test
    void autoMatchesCurrentOs() {
        OsIntegration os = build("auto");
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            assertInstanceOf(WindowsOsIntegration.class, os);
        } else if (name.contains("mac") || name.contains("darwin")) {
            assertInstanceOf(MacOsIntegration.class, os);
        } else {
            assertInstanceOf(LinuxOsIntegration.class, os); // Linux CI
        }
    }
}
