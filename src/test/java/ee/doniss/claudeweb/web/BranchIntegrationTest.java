package ee.doniss.claudeweb.web;

import com.jayway.jsonpath.JsonPath;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fork-from-message over REST. Own temp home — branching writes new session files. */
@SpringBootTest
@AutoConfigureMockMvc
class BranchIntegrationTest {

    static Path testHome;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void claudeProps(DynamicPropertyRegistry registry) throws IOException {
        testHome = Files.createTempDirectory("claude-home-branch-it");
        Path proj = testHome.resolve("projects").resolve("C--Work-demo");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("orig.jsonl"), String.join("\n",
                "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"orig\",\"timestamp\":\"2026-06-01T10:00:00Z\","
                        + "\"cwd\":\"C:/Work/demo\",\"message\":{\"role\":\"user\",\"content\":\"first\"}}",
                "{\"type\":\"assistant\",\"uuid\":\"a1\",\"sessionId\":\"orig\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"answer one\"}]}}",
                "{\"type\":\"user\",\"uuid\":\"u2\",\"sessionId\":\"orig\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"second\"}}") + "\n",
                StandardCharsets.UTF_8);

        registry.add("claude.home", () -> testHome.toString());
        registry.add("claude.os-integration", () -> "noop");
        registry.add("claude.auto-open-browser", () -> "false");
        registry.add("claude.security.enabled", () -> "false");
    }

    @Test
    void branchCreatesPrefixForkAndMessagesExposeUuids() throws Exception {
        MvcResult res = mvc.perform(post("/api/projects/C--Work-demo/sessions/orig/branch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uuid\":\"a1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(not("orig")))
                .andExpect(jsonPath("$.title").value(startsWith("⑂ ")))
                .andReturn();
        String forkId = JsonPath.read(res.getResponse().getContentAsString(), "$.sessionId");

        mvc.perform(get("/api/projects/C--Work-demo/sessions/" + forkId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].uuid").value("u1"))
                .andExpect(jsonPath("$[1].text").value("answer one"));
    }

    @Test
    void branchAtUnknownUuidIs404() throws Exception {
        mvc.perform(post("/api/projects/C--Work-demo/sessions/orig/branch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uuid\":\"nope\"}"))
                .andExpect(status().isNotFound());
    }
}
