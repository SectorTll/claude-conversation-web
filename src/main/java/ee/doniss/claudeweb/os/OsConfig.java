package ee.doniss.claudeweb.os;

import ee.doniss.claudeweb.config.ClaudeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Chooses the OS-integration implementation from {@code claude.os-integration}
 * ({@code auto|windows|mac|linux|noop}). {@code auto} detects from {@code os.name}; an unknown OS
 * (or {@code noop}) falls back to {@link NoopOsIntegration} (actions report 501).
 */
@Configuration
public class OsConfig {

    private static final Logger log = LoggerFactory.getLogger(OsConfig.class);

    @Bean
    public OsIntegration osIntegration(ClaudeProperties props, ProcessRunner runner) {
        String mode = props.getOsIntegration() == null ? "auto" : props.getOsIntegration().toLowerCase(Locale.ROOT);
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        String target = switch (mode) {
            case "windows", "mac", "linux", "noop" -> mode;
            default -> detect(osName); // "auto" or anything unexpected
        };

        OsIntegration os = switch (target) {
            case "windows" -> new WindowsOsIntegration(runner);
            case "mac" -> new MacOsIntegration(runner);
            case "linux" -> new LinuxOsIntegration(runner);
            default -> new NoopOsIntegration();
        };
        log.info("OS integration: {} (mode={}, os.name={})", target, mode, System.getProperty("os.name"));
        return os;
    }

    private static String detect(String osName) {
        if (osName.contains("win")) {
            return "windows";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "mac";
        }
        if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
            return "linux";
        }
        return "noop";
    }
}
