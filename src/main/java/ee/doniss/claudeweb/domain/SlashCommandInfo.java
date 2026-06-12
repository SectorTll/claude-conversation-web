package ee.doniss.claudeweb.domain;

/**
 * One user-invocable slash command offered by the composer's autocomplete popup.
 *
 * @param name         what the user types after the slash (e.g. {@code review} or {@code git:commit})
 * @param description  one-liner from the frontmatter ({@code ""} when absent)
 * @param argumentHint frontmatter {@code argument-hint}, or {@code null}
 * @param source       {@code user} (Claude home) or {@code project} (the project's {@code .claude})
 * @param kind         {@code skill} ({@code skills/<name>/SKILL.md}) or {@code command} ({@code commands/**.md})
 */
public record SlashCommandInfo(String name, String description, String argumentHint,
                               String source, String kind) {
}
