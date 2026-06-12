package ee.doniss.claudeweb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/** Opens the default browser at the app URL once on startup (toggle via {@code claude.auto-open-browser}). */
@Component
public class StartupBrowserOpener implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBrowserOpener.class);

    private final ClaudeProperties props;
    private final int port;
    private final boolean ssl;

    public StartupBrowserOpener(ClaudeProperties props,
                                @Value("${server.port:8080}") int port,
                                @Value("${server.ssl.enabled:false}") boolean ssl) {
        this.props = props;
        this.port = port;
        this.ssl = ssl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isAutoOpenBrowser()) {
            return;
        }
        String url = (ssl ? "https" : "http") + "://127.0.0.1:" + port + "/";
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            // Spring Boot runs headless by default, so java.awt.Desktop is usually unavailable — prefer
            // the native per-OS opener and keep Desktop.browse only as a last-resort fallback.
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac") || os.contains("darwin")) {
                new ProcessBuilder("open", url).start();
            } else if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
                new ProcessBuilder("xdg-open", url).start();
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
            log.info("Opened {} in the browser", url);
        } catch (Exception e) {
            log.info("Could not open browser at {} ({}). Open it manually.", url, e.getMessage());
        }
    }
}
