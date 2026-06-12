package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.service.notify.TelegramSettingsService;
import ee.doniss.claudeweb.service.notify.TelegramSettingsService.TelegramView;
import ee.doniss.claudeweb.service.notify.TelegramSettingsService.TestOutcome;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read/write the Telegram notification settings from the browser (behind the auth wall like the rest
 * of {@code /api}). The bot token is write-only here — it is never echoed back, only a {@code tokenSet}
 * flag is. See {@link TelegramSettingsService} for the env-vs-UI precedence rules.
 */
@RestController
@RequestMapping("/api/settings/telegram")
public class TelegramSettingsController {

    /**
     * @param enabled       turn Telegram pings on/off
     * @param chatId        the chat to notify
     * @param publicBaseUrl absolute origin for the deep link in messages (blank = none)
     * @param botToken      a new bot token, or null/omitted to keep the current one
     * @param clearToken    remove the stored token (wins over {@code botToken})
     */
    public record UpdateRequest(boolean enabled, String chatId, String publicBaseUrl,
                                String botToken, boolean clearToken) {
    }

    private final TelegramSettingsService service;

    public TelegramSettingsController(TelegramSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public TelegramView get() {
        return service.view();
    }

    @PostMapping
    public TelegramView update(@RequestBody UpdateRequest req) {
        return service.update(req.enabled(), req.chatId(), req.publicBaseUrl(),
                req.botToken(), req.clearToken());
    }

    @PostMapping("/test")
    public TestOutcome test() {
        return service.sendTest();
    }
}
