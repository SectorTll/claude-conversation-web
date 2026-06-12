package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.support.BadRequestException;

import java.util.Set;

/**
 * Validates the per-turn model override from the browser. Only the CLI's stable aliases are
 * accepted (the value ends up on a process command line / SDK option) — blank means "the user's
 * configured default model".
 */
public final class ChatModels {

    public static final Set<String> ALLOWED = Set.of("haiku", "sonnet", "opus", "fable");

    private ChatModels() {
    }

    /** Normalized model alias, or {@code ""} for the default. Unknown values are rejected. */
    public static String resolve(String model) {
        if (model == null || model.isBlank()) {
            return "";
        }
        String m = model.strip();
        if (!ALLOWED.contains(m)) {
            throw new BadRequestException("invalid model: " + m);
        }
        return m;
    }
}
