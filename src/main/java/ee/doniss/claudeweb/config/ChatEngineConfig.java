package ee.doniss.claudeweb.config;

import ee.doniss.claudeweb.os.ProcessRunner;
import ee.doniss.claudeweb.service.chat.ChatEngine;
import ee.doniss.claudeweb.service.chat.CliChatEngine;
import ee.doniss.claudeweb.service.chat.sdk.SdkChatEngine;
import ee.doniss.claudeweb.service.chat.sdk.SidecarLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Picks the chat backend from {@code claude.chat.engine}. Both engines exist as beans; the
 * {@code @Primary} one is what {@code ChatController}/{@code LivePoller} inject as {@link ChatEngine}.
 *
 * <p>The {@code sdk} engine needs Node and the sidecar bundle at runtime. Rather than failing the
 * first chat turn with a launch error, the startup probe falls back to the {@code cli} engine (with
 * a WARN) when either is missing — the capabilities endpoint then reports {@code cli} and the
 * composer adapts automatically.
 */
@Configuration
public class ChatEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatEngineConfig.class);

    @Bean
    @Primary
    public ChatEngine chatEngine(ClaudeProperties props, CliChatEngine cli, SdkChatEngine sdk,
                                 SidecarLocator locator, ProcessRunner processRunner) {
        return switch (props.getChat().getEngine()) {
            case "cli" -> cli;
            case "sdk" -> sdkOrFallback(props, sdk, cli, locator, processRunner);
            default -> throw new IllegalStateException(
                    "unknown claude.chat.engine: " + props.getChat().getEngine() + " (expected sdk or cli)");
        };
    }

    private ChatEngine sdkOrFallback(ClaudeProperties props, SdkChatEngine sdk, CliChatEngine cli,
                                     SidecarLocator locator, ProcessRunner runner) {
        try {
            locator.locate();
        } catch (RuntimeException e) {
            log.warn("chat engine sdk unavailable ({}) — falling back to the cli engine", e.getMessage());
            return cli;
        }
        String node = props.getChat().getNodeExecutable();
        ProcessRunner.Result nodeVersion = runner.run(List.of(node, "--version"));
        if (!nodeVersion.ok()) {
            log.warn("chat engine sdk unavailable ({} --version failed: {}) — falling back to the cli engine",
                    node, nodeVersion.output().strip());
            return cli;
        }
        // Best-effort version line for diagnosing SDK/CLI drift (the sidecar logs its SDK version on ready).
        ProcessRunner.Result claudeVersion = runner.run(List.of(props.getChat().getExecutable(), "--version"));
        log.info("chat engine: sdk (node {}, claude {})", nodeVersion.output().strip(),
                claudeVersion.ok() ? claudeVersion.output().strip() : "version unknown");
        return sdk;
    }
}
