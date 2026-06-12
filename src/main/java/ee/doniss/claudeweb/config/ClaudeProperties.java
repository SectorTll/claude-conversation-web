package ee.doniss.claudeweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for where the Claude data lives and how OS integration behaves.
 *
 * <p>Defaults: {@code home = ~/.claude}; {@code tasksRoot = ./_claude-tasks} resolved relative to
 * the server's working directory ({@code user.dir}) — we keep no absolute paths for our own data.
 * Tests point {@code home}/{@code tasksRoot} at temp fixtures.
 */
@ConfigurationProperties(prefix = "claude")
public class ClaudeProperties {

    /** Root of the Claude store. */
    private Path home = Path.of(System.getProperty("user.home"), ".claude");

    /** Where scheduled-task artifacts live. Relative paths are resolved against {@code user.dir}. */
    private Path tasksRoot = Path.of("_claude-tasks");

    /** Open the browser at the app URL once on startup. */
    private boolean autoOpenBrowser = true;

    /** {@code auto} | {@code windows} | {@code mac} | {@code linux} | {@code noop}. */
    private String osIntegration = "auto";

    /** In-browser chat (driving the {@code claude} CLI headlessly). */
    private Chat chat = new Chat();

    /** Second, in-process scheduler (the always-on server fires {@code claude -p} runs itself). */
    private ServerSchedule serverSchedule = new ServerSchedule();

    /** Model pricing used to estimate per-session cost from the usage data in the {@code .jsonl}. */
    private Pricing pricing = new Pricing();

    /** Full-text search index over the session store. */
    private Search search = new Search();

    /** Web-push notifications for WAITING chat turns (a card needs the user's decision). */
    private Push push = new Push();

    /** Telegram notifications for the same WAITING events (simple bot sendMessage). */
    private Telegram telegram = new Telegram();

    public Path getHome() { return home; }
    public void setHome(Path home) { this.home = home; }

    public Path getTasksRoot() { return tasksRoot; }
    public void setTasksRoot(Path tasksRoot) { this.tasksRoot = tasksRoot; }

    public boolean isAutoOpenBrowser() { return autoOpenBrowser; }
    public void setAutoOpenBrowser(boolean autoOpenBrowser) { this.autoOpenBrowser = autoOpenBrowser; }

    public String getOsIntegration() { return osIntegration; }
    public void setOsIntegration(String osIntegration) { this.osIntegration = osIntegration; }

    public Chat getChat() { return chat; }
    public void setChat(Chat chat) { this.chat = chat; }

    public ServerSchedule getServerSchedule() { return serverSchedule; }
    public void setServerSchedule(ServerSchedule serverSchedule) { this.serverSchedule = serverSchedule; }

