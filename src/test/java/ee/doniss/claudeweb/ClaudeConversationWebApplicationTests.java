package ee.doniss.claudeweb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "claude.auto-open-browser=false",
        "claude.os-integration=noop",
})
class ClaudeConversationWebApplicationTests {

    @Test
    void contextLoads() {
    }

}
