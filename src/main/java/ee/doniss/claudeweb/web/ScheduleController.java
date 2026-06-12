package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.domain.ScheduledTaskInfo;
import ee.doniss.claudeweb.domain.TaskSpec;
import ee.doniss.claudeweb.os.OsIntegration;
import ee.doniss.claudeweb.service.schedule.ScheduledTaskService;
import ee.doniss.claudeweb.support.OsActionUnsupportedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Manage headless scheduled Claude runs (Windows Task Scheduler under {@code \Claude\}). */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduledTaskService service;
    private final OsIntegration os;

    public ScheduleController(ScheduledTaskService service, OsIntegration os) {
        this.service = service;
        this.os = os;
    }

    public record Capabilities(boolean scheduling) {
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(os.supportsScheduling());
    }

    /** Reads meta.json + (on Windows) live Task Scheduler state. Works cross-platform read-only. */
    @GetMapping("/tasks")
    public List<ScheduledTaskInfo> tasks() {
        return service.list();
    }

    @PostMapping("/tasks")
    public ScheduledTaskInfo create(@RequestBody TaskSpec spec) {
        requireScheduling();
        return service.create(spec);
    }

    @PostMapping("/tasks/{name}/run")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void run(@PathVariable String name) {
        requireScheduling();
        service.runNow(name);
    }

    @PostMapping("/tasks/{name}/open")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(@PathVariable String name) {
        os.openFolder(service.folderOf(name).toString());
    }

    @DeleteMapping("/tasks/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name,
                       @RequestParam(defaultValue = "true") boolean removeFiles) {
        requireScheduling();
        service.delete(name, removeFiles);
    }

    private void requireScheduling() {
        if (!os.supportsScheduling()) {
            throw new OsActionUnsupportedException("Task scheduling is only available on Windows");
        }
    }
}
