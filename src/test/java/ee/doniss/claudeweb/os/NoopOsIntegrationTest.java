package ee.doniss.claudeweb.os;

import ee.doniss.claudeweb.support.OsActionUnsupportedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoopOsIntegrationTest {

    private final NoopOsIntegration os = new NoopOsIntegration();

    @Test
    void actionsThrowUnsupported() {
        assertThrows(OsActionUnsupportedException.class, () -> os.resume("s", "d", false));
        assertThrows(OsActionUnsupportedException.class, () -> os.newSession("d"));
        assertThrows(OsActionUnsupportedException.class, () -> os.openFolder("d"));
        assertThrows(OsActionUnsupportedException.class, () -> os.revealFile("f"));
    }

    @Test
    void reportsUnsupportedButStillBuildsCommandString() {
        assertFalse(os.isSupported());
        assertFalse(os.supportsScheduling());
        assertEquals("claude --resume s", os.resumeCommand("s", false));
    }
}
