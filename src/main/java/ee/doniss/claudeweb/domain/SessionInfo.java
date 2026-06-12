package ee.doniss.claudeweb.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ee.doniss.claudeweb.service.Format;

import java.time.Instant;

/**
 * A single conversation = one {@code .jsonl} file. The file name (without extension) is the
 * sessionId used by {@code claude --resume <id>}. Port of {@code Models/SessionInfo.cs}; the
 * computed getters are serialized so the title/preview/meta text matches the desktop app exactly.
 */
public final class SessionInfo {

    private String sessionId = "";
    private String filePath = "";
    private String customTitle;
    private String aiTitle;
    private String firstPrompt;
    private String cwd;
    private String gitBranch;
    private String version;
    private int userMessages;
    private int assistantMessages;
    private Instant lastActivity;
    private Instant started;
    private LiveStatus liveStatus = LiveStatus.NONE;
    private long inputTokens;
    private long outputTokens;
    private long cacheCreationTokens;
    private long cacheReadTokens;
    private double costUsd;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    /** Absolute on-disk path — internal only, never sent to the client. */
    @JsonIgnore
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getCustomTitle() { return customTitle; }
    public void setCustomTitle(String customTitle) { this.customTitle = customTitle; }

    public String getAiTitle() { return aiTitle; }
    public void setAiTitle(String aiTitle) { this.aiTitle = aiTitle; }

    public String getFirstPrompt() { return firstPrompt; }
    public void setFirstPrompt(String firstPrompt) { this.firstPrompt = firstPrompt; }

    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }

    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public int getUserMessages() { return userMessages; }
    public void setUserMessages(int userMessages) { this.userMessages = userMessages; }
    public void incrementUserMessages() { this.userMessages++; }

    public int getAssistantMessages() { return assistantMessages; }
    public void setAssistantMessages(int assistantMessages) { this.assistantMessages = assistantMessages; }
    public void incrementAssistantMessages() { this.assistantMessages++; }

    public Instant getLastActivity() { return lastActivity; }
    public void setLastActivity(Instant lastActivity) { this.lastActivity = lastActivity; }

    public Instant getStarted() { return started; }
    public void setStarted(Instant started) { this.started = started; }

    public LiveStatus getLiveStatus() { return liveStatus; }
    public void setLiveStatus(LiveStatus liveStatus) { this.liveStatus = liveStatus; }

    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCacheCreationTokens() { return cacheCreationTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public double getCostUsd() { return costUsd; }

    /** Accumulate one assistant line's usage into the session totals. */
    public void addUsage(long input, long output, long cacheCreation, long cacheRead, double cost) {
        this.inputTokens += input;
        this.outputTokens += output;
        this.cacheCreationTokens += cacheCreation;
        this.cacheReadTokens += cacheRead;
        this.costUsd += cost;
    }

    // ---- computed (serialized) ----
    /** Human title: custom name &gt; ai-title &gt; first user prompt (mirrors Claude's picker). */
    public String getTitle() {
        if (notBlank(customTitle)) {
            return customTitle;
        }
        if (notBlank(aiTitle)) {
            return aiTitle;
        }
        if (notBlank(firstPrompt)) {
            return Format.truncate(firstPrompt.replace('\n', ' '), 70);
        }
        return "(untitled session)";
    }

    public boolean isHasCustomTitle() { return notBlank(customTitle); }

    public String getPreview() {
        return notBlank(firstPrompt) ? Format.truncate(firstPrompt.replace('\n', ' '), 120) : "";
    }

    public String getLastActivityDisplay() { return Format.relativeTime(lastActivity); }

    public int getTotalMessages() { return userMessages + assistantMessages; }

    public String getMetaLine() {
        String s = getTotalMessages() + " msgs · " + getLastActivityDisplay();
        if (notBlank(gitBranch) && !"HEAD".equals(gitBranch)) {
            s += " · " + gitBranch;
        }
        return s;
    }

    public String getShortId() {
        return sessionId.length() >= 8 ? sessionId.substring(0, 8) : sessionId;
    }

    private boolean hasUsage() {
        return inputTokens > 0 || outputTokens > 0 || cacheCreationTokens > 0 || cacheReadTokens > 0;
    }

    /** Compact usage summary for the session header, e.g. {@code "12.3k in · 45.6k out · $0.42"}; null when no usage. */
    public String getUsageLine() {
        if (!hasUsage()) {
            return null;
        }
        String s = Format.compactTokens(inputTokens + cacheCreationTokens + cacheReadTokens) + " in · "
                + Format.compactTokens(outputTokens) + " out";
        if (costUsd > 0) {
            s += " · " + Format.usd(costUsd);
        }
        return s;
    }

    /** Full token breakdown for the tooltip; null when no usage. The cost is an estimate (main transcript only). */
    public String getUsageTooltip() {
        if (!hasUsage()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("input ").append(String.format(java.util.Locale.ENGLISH, "%,d", inputTokens));
        sb.append(" · output ").append(String.format(java.util.Locale.ENGLISH, "%,d", outputTokens));
        if (cacheCreationTokens > 0) {
            sb.append(" · cache write ").append(Format.compactTokens(cacheCreationTokens));
        }
        if (cacheReadTokens > 0) {
            sb.append(" · cache read ").append(Format.compactTokens(cacheReadTokens));
        }
        if (costUsd > 0) {
            sb.append(" · estimated ").append(Format.usd(costUsd));
        }
        return sb.toString();
    }

    public boolean isShowStatus() { return liveStatus != LiveStatus.NONE; }
    public boolean isWaiting() { return liveStatus == LiveStatus.WAITING; }
    public boolean isWorking() { return liveStatus == LiveStatus.WORKING; }
    public boolean isIdle() { return liveStatus == LiveStatus.IDLE; }

    public String getStatusText() {
        return switch (liveStatus) {
            case WAITING -> "waiting";
            case WORKING -> "working";
            case IDLE -> "idle";
            default -> "";
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
