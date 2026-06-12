package ee.doniss.claudeweb.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-push subscription endpoints against a temp push store (VAPID keys generated on demand). */
@SpringBootTest
@AutoConfigureMockMvc
class PushIntegrationTest {

    static Path testHome;
    static Path pushStore;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void claudeProps(DynamicPropertyRegistry registry) throws IOException {
        testHome = Files.createTempDirectory("claude-home-push-it");
        pushStore = Files.createTempDirectory("claude-push-it");
        Files.createDirectories(testHome.resolve("projects"));

        registry.add("claude.home", () -> testHome.toString());
        registry.add("claude.push.store", () -> pushStore.toString());
        registry.add("claude.os-integration", () -> "noop");
        registry.add("claude.auto-open-browser", () -> "false");
        registry.add("claude.security.enabled", () -> "false");
    }

    @Test
    void keyGeneratesAndPersistsTheVapidKeypair() throws Exception {
        mvc.perform(get("/api/push/key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(not(emptyString())));
        assertTrue(Files.isRegularFile(pushStore.resolve("vapid.keys")), "keypair persisted on first use");
    }

    @Test
    void subscribePersistsAndUnsubscribeRemoves() throws Exception {
        mvc.perform(post("/api/push/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example/abc\","
                                + "\"keys\":{\"p256dh\":\"pk\",\"auth\":\"at\"}}"))
                .andExpect(status().isNoContent());
        String stored = Files.readString(pushStore.resolve("subscriptions.json"), StandardCharsets.UTF_8);
        assertTrue(stored.contains("https://push.example/abc"));

        mvc.perform(post("/api/push/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example/abc\"}"))
                .andExpect(status().isNoContent());
        stored = Files.readString(pushStore.resolve("subscriptions.json"), StandardCharsets.UTF_8);
        assertFalse(stored.contains("https://push.example/abc"));
    }

    @Test
    void subscribeWithoutKeysIsBadRequest() throws Exception {
        mvc.perform(post("/api/push/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example/abc\"}"))
                .andExpect(status().isBadRequest());
    }
}
