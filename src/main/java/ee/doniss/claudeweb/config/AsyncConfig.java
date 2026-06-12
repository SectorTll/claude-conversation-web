package ee.doniss.claudeweb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Thread pool that reads streaming {@code claude -p} chat-turn output (~2 reader threads per turn). */
@Configuration
public class AsyncConfig {

    /** Light timer pool for the sdk engine's watchdogs (auto-deny, idle reap) — never does real work. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService claudeChatScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "claude-chat-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public Executor claudeChatExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        // The reader tasks block for the whole turn, so we must NOT queue them (queueing would
        // serialize concurrent turns). queueCapacity=0 → SynchronousQueue: each task gets a thread,
        // growing to maxPoolSize; idle threads time out. A personal/LAN tool never approaches the cap.
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(64);
        ex.setQueueCapacity(0);
        ex.setKeepAliveSeconds(30);
        ex.setAllowCoreThreadTimeOut(true);
        ex.setThreadNamePrefix("claude-chat-");
        ex.setDaemon(true);
        ex.initialize();
        return ex;
    }
}
