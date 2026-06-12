package ee.doniss.claudeweb.os;

import ee.doniss.claudeweb.support.OsActionUnsupportedException;

/** Used on unsupported platforms / CI. Launch and reveal actions report 501; status reads as unsupported. */
public class NoopOsIntegration implements OsIntegration {

    private static final String MSG = "OS integration (CLI launch / reveal) is not available on this platform";

    @Override
    public void resume(String sessionId, String workingDir, boolean fork) {
        throw new OsActionUnsupportedException(MSG);
    }

    @Override
    public void newSession(String workingDir) {
        throw new OsActionUnsupportedException(MSG);
    }

    @Override
    public void openFolder(String path) {
        throw new OsActionUnsupportedException(MSG);
    }

    @Override
    public void revealFile(String path) {
        throw new OsActionUnsupportedException(MSG);
    }

    @Override
    public String resumeCommand(String sessionId, boolean fork) {
        return "claude --resume " + sessionId + (fork ? " --fork-session" : "");
    }

    @Override
    public boolean supportsScheduling() {
        return false;
    }

    @Override
    public boolean isSupported() {
        return false;
    }
}
