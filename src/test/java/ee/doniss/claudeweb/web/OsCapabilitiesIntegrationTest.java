package ee.doniss.claudeweb.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * On a supported non-Windows platform the OS actions are enabled but scheduling stays Windows-only.
 * Forcing {@code claude.os-integration=linux} wires {@link ee.doniss.claudeweb.os.LinuxOsIntegration}
 * (construction is side-effect-free, so this is safe to boot on any host); the capabilities endpoints
 * only read {@code isSupported()}/{@code supportsScheduling()} and never touch the OS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "claude.os-integration=linux",
        "claude.auto-open-browser=false",
})
class OsCapabilitiesIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void actionsSupportedButSchedulingNotOnLinux() throws Exception {
        mvc.perform(get("/api/actions/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions").value(true))
                .andExpect(jsonPath("$.scheduling").value(false));
    }

    @Test
    void scheduleCapabilitiesReportsUnsupportedOffWindows() throws Exception {
        mvc.perform(get("/api/schedule/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduling").value(false));
    }
}