    public Pricing getPricing() { return pricing; }
    public void setPricing(Pricing pricing) { this.pricing = pricing; }

    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }

    public Push getPush() { return push; }
    public void setPush(Push push) { this.push = push; }

    public Telegram getTelegram() { return telegram; }
    public void setTelegram(Telegram telegram) { this.telegram = telegram; }

    /**
     * In-browser chat settings. SECURITY: a chat turn runs the {@code claude} CLI on the host. With
     * {@code bypassPermissions} an authenticated browser can run arbitrary shell commands; the only
     * thing in front of it is the shared-password login wall and the IP allowlist. On the {@code sdk}
     * engine the {@code default} mode is an interactive gate (every non-allowlisted tool waits for a
     * browser Allow/Deny, auto-denied on timeout); on the headless {@code cli} engine the resolved
     * mode is final for the turn and {@code default} would deny tools without asking. The code
     * defaults here stay at the conservative {@code cli}/{@code plan}; the shipped
     * {@code application.yml} selects {@code sdk}/{@code default}.
     */
    public static class Chat {

        /**
         * Which chat backend drives a turn: {@code cli} (fresh {@code claude -p} per turn, headless)
         * or {@code sdk} (persistent Node sidecar on the Claude Agent SDK — interactive permissions
         * and in-turn questions).
         */
        private String engine = "cli";

        /** Default permission mode when a request doesn't specify one: {@code plan|acceptEdits|bypassPermissions|default|dontAsk|auto}. */
        private String permissionMode = "plan";

        /** Allow the client to override {@link #permissionMode} per request (the composer's mode picker). */
        private boolean allowPerRequestPermissionMode = true;

        /**
         * Inactivity cap on a single turn. On the {@code cli} engine this is the emitter's hard
         * timeout; on the {@code sdk} engine it is an activity watchdog that is PAUSED while a
         * permission/question is waiting on the user.
         */
        private Duration turnTimeout = Duration.ofMinutes(10);

        /** The CLI executable. Resolved on PATH by default; set an absolute path to be explicit. */
        private String executable = "claude";

        /** Node executable used to run the sidecar ({@code sdk} engine). */
        private String nodeExecutable = "node";

        /** Explicit path to the sidecar bundle; blank = dev checkout {@code sidecar/dist} or extract from the jar. */
        private String sidecarPath = "";

        /** How long a permission/question card may wait for the user before it is auto-denied ({@code sdk} engine). */
        private Duration permissionTimeout = Duration.ofMinutes(5);

        /** Idle sidecar processes (no turn, no pending ask) are reaped after this ({@code sdk} engine). */
        private Duration sessionIdleTimeout = Duration.ofMinutes(15);

        /** Absolute backstop on one streamed turn, pauses included ({@code sdk} engine's emitter timeout). */
        private Duration turnHardTimeout = Duration.ofMinutes(60);

        /** Max pasted images per message ({@code sdk} engine). */
        private int imageMaxCount = 4;

        /** Max decoded size of one pasted image in bytes ({@code sdk} engine). */
        private long imageMaxBytes = 5L * 1024 * 1024;

        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }

        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

        public boolean isAllowPerRequestPermissionMode() { return allowPerRequestPermissionMode; }
        public void setAllowPerRequestPermissionMode(boolean v) { this.allowPerRequestPermissionMode = v; }

        public Duration getTurnTimeout() { return turnTimeout; }
        public void setTurnTimeout(Duration turnTimeout) { this.turnTimeout = turnTimeout; }

        public String getExecutable() { return executable; }
        public void setExecutable(String executable) { this.executable = executable; }

        public String getNodeExecutable() { return nodeExecutable; }
        public void setNodeExecutable(String nodeExecutable) { this.nodeExecutable = nodeExecutable; }

        public String getSidecarPath() { return sidecarPath; }
        public void setSidecarPath(String sidecarPath) { this.sidecarPath = sidecarPath; }

        public Duration getPermissionTimeout() { return permissionTimeout; }
        public void setPermissionTimeout(Duration permissionTimeout) { this.permissionTimeout = permissionTimeout; }

        public Duration getSessionIdleTimeout() { return sessionIdleTimeout; }
        public void setSessionIdleTimeout(Duration sessionIdleTimeout) { this.sessionIdleTimeout = sessionIdleTimeout; }

        public Duration getTurnHardTimeout() { return turnHardTimeout; }
        public void setTurnHardTimeout(Duration turnHardTimeout) { this.turnHardTimeout = turnHardTimeout; }

        public int getImageMaxCount() { return imageMaxCount; }
        public void setImageMaxCount(int imageMaxCount) { this.imageMaxCount = imageMaxCount; }

        public long getImageMaxBytes() { return imageMaxBytes; }
        public void setImageMaxBytes(long imageMaxBytes) { this.imageMaxBytes = imageMaxBytes; }
    }

    /**
     * In-process scheduling settings. Like {@link Chat}, every fire runs {@code claude -p} headlessly
     * on the host and headless mode can NOT prompt for permission — so {@code permissionMode} is final
     * for that run. The default is the safe read-only {@code plan} mode; tasks that must change files
     * have to opt into a stronger mode (and {@code acceptEdits}/{@code bypassPermissions} let an
     * authenticated browser run host actions autonomously on a timer — same RCE surface as chat).
     */
    public static class ServerSchedule {

        /** Where server-scheduled task definitions + reports live. Relative paths resolve against {@code user.dir}. */
        private Path store = Path.of("_claude-server-tasks");

        /** Default permission mode for a task that doesn't pick one: {@code plan|acceptEdits|bypassPermissions|default|dontAsk|auto}. */
        private String permissionMode = "plan";

        /** Allow a task to override {@link #permissionMode} (the modal's mode picker). */
        private boolean allowPerRequestPermissionMode = true;

        /** Threads that fire due triggers. Each fire only dispatches to the chat executor, so a couple suffice. */
        private int schedulerPoolSize = 2;

        public Path getStore() { return store; }
        public void setStore(Path store) { this.store = store; }

        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

        public boolean isAllowPerRequestPermissionMode() { return allowPerRequestPermissionMode; }
        public void setAllowPerRequestPermissionMode(boolean v) { this.allowPerRequestPermissionMode = v; }

        public int getSchedulerPoolSize() { return schedulerPoolSize; }
        public void setSchedulerPoolSize(int schedulerPoolSize) { this.schedulerPoolSize = schedulerPoolSize; }
    }

    /**
     * $/MTok rates per model family, used for the *estimated* cost shown in the UI. A family key
     * matches when the model id contains it as a substring (e.g. {@code haiku} matches
     * {@code claude-haiku-4-5-20251001}); first match in declaration order wins. Cache pricing
     * follows the Anthropic rule of thumb: write = 1.25× input, read = 0.1× input.
     */
    public static class Pricing {

        /** Cache write tokens cost this × the input rate (5-minute TTL writes). */
        private double cacheWriteMultiplier = 1.25;

        /** Cache read tokens cost this × the input rate. */
        private double cacheReadMultiplier = 0.10;

        /** Family substring → rates. Insertion order = match priority. */
        private Map<String, ModelRate> models = defaultModels();

        public double getCacheWriteMultiplier() { return cacheWriteMultiplier; }
        public void setCacheWriteMultiplier(double v) { this.cacheWriteMultiplier = v; }

        public double getCacheReadMultiplier() { return cacheReadMultiplier; }
        public void setCacheReadMultiplier(double v) { this.cacheReadMultiplier = v; }

        public Map<String, ModelRate> getModels() { return models; }
        public void setModels(Map<String, ModelRate> models) { this.models = models; }

        private static Map<String, ModelRate> defaultModels() {
            Map<String, ModelRate> m = new LinkedHashMap<>();
            m.put("haiku", new ModelRate(1.00, 5.00));
            m.put("sonnet", new ModelRate(3.00, 15.00));
            m.put("opus", new ModelRate(5.00, 25.00));
            m.put("fable", new ModelRate(10.00, 50.00));
            return m;
        }

        /** $ per 1M input / output tokens. */
        public static class ModelRate {
            private double input;
            private double output;

            public ModelRate() {
            }

            public ModelRate(double input, double output) {
                this.input = input;
                this.output = output;
            }

            public double getInput() { return input; }
            public void setInput(double input) { this.input = input; }

            public double getOutput() { return output; }
            public void setOutput(double output) { this.output = output; }
        }
    }

    /**
     * Full-text search index (Lucene) speeding up global search over large stores. The index lives
     * under {@link #getIndexDir() indexDir} and is rebuilt in the background on startup; while it
     * is not ready (or {@code enabled=false}) search falls back to the brute-force scan.
     */
    public static class Search {

        private boolean enabled = true;

        /** Index directory. Relative paths resolve against {@code user.dir}. */
        private Path indexDir = Path.of("_claude-search-index");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Path getIndexDir() { return indexDir; }
        public void setIndexDir(Path indexDir) { this.indexDir = indexDir; }
    }

    /**
     * Web-push settings. The VAPID keypair is generated on first use and persisted under
     * {@link #getStore() store} together with the browser subscriptions; pushes fire when a chat
     * turn parks on a permission/question card (WAITING) so a closed tab still gets notified.
     */
    public static class Push {

        private boolean enabled = true;

        /** Where the VAPID keys + subscriptions live. Relative paths resolve against {@code user.dir}. */
        private Path store = Path.of("_claude-push");

        /** VAPID subject (mailto: or https: URL identifying the sender). */
        private String subject = "mailto:claude-web@localhost";

        /** Minimum gap between pushes for the SAME session — collapses a burst of cards into one ping. */
        private Duration cooldown = Duration.ofSeconds(30);

        /** Absolute origin for deep links in messages that leave the browser (Telegram). Blank = no links. */
        private String publicBaseUrl = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Path getStore() { return store; }
        public void setStore(Path store) { this.store = store; }

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }

        public Duration getCooldown() { return cooldown; }
        public void setCooldown(Duration cooldown) { this.cooldown = cooldown; }

        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    }

    /** Telegram bot notifications for WAITING events. Disabled until a bot token + chat id are set. */
    public static class Telegram {

        private boolean enabled = false;

        /** Bot API token from @BotFather (set via {@code CLAUDE_TELEGRAM_BOT_TOKEN}). */
        private String botToken = "";

        /** The chat to notify (your user id or a group id). */
        private String chatId = "";

        /**
         * Where the UI-managed overrides persist ({@code settings.json}). Relative paths resolve
         * against {@code user.dir}. The browser settings dialog writes here so its changes survive a
         * restart and win over this yml; an env-provided token is never copied to disk.
         */
        private Path store = Path.of("_claude-telegram");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }

        public String getChatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }

        public Path getStore() { return store; }
        public void setStore(Path store) { this.store = store; }
    }

    // ---- derived ----
    public Path projectsRoot() {
        return home.resolve("projects");
    }

    public Path sessionsRoot() {
        return home.resolve("sessions");
    }

    /** Absolute, normalized tasks root (resolved against the server working dir if relative). */
    public Path resolvedTasksRoot() {
        Path p = tasksRoot.isAbsolute()
                ? tasksRoot
                : Path.of(System.getProperty("user.dir")).resolve(tasksRoot);
        return p.toAbsolutePath().normalize();
    }

    /** Absolute, normalized server-schedule store (resolved against the server working dir if relative). */
    public Path resolvedServerScheduleStore() {
        Path s = serverSchedule.getStore();
        Path p = s.isAbsolute() ? s : Path.of(System.getProperty("user.dir")).resolve(s);
        return p.toAbsolutePath().normalize();
    }

    /** Absolute, normalized push store (resolved against the server working dir if relative). */
    public Path resolvedPushStore() {
        Path s = push.getStore();
        Path p = s.isAbsolute() ? s : Path.of(System.getProperty("user.dir")).resolve(s);
        return p.toAbsolutePath().normalize();
    }

    /** Absolute, normalized search index dir (resolved against the server working dir if relative). */
    public Path resolvedSearchIndexDir() {
        Path s = search.getIndexDir();
        Path p = s.isAbsolute() ? s : Path.of(System.getProperty("user.dir")).resolve(s);
        return p.toAbsolutePath().normalize();
    }

    /** Absolute, normalized Telegram settings store (resolved against the server working dir if relative). */
    public Path resolvedTelegramStore() {
        Path s = telegram.getStore();
        Path p = s.isAbsolute() ? s : Path.of(System.getProperty("user.dir")).resolve(s);
        return p.toAbsolutePath().normalize();
    }
}
