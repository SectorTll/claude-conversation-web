package ee.doniss.claudeweb.service.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CwdFileListerTest {

    @TempDir
    Path root;

    private final CwdFileLister lister = new CwdFileLister();

    private void touch(String rel) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent() == null ? root : f.getParent());
        Files.writeString(f, "x");
    }

    @Test
    void listsRelativeForwardSlashPathsShallowFirst() throws IOException {
        touch("zz.txt");
        touch("src/main/App.java");
        touch("src/util.ts");

        List<String> out = lister.list(root);

        assertEquals(List.of("zz.txt", "src/util.ts", "src/main/App.java"), out);
    }

    @Test
    void skipsVcsBuildAndHiddenDirectoriesButKeepsDotClaude() throws IOException {
        touch("keep.md");
        touch(".git/HEAD");
        touch("node_modules/pkg/index.js");
        touch("build/out.jar");
        touch("_claude-push/keys.json");
        touch(".hidden/secret.txt");
        touch(".claude/commands/go.md");

        List<String> out = lister.list(root);

        assertTrue(out.contains("keep.md"));
        assertTrue(out.contains(".claude/commands/go.md"));
        assertFalse(out.stream().anyMatch(p -> p.startsWith(".git/")));
        assertFalse(out.stream().anyMatch(p -> p.contains("node_modules")));
        assertFalse(out.stream().anyMatch(p -> p.startsWith("build/")));
        assertFalse(out.stream().anyMatch(p -> p.startsWith("_claude-push/")));
        assertFalse(out.stream().anyMatch(p -> p.startsWith(".hidden/")));
    }

    @Test
    void emptyForAMissingDirectory() {
        assertTrue(lister.list(root.resolve("nope")).isEmpty());
    }
}
