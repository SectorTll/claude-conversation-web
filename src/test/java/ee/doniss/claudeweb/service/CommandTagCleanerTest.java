package ee.doniss.claudeweb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTagCleanerTest {

    @Test
    void buildsReadableSlashCommandHeader() {
        String in = "<command-name>run</command-name><command-args>npm start</command-args>";
        assertEquals("⌘ /run  npm start", CommandTagCleaner.clean(in));
    }

    @Test
    void keepsTrailingBodyAfterHeader() {
        String in = "<command-name>deploy</command-name>do it carefully";
        String out = CommandTagCleaner.clean(in);
        assertTrue(out.startsWith("⌘ /deploy"), out);
        assertTrue(out.contains("do it carefully"), out);
    }

    @Test
    void passthroughWhenNoTags() {
        assertEquals("just a normal message", CommandTagCleaner.clean("just a normal message"));
    }
}
