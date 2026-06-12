package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.support.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime-editable Telegram notification settings, surfaced by {@code /api/settings/telegram}.
 *
 * <p>Telegram config arrives from {@code application.yml} + {@code CLAUDE_TELEGRAM_BOT_TOKEN} at
 * startup; this service lets an authenticated browser change it live (the {@link TelegramSender}
 * re-reads {@link ClaudeProperties} on every send, so a mutation takes effect on the next ping with
 * no restart) and persists the change to {@code settings.json} so it survives one.
 *
 * <p>Precedence: a stored {@code settings.json}, once written, owns {@code enabled}/{@code chatId}/
 * {@code publicBaseUrl}, and the bot token <em>only when set through the UI</em>. An env/yml-provided
 * token is deliberately NOT copied to disk — leaving the form's token field blank keeps whatever
 * token is already in effect, and the env var stays the single source for it.
 */
@Service
public class TelegramSettingsService {

    private static final Logger log = LoggerFactory.getLogger(TelegramSettingsService.class);

    /** The on-disk shape. Object-typed fields so an absent key reads as {@code null} (= "leave as is"). */
    record Stored(Boolean enabled, String botToken, String chatId, String publicBaseUrl) {
    }

    /** What the browser sees — never the token itself, only whether one is set and from where. */
    public record TelegramView(boolean enabled, String chatId, String publicBaseUrl,
                               boolean tokenSet, boolean tokenFromEnv) {
    }

    /** Outcome of the "send test" button: a Telegram round-trip with a human-readable detail line. */
    public record TestOutcome(boolean ok, String detail) {
    }

    private final ClaudeProperties props;
    private final ObjectMapper mapper;
    private final TelegramSender sender;

    /** The token persisted in {@code settings.json} (UI-set). Blank/null = "use env/yml token, if any". */
    private String persistedToken;
    private boolean loaded;

    public TelegramSettingsService(ClaudeProperties props, ObjectMapper mapper, TelegramSender sender) {
        this.props = props;
        this.mapper = mapper;
        this.sender = sender;
    }

    /** Apply any stored overrides onto the live properties before the first WAITING event can fire. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void applyOnStartup() {
        load();
    }

    public synchronized TelegramView view() {
        load();
        return currentView();
    }

    /**
     * @param enabled       whether Telegram pings are on
     * @param chatId        the target chat id (your user id or a group id)
     * @param publicBaseUrl absolute origin for the deep link appended to messages (blank = no link)
     * @param newToken      a freshly entered bot token, or {@code null}/blank to keep the current one
     * @param clearToken    drop the stored token entirely (overrides {@code newToken})
     */
    public synchronized TelegramView update(boolean enabled, String chatId, String publicBaseUrl,
                                            String newToken, boolean clearToken) {
        load();
        ClaudeProperties.Telegram t = props.getTelegram();
        t.setEnabled(enabled);
        t.setChatId(nullToEmpty(chatId).trim());
        props.getPush().setPublicBaseUrl(nullToEmpty(publicBaseUrl).trim());

        if (clearToken) {
            t.setBotToken("");
            persistedToken = null;
        } else if (newToken != null && !newToken.isBlank()) {
            t.setBotToken(newToken.trim());
            persistedToken = newToken.trim();
        }
        // else: token field left blank — keep whatever token is already in effect (env or prior UI).

        save(new Stored(t.isEnabled(), persistedToken, t.getChatId(), props.getPush().getPublicBaseUrl()));
        return currentView();
    }

    /** Fire a one-off message with the current settings so the user can confirm token + chat id. */
    public synchronized TestOutcome sendTest() {
        load();
        ClaudeProperties.Telegram t = props.getTelegram();
        if (t.getBotToken().isBlank() || t.getChatId().isBlank()) {
            throw new BadRequestException("Set a bot token and chat id first");
        }
        WaitingEvent event = new WaitingEvent("demo", "demo", "permission",
                "Test from Claude Web", "Telegram notifications are working ✅", "/");
        try {
            TelegramSender.HttpResult r = sender.sendForResult(event);
            boolean ok = r.statusCode() / 100 == 2;
            return new TestOutcome(ok, ok
                    ? "Sent — check your Telegram"
                    : "Telegram API returned " + r.statusCode() + ": " + truncate(r.body()));
        } catch (RuntimeException e) {
            return new TestOutcome(false, "Request failed: " + e.getMessage());
        }
    }

    private TelegramView currentView() {
        ClaudeProperties.Telegram t = props.getTelegram();
        boolean tokenSet = !t.getBotToken().isBlank();
        boolean tokenFromEnv = tokenSet && (persistedToken == null || persistedToken.isBlank());
        return new TelegramView(t.isEnabled(), t.getChatId(),
                props.getPush().getPublicBaseUrl(), tokenSet, tokenFromEnv);
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Stored stored = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            if (stored == null) {
                return;
            }
            ClaudeProperties.Telegram t = props.getTelegram();
            if (stored.enabled() != null) {
                t.setEnabled(stored.enabled());
            }
            if (stored.chatId() != null) {
                t.setChatId(stored.chatId());
            }
            if (stored.publicBaseUrl() != null) {
                props.getPush().setPublicBaseUrl(stored.publicBaseUrl());
            }
            if (stored.botToken() != null && !stored.botToken().isBlank()) {
                persistedToken = stored.botToken();
                t.setBotToken(stored.botToken());
            }
        } catch (Exception e) {
            log.warn("could not read {} — keeping yml/env Telegram settings: {}", file, e.toString());
        }
    }

    private void save(Stored stored) {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(stored), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file() {
        return props.resolvedTelegramStore().resolve("settings.json");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
