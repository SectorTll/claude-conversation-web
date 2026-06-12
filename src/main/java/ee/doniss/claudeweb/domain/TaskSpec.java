package ee.doniss.claudeweb.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

/** User input for creating a scheduled Claude run. Port of {@code Models/TaskSpec.cs}. */
public class TaskSpec {

    private String name = "";
    private String prompt = "";
    private String workingDir = "";
    /** Space-separated tool allowlist; empty = pass nothing (Claude default). */
    private String allowedTools = "Bash Read Write Edit Glob Grep";

    private ScheduleKind kind = ScheduleKind.DAILY;
    /** Date+time for Once; only the time-of-day is used for Daily/Weekly. */
    private LocalDateTime at = LocalDateTime.now().plusHours(1);
    private List<DayOfWeek> days = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    private int timeLimitHours = 2;
    private boolean hidden = true;
    private boolean openReport = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    public String getAllowedTools() { return allowedTools; }
    public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }

    public ScheduleKind getKind() { return kind; }
    public void setKind(ScheduleKind kind) { this.kind = kind; }

    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }

    public List<DayOfWeek> getDays() { return days; }
    public void setDays(List<DayOfWeek> days) { this.days = days; }

    public int getTimeLimitHours() { return timeLimitHours; }
    public void setTimeLimitHours(int timeLimitHours) { this.timeLimitHours = timeLimitHours; }

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }

    public boolean isOpenReport() { return openReport; }
    public void setOpenReport(boolean openReport) { this.openReport = openReport; }
}
