package ee.doniss.claudeweb.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

/**
 * User input for creating an in-process (server-driven) scheduled Claude run — the "second approach"
 * that the always-on Spring server fires itself, independent of the Windows-only {@link TaskSpec}.
 * Unlike {@code TaskSpec} there is no {@code hidden}/{@code openReport} (those are desktop-launch
 * concerns); instead it carries a {@code permissionMode} and an {@code enabled} switch.
 */
public class ServerTaskSpec {

    private String name = "";
    private String prompt = "";
    private String workingDir = "";
    /** Space/comma-separated tool allowlist; empty = pass nothing (Claude default). */
    private String allowedTools = "Bash Read Write Edit Glob Grep";
    /** Blank → server default. Otherwise plan|acceptEdits|bypassPermissions|default|dontAsk|auto. */
    private String permissionMode = "";

    private ScheduleKind kind = ScheduleKind.DAILY;
    /** Date+time for Once; only the time-of-day is used for Daily/Weekly. */
    private LocalDateTime at = LocalDateTime.now().plusHours(1);
    private List<DayOfWeek> days = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    private int timeLimitHours = 2;
    /** Cap on agentic turns per run ({@code --max-turns}); 0 = unlimited. Cheaper than the hour cap against loops. */
    private int maxTurns = 0;
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

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
}
