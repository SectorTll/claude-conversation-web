package ee.doniss.claudeweb.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import ee.doniss.claudeweb.service.Format;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A persisted in-process scheduled task (the "second approach"). The definition + last-run state live
 * together in one {@code task.json} under the server-schedule store. {@code folder}, {@code state} and
 * {@code nextRun} are runtime values the service overlays when listing (not authoritative on disk).
 */
public class ServerScheduledTask {

    private String name = "";
    private String folder = "";
    private String prompt = "";
    private String workingDir = "";
    private String allowedTools = "";
    private String permissionMode = "";

    private ScheduleKind kind = ScheduleKind.DAILY;
    private LocalDateTime at = LocalDateTime.now();
    private List<DayOfWeek> days = List.of();
    private int timeLimitHours = 2;
    /** Cap on agentic turns per run ({@code --max-turns}); 0 = unlimited. */
    private int maxTurns;
    private boolean enabled = true;

    private String scheduleText = "";

    // ---- runtime / last-run state ----
    /** {@code idle} | {@code running} | {@code disabled} — overlaid at list time. */
    private String state = "idle";
    /** {@code ok} | {@code failed} | {@code timeout} — persisted after each run. */
    private String lastStatus = "";
    private String lastRun = "";
    private int lastExitCode;
    /** Computed at list time from the live trigger; not authoritative on disk. */
    private String nextRun = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    public String getAllowedTools() { return allowedTools; }
    public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }

    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

    public ScheduleKind getKind() { return kind; }
    public void setKind(ScheduleKind kind) { this.kind = kind; }

    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }

    public List<DayOfWeek> getDays() { return days; }
    public void setDays(List<DayOfWeek> days) { this.days = days; }

    public int getTimeLimitHours() { return timeLimitHours; }
    public void setTimeLimitHours(int timeLimitHours) { this.timeLimitHours = timeLimitHours; }

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getScheduleText() { return scheduleText; }
    public void setScheduleText(String scheduleText) { this.scheduleText = scheduleText; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public String getLastRun() { return lastRun; }
    public void setLastRun(String lastRun) { this.lastRun = lastRun; }

    public int getLastExitCode() { return lastExitCode; }
    public void setLastExitCode(int lastExitCode) { this.lastExitCode = lastExitCode; }

    public String getNextRun() { return nextRun; }
    public void setNextRun(String nextRun) { this.nextRun = nextRun; }

    // ---- computed (API only; ignored when reading task.json back) ----
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getPromptPreview() {
        return Format.truncate(prompt.replace('\n', ' '), 90);
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getSubLine() {
        StringBuilder sb = new StringBuilder(scheduleText);
        if (state != null && !state.isBlank()) {
            sb.append("  ·  ").append(state);
        }
        if (nextRun != null && !nextRun.isBlank()) {
            sb.append("  ·  next ").append(nextRun);
        }
        if (lastStatus != null && !lastStatus.isBlank()) {
            sb.append("  ·  last ").append(lastStatus);
            if (lastRun != null && !lastRun.isBlank()) {
                sb.append(" (").append(lastRun).append(")");
            }
        }
        return sb.toString();
    }
}
