package ee.doniss.claudeweb.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Encoded-folder-name and path-segment helpers. Ports the C# {@code DecodeFolderName/SegmentOf}. */
public final class FolderNameCodec {

    private static final Pattern ENCODED = Pattern.compile("^([A-Za-z])--(.*)$");

    private FolderNameCodec() {
    }

    /**
     * Best-effort reverse of the folder encoding ({@code ':' and '\' -> '-'}). Ambiguous because
     * path segments may contain {@code '-'}, so only used when no session carries a {@code cwd}
     * (e.g. an empty project folder).
     */
    public static String decodeFolderName(String folder) {
        Matcher m = ENCODED.matcher(folder);
        if (m.matches()) {
            return m.group(1) + ":\\" + m.group(2).replace('-', '\\');
        }
        return folder;
    }

    /** Last path segment of a Windows/Unix path, or null for blank input. */
    public static String segmentOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path;
        int end = p.length();
        while (end > 0 && (p.charAt(end - 1) == '\\' || p.charAt(end - 1) == '/')) {
            end--;
        }
        p = p.substring(0, end);
        int i = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
        return i >= 0 && i < p.length() - 1 ? p.substring(i + 1) : p;
    }
}
