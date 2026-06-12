package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.support.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramSettingsServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path storeDir;

    private final List<HttpRequest> requests = new ArrayList<>();
    private TelegramSender.HttpResult nextResult = new TelegramSender.HttpResult(200, "ok");
    private final Function<HttpRequest, TelegramSender.HttpResult> http = request -> {
        requests.add(request);
        return nextResult;
    };

    private ClaudeProperties props() {
        ClaudeProperties props = new ClaudeProperties();
        props.getTelegram().setStore(storeDir);
        return props;
    }

    private TelegramSettingsService service(ClaudeProperties props) {
        return new TelegramSettingsService(props, MAPPER, new TelegramSender(props, MAPPER, http));
    }

    @Test
    void viewHidesTokenAndReportsEnvSource() {
        ClaudeProperties props = props();
        props.getTelegram().setBotToken("env:tok"); // as if from CLAUDE_TELEGRAM_BOT_TOKEN
        props.getTelegram().setChatId("42");

        var view = service(props).view();

        assertTrue(view.tokenSet());
        assertTrue(view.tokenFromEnv(), "env token is in effect but not UI-managed");
        assertEquals("42", view.chatId());
    }

    @Test
    void updatePersistsSettingsWithoutLeakingTheEnvToken() throws Exception {
        ClaudeProperties props = props();
        props.getTelegram().setBotToken("env:tok");

        var view = service(props).update(true, "42", "https://h:8443", null, false);

        assertTrue(view.enabled());
        assertEquals("42", view.chatId());
        assertEquals("https://h:8443", view.publicBaseUrl());
        assertTrue(view.tokenSet());
        assertTrue(view.tokenFromEnv());
        assertEquals("env:tok", props.getTelegram().getBotToken(), "env token kept in effect");

        JsonNode stored = readStore();
        assertTrue(stored.get("botToken").isNull(), "env token must not be written to disk");
        assertEquals("42", stored.get("chatId").asText());
        assertTrue(stored.get("enabled").asBoolean());
    }

    @Test
    void newTokenIsPersistedAndReappliedOnReload() {
        ClaudeProperties first = props();
        first.getTelegram().setChatId("42");
        service(first).update(true, "42", "", "123:abc", false);

        // A fresh service over fresh props (server restart) must pick the UI token up from disk.
        ClaudeProperties restarted = props();
        var view = service(restarted).view();

        assertEquals("123:abc", restarted.getTelegram().getBotToken());
        assertTrue(view.tokenSet());
        assertFalse(view.tokenFromEnv(), "token is UI-managed now");
        assertTrue(view.enabled());
    }

    @Test
    void clearTokenRemovesIt() {
        ClaudeProperties props = props();
        TelegramSettingsService svc = service(props);
        svc.update(true, "42", "", "123:abc", false);

        var view = svc.update(true, "42", "", null, true);

        assertFalse(view.tokenSet());
        assertTrue(props.getTelegram().getBotToken().isBlank());
    }

    @Test
    void blankTokenKeepsTheExistingOne() {
        ClaudeProperties props = props();
        TelegramSettingsService svc = service(props);
        svc.update(true, "42", "", "123:abc", false);

        svc.update(false, "99", "", null, false);

        assertEquals("123:abc", props.getTelegram().getBotToken());
        assertEquals("99", props.getTelegram().getChatId());
        assertFalse(props.getTelegram().isEnabled());
    }

    @Test
    void startupAppliesStoredSettingsOverEnv() throws Exception {
        Files.createDirectories(storeDir);
        Files.writeString(storeDir.resolve("settings.json"),
                "{\"enabled\":true,\"botToken\":\"ui:tok\",\"chatId\":\"7\",\"publicBaseUrl\":\"\"}",
                StandardCharsets.UTF_8);

        ClaudeProperties props = props();
        props.getTelegram().setBotToken("env:tok");
        props.getTelegram().setEnabled(false);

        service(props).applyOnStartup();

        assertTrue(props.getTelegram().isEnabled());
        assertEquals("ui:tok", props.getTelegram().getBotToken());
        assertEquals("7", props.getTelegram().getChatId());
    }

    @Test
    void sendTestValidatesConfigThenPosts() {
        ClaudeProperties props = props();
        TelegramSettingsService svc = service(props);
        assertThrows(BadRequestException.class, svc::sendTest);

        svc.update(true, "42", "", "123:abc", false);
        var outcome = svc.sendTest();

        assertTrue(outcome.ok());
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).uri().toString().contains("/bot123:abc/sendMessage"));
    }

    @Test
    void sendTestSurfacesABadTokenResponse() {
        ClaudeProperties props = props();
        TelegramSettingsService svc = service(props);
        svc.update(true, "42", "", "bad", false);
        nextResult = new TelegramSender.HttpResult(401, "Unauthorized");

        var outcome = svc.sendTest();

        assertFalse(outcome.ok());
        assertTrue(outcome.detail().contains("401"));
    }

    private JsonNode readStore() throws Exception {
        return MAPPER.readTree(Files.readString(storeDir.resolve("settings.json"), StandardCharsets.UTF_8));
    }
}
