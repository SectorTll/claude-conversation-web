package ee.doniss.claudeweb.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import ee.doniss.claudeweb.service.Format;

/** A persisted Claude scheduled task (read back from meta.json + Task Scheduler state). */
public class ScheduledTaskInfo {

    private String name = "";
    private String folder = "";
    private String prompt = "";
    private String workingDir = "";
    private String scheduleText = "";
    private String taskPath = "\\Claude\\";
    private String state = "";
    private String nextRun = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    public String getScheduleText() { return scheduleText; }
    public void setScheduleText(String scheduleText) { this.scheduleText = scheduleText; }

    public String getTaskPath() { return taskPath; }
    public void setTaskPath(String taskPath) { this.taskPath = taskPath; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getNextRun() { return nextRun; }
    public void setNextRun(String nextRun) { this.nextRun = nextRun; }

    // ---- computed (API only; ignored when reading meta.json back) ----
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getFullTaskName() {
        String base = taskPath.endsWith("\\") ? taskPath.substring(0, taskPath.length() - 1) : taskPath;
        return base + "\\" + name;
    }

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
        return sb.toString();
    }
}
