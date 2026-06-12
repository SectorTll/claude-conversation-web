package ee.doniss.claudeweb.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the slash-command XML wrapper into a readable {@code "⌘ /name args"} line. */
public final class CommandTagCleaner {

    private static final Pattern COMMAND_TAG =
            Pattern.compile("<command-(message|name|args)>(.*?)</command-\\1>", Pattern.DOTALL);

    private CommandTagCleaner() {
    }

    public static String clean(String text) {
        if (text == null || text.isEmpty() || !text.contains("<command-")) {
            return text == null ? "" : text;
        }

        String name = null;
        String args = null;
        Matcher m = COMMAND_TAG.matcher(text);
        while (m.find()) {
            if ("name".equals(m.group(1))) {
                name = m.group(2).strip();
            } else if ("args".equals(m.group(1))) {
                args = m.group(2).strip();
            }
        }

        String cleaned = COMMAND_TAG.matcher(text).replaceAll("").strip();
        if (name != null) {
            String head = "⌘ " + (name.startsWith("/") ? name : "/" + name);
            if (args != null && !args.isBlank()) {
                head += "  " + args;
            }
            cleaned = cleaned.isBlank() ? head : head + "\n\n" + cleaned;
        }
        return cleaned;
    }
}
