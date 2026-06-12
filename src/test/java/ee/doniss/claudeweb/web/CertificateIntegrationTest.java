package ee.doniss.claudeweb.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Certificate distribution with the LOGIN WALL ON: a fresh LAN client must be able to fetch the
 * cert + installer before it has any session (that's the whole point), while /api stays 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateIntegrationTest {

    static Path testHome;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void claudeProps(DynamicPropertyRegistry registry) throws IOException {
        testHome = Files.createTempDirectory("claude-home-cert-it");
        Files.createDirectories(testHome.resolve("projects"));

        registry.add("claude.home", () -> testHome.toString());
        registry.add("claude.os-integration", () -> "noop");
        registry.add("claude.auto-open-browser", () -> "false");
        // Wall ON — /cert must still answer pre-login.
        registry.add("claude.security.enabled", () -> "true");
        registry.add("claude.security.password", () -> "test-password");
        registry.add("claude.security.enforce-ip-allowlist", () -> "false");
        // Read the certificate from the committed test fixture (junk key; no TLS listener runs).
        registry.add("server.ssl.key-store", () -> "classpath:certs/test-keystore.p12");
        registry.add("server.ssl.key-store-password", () -> "changeit");
        registry.add("server.ssl.key-alias", () -> "claudeweb");
    }

    @Test
    void certificateDownloadsPreLoginAsDer() throws Exception {
        MvcResult res = mvc.perform(get("/cert/claudeweb.cer"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-x509-ca-cert"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"claudeweb.cer\""))
                .andReturn();
        byte[] der = res.getResponse().getContentAsByteArray();
        assertTrue(der.length > 100);
        assertEquals(0x30, der[0] & 0xFF, "DER SEQUENCE tag");
    }

    @Test
    void installerScriptEmbedsThePemAndImportsViaCertutil() throws Exception {
        MvcResult res = mvc.perform(get("/cert/install-cert.bat"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"install-cert.bat\""))
                .andReturn();
        String script = res.getResponse().getContentAsString(StandardCharsets.US_ASCII);
        assertTrue(script.contains("certutil -addstore -user Root"));
        assertTrue(script.contains("-----BEGIN CERTIFICATE-----"));
        assertTrue(script.contains("-----END CERTIFICATE-----"));
        assertTrue(script.contains("\r\n"), "batch files need CRLF line endings");
    }

    @Test
    void apiStaysWalledWhileCertIsOpen() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    }
}
