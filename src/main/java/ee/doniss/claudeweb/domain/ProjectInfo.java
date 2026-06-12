package ee.doniss.claudeweb.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ee.doniss.claudeweb.service.Format;

import java.time.Instant;

/**
 * A Claude Code project = one folder under {@code ~/.claude/projects/}. The folder name is an
 * encoded path; the real working directory is recovered from the {@code cwd} field inside the
 * session files. Port of {@code Models/ProjectInfo.cs}.
 */
public final class ProjectInfo {

    private String folderName = "";
    private String folderPath = "";
    private String realPath = "";
    private int sessionCount;
    private Instant lastActivity;
    private LiveStatus liveStatus = LiveStatus.NONE;

    /** The REST identity of a project (= encoded folder name). */
    public String getProjectId() { return folderName; }

    public String getFolderName() { return folderName; }
    public void setFolderName(String folderName) { this.folderName = folderName; }

    /** Absolute on-disk folder — internal only, never sent to the client. */
    @JsonIgnore
    public String getFolderPath() { return folderPath; }
    public void setFolderPath(String folderPath) { this.folderPath = folderPath; }

    public String getRealPath() { return realPath; }
    public void setRealPath(String realPath) { this.realPath = realPath; }

    public int getSessionCount() { return sessionCount; }
    public void setSessionCount(int sessionCount) { this.sessionCount = sessionCount; }

    public Instant getLastActivity() { return lastActivity; }
    public void setLastActivity(Instant lastActivity) { this.lastActivity = lastActivity; }

    public LiveStatus getLiveStatus() { return liveStatus; }
    public void setLiveStatus(LiveStatus liveStatus) { this.liveStatus = liveStatus; }

    // ---- computed (serialized) ----
    /** Last path segment, e.g. "egs-aleaplay". */
    public String getShortName() {
        String p = stripTrailingSlashes(realPath);
        int idx = lastSlash(p);
        return idx >= 0 && idx < p.length() - 1 ? p.substring(idx + 1) : p;
    }

    public String getLastActivityDisplay() {
        return Format.relativeTime(lastActivity);
    }

    public String getSessionCountDisplay() {
        return sessionCount == 1 ? "1 session" : sessionCount + " sessions";
    }

    public boolean isShowStatus() { return liveStatus != LiveStatus.NONE; }
    public boolean isWaiting() { return liveStatus == LiveStatus.WAITING; }
    public boolean isWorking() { return liveStatus == LiveStatus.WORKING; }
    public boolean isIdle() { return liveStatus == LiveStatus.IDLE; }

    private static String stripTrailingSlashes(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\\' || s.charAt(end - 1) == '/')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static int lastSlash(String s) {
        return Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
    }
}
