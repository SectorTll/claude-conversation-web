package ee.doniss.claudeweb.service.chat;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Project-relative file paths for the composer's {@code @}-mention autocomplete. Walks the
 * session's cwd (resolved server-side — the client never supplies a path), skipping VCS/build/
 * dependency junk, capped so a monorepo can't flood the browser. Paths use forward slashes —
 * what the CLI's {@code @path} syntax expects on every OS.
 */
@Service
public class CwdFileLister {

    public static final int MAX_FILES = 2000;
    private static final int MAX_DEPTH = 6;
    private static final Set<String> SKIP = Set.of(
            ".git", "node_modules", "build", "dist", "out", "target", ".gradle", ".idea",
            "__pycache__", ".venv", "venv", "coverage", ".next", ".cache", "vendor");

    /** Relative paths under {@code root}, shallow-first then alphabetical; empty if not a dir. */
    public List<String> list(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (out.size() >= MAX_FILES) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (dir.equals(root)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String name = dir.getFileName().toString();
                            boolean junk = SKIP.contains(name)
                                    || name.startsWith("_claude")
                                    || (name.startsWith(".") && !name.equals(".claude"));
                            return junk ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile()) {
                                out.add(root.relativize(file).toString().replace('\\', '/'));
                                if (out.size() >= MAX_FILES) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE; // unreadable entry — skip, keep walking
                        }
                    });
        } catch (IOException ignore) {
            // partial listing is fine for autocomplete
        }
        out.sort(Comparator
                .comparingLong((String p) -> p.chars().filter(ch -> ch == '/').count())
                .thenComparing(Comparator.naturalOrder()));
        return out;
    }
}
