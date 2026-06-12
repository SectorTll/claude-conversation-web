package ee.doniss.claudeweb.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the login wall: gated endpoints need a session cookie; the auth endpoints are open. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    static final String PASSWORD = "s3cret";

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path home = Files.createTempDirectory("claude-home-sec");
        registry.add("claude.home", () -> home.toString());
        registry.add("claude.os-integration", () -> "noop");
        registry.add("claude.auto-open-browser", () -> "false");
        // Security ON (default) with a known password; IP allowlist off so MockMvc isn't blocked.
        registry.add("claude.security.enabled", () -> "true");
        registry.add("claude.security.password", () -> PASSWORD);
        registry.add("claude.security.enforce-ip-allowlist", () -> "false");
    }

    @Test
    void statusIsFalseWhenUnauthenticated() throws Exception {
        mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void gatedEndpointWithoutCookieReturns401() throws Exception {
        mvc.perform(get("/api/actions/capabilities"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginGrantsAccessAndLogoutRevokesIt() throws Exception {
        Cookie session = login();

        // With the cookie, the gated endpoint is reachable.
        mvc.perform(get("/api/actions/capabilities").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions").value(false));

        mvc.perform(get("/api/auth/status").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));

        // After logout the token is revoked and the gate closes again.
        mvc.perform(post("/api/auth/logout").cookie(session))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/actions/capabilities").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    private Cookie login() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        String setCookie = res.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie, "login must return a Set-Cookie header");
        String token = setCookie.substring("SESSION=".length(), setCookie.indexOf(';'));
        return new Cookie("SESSION", token);
    }
}
