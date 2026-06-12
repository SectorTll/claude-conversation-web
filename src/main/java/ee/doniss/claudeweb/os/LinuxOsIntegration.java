package ee.doniss.claudeweb.os;

import ee.doniss.claudeweb.support.OsActionUnsupportedException;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Linux implementation. There is no single standard terminal or "reveal in folder" call, so:
 *
 * <ul>
 *   <li><b>Terminal:</b> probe a list of known emulators (honoring {@code $TERMINAL} and the Debian
 *       {@code x-terminal-emulator} alternative first) and run the generated launcher with that
 *       emulator's "run this program" flag. The launcher is a single executable token, which sidesteps
 *       the {@code -e "<string>"} vs {@code -e <argv>} differences between emulators.</li>
 *   <li><b>Open folder:</b> {@code xdg-open <dir>}.</li>
 *   <li><b>Reveal file:</b> the freedesktop {@code org.freedesktop.FileManager1.ShowItems} D-Bus call
 *       (selects the file in Nautilus/Dolphin/Nemo/Caja/…); falls back to opening the parent folder
 *       with {@code xdg-open} when {@code dbus-send} is unavailable.</li>
 * </ul>
 */
public class LinuxOsIntegration extends UnixOsIntegration {

    /** A terminal emulator and the argument(s) that precede the program it should run. */
    private record Terminal(String exe, List<String> runArgs) {
    }

    /**
     * Ordered probe list. The launcher is one executable token, so for the common {@code -e} family a
     * single {@code -e} works; emulators that dropped {@code -e} get their own flag.
     */
    private static final List<Terminal> TERMINALS = List.of(
            new Terminal("x-terminal-emulator", List.of("-e")),
            new Terminal("gnome-terminal", List.of("--")),
            new Terminal("konsole", List.of("-e")),
            new Terminal("xfce4-terminal", List.of("-e")),
            new Terminal("mate-terminal", List.of("-e")),
            new Terminal("tilix", List.of("-e")),
            new Terminal("terminator", List.of("-e")),
            new Terminal("kitty", List.of()),
            new Terminal("alacritty", List.of("-e")),
            new Terminal("wezterm", List.of("start", "--")),
            new Terminal("foot", List.of()),
            new Terminal("st", List.of("-e")),
            new Terminal("urxvt", List.of("-e")),
            new Terminal("xterm", List.of("-e")));

    private final Predicate<String> onPath;

    public LinuxOsIntegration(ProcessRunner runner) {
        this(runner, ON_PATH);
    }

    /** Test seam: inject which executables are "present" so terminal/dbus selection is deterministic. */
    LinuxOsIntegration(ProcessRunner runner, Predicate<String> onPath) {
        super(runner);
        this.onPath = onPath;
    }

    @Override
    protected void openTerminal(String dir, Path launcher) {
        Terminal term = pickTerminal();
        if (term == null) {
            throw new OsActionUnsupportedException(
                    "No supported terminal emulator found on PATH. Install one of "
                            + "gnome-terminal, konsole, xfce4-terminal or xterm, or set $TERMINAL.");
        }
        List<String> command = new ArrayList<>();
        command.add(term.exe());
        command.addAll(term.runArgs());
        command.add(launcher.toString());
        runner.launch(command, dir);
    }

    @Override
    public void openFolder(String path) {
        if (path != null && !path.isBlank() && new File(path).isDirectory()) {
            runner.launch(List.of("xdg-open", path), null);
        }
    }

    @Override
    public void revealFile(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (onPath.test("dbus-send")) {
            String uri = Path.of(path).toAbsolutePath().toUri().toString();
            runner.launch(List.of("dbus-send", "--session", "--dest=org.freedesktop.FileManager1",
                    "--type=method_call", "/org/freedesktop/FileManager1",
                    "org.freedesktop.FileManager1.ShowItems",
                    "array:string:" + uri, "string:"), null);
        } else {
            // No D-Bus: open the containing folder (can't select the file, but still gets the user there).
            File parent = new File(path).getParentFile();
            if (parent != null && parent.isDirectory()) {
                runner.launch(List.of("xdg-open", parent.getPath()), null);
            }
        }
    }

    /** {@code $TERMINAL} (if present on PATH) wins; otherwise the first probe-list emulator found. */
    private Terminal pickTerminal() {
        String preferred = System.getenv("TERMINAL");
        if (preferred != null && !preferred.isBlank() && onPath.test(preferred)) {
            return new Terminal(preferred, List.of("-e"));
        }
        for (Terminal t : TERMINALS) {
            if (onPath.test(t.exe())) {
                return t;
            }
        }
        return null;
    }
}
