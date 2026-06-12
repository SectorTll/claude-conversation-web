package ee.doniss.claudeweb.os;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * macOS implementation. The terminal launch opens the generated {@code .command} launcher with
 * Terminal.app ({@code open -a Terminal <launcher>}) — Terminal runs an opened executable script,
 * so no AppleScript/Automation permission prompt is needed. File-manager actions use {@code open}
 * ({@code open <dir>} to show a folder, {@code open -R <file>} to reveal & select in Finder).
 */
public class MacOsIntegration extends UnixOsIntegration {

    public MacOsIntegration(ProcessRunner runner) {
        super(runner);
    }

    @Override
    protected String launcherSuffix() {
        return ".command"; // the extension Terminal.app treats as a runnable script
    }

    @Override
    protected void openTerminal(String dir, Path launcher) {
        runner.launch(List.of("open", "-a", "Terminal", launcher.toString()), dir);
    }

    @Override
    public void openFolder(String path) {
        if (path != null && !path.isBlank() && new File(path).isDirectory()) {
            runner.launch(List.of("open", path), null);
        }
    }

    @Override
    public void revealFile(String path) {
        if (path != null && !path.isBlank()) {
            runner.launch(List.of("open", "-R", path), null);
        }
    }
}
