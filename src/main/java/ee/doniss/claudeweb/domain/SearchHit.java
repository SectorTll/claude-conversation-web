package ee.doniss.claudeweb.domain;

/**
 * One session that matched a global (all-projects) search, with a preview snippet. Port of
 * {@code Models/SearchHit.cs}.
 */
public final class SearchHit {

    private final SessionInfo session;
    private final String projectId;
    private final String projectName;
    private final String snippet;
    private final int count;

    public SearchHit(SessionInfo session, String projectId, String projectName, String snippet, int count) {
        this.session = session;
        this.projectId = projectId;
        this.projectName = projectName;
        this.snippet = snippet;
        this.count = count;
    }

    public SessionInfo getSession() { return session; }
    public String getProjectId() { return projectId; }
    public String getProjectName() { return projectName; }
    public String getSnippet() { return snippet; }
    public int getCount() { return count; }

    public String getTitle() { return session.getTitle(); }

    public String getMetaLine() {
        String matches = count == 1 ? "1 match" : count + " matches";
        return projectName + "  ·  " + matches + "  ·  " + session.getLastActivityDisplay();
    }
}
