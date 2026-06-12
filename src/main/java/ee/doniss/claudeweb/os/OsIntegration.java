package ee.doniss.claudeweb.os;

/**
 * Platform actions that touch the local OS: launching the Claude CLI in a terminal and revealing
 * files/folders. Implemented for Windows, macOS and Linux; a no-op variant keeps the rest of the app
 * working on unsupported platforms / in CI (actions throw a friendly "unsupported" error → HTTP 501).
 */
public interface OsIntegration {

    void resume(String sessionId, String workingDir, boolean fork);

    void newSession(String workingDir);

    void openFolder(String path);

    void revealFile(String path);

    /** The shell command a user would type to resume — purely informational (for clipboard). */
    String resumeCommand(String sessionId, boolean fork);

    boolean supportsScheduling();

    /** Whether the launch/reveal actions actually work on this platform. */
    boolean isSupported();
}
