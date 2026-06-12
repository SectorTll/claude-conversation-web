package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.domain.ServerScheduledTask;
import ee.doniss.claudeweb.domain.ServerTaskSpec;
import ee.doniss.claudeweb.os.OsIntegration;
import ee.doniss.claudeweb.service.schedule.server.ServerScheduleService;
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

/**
 * Manage in-process scheduled Claude runs (the "second approach" — the server fires them itself).
 * Parallel to {@link ScheduleController} but cross-platform: capabilities are always available because
 * the always-on server owns the schedule.
 */
@RestController
@RequestMapping("/api/server-schedule")
public class ServerScheduleController {

    private final ServerScheduleService service;
    private final OsIntegration os;

    public ServerScheduleController(ServerScheduleService service, OsIntegration os) {
        this.service = service;
        this.os = os;
    }

    public record Capabilities(boolean scheduling) {
    }

    public record Report(String name, String content) {
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(true); // the server is always running — scheduling works on any OS
    }

    @GetMapping("/tasks")
    public List<ServerScheduledTask> tasks() {
        return service.list();
    }

    @PostMapping("/tasks")
    public ServerScheduledTask create(@RequestBody ServerTaskSpec spec) {
        return service.create(spec);
    }

    @PostMapping("/tasks/{name}/run")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void run(@PathVariable String name) {
        service.runNow(name);
    }

    @PostMapping("/tasks/{name}/enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setEnabled(@PathVariable String name, @RequestParam boolean value) {
        service.setEnabled(name, value);
    }

    /** Reveal the task's folder on the host (OS-dependent; may 501 where reveal is unsupported). */
    @PostMapping("/tasks/{name}/open")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(@PathVariable String name) {
        os.openFolder(service.folderOf(name).toString());
    }

    /** The most recent run's report (latest.md), rendered in the browser. */
    @GetMapping("/tasks/{name}/report")
    public Report report(@PathVariable String name) {
        return new Report(name, service.latestReport(name));
    }

    @DeleteMapping("/tasks/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        service.delete(name);
    }
}
