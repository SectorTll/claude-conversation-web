package ee.doniss.claudeweb.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Security configuration for exposing the server beyond loopback: a shared-password login wall and
 * a client-IP allowlist. Both replace the old "{@code server.address: 127.0.0.1}" protection once
 * the server binds the network.
 *
 * <p>{@link #enabled} is the master switch (default on). When on, {@link #password} must be set or
 * the app refuses to start — a network-bound server that reads the filesystem and launches processes
 * must never run open. Tests set {@code claude.security.enabled=false} to exercise plain API behavior.
 */
@ConfigurationProperties(prefix = "claude.security")
public class SecurityProperties {

    /** Master switch. When false, the IP allowlist and the login wall are both disabled. */
    private boolean enabled = true;

    /** Shared password. Required (non-blank) when {@link #enabled}; set via {@code CLAUDE_SECURITY_PASSWORD}. */
    private String password = "";

    /** Client IPs / CIDRs allowed to reach the server, e.g. {@code 192.168.1.0/24}, {@code 127.0.0.1}, {@code ::1}. */
    private List<String> allowedCidrs = List.of();

    /** When true, a request whose remote address is outside {@link #allowedCidrs} gets HTTP 403. */
    private boolean enforceIpAllowlist = true;

    /** How long an issued session token stays valid. */
    private Duration sessionTtl = Duration.ofHours(12);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public List<String> getAllowedCidrs() { return allowedCidrs; }
    public void setAllowedCidrs(List<String> allowedCidrs) { this.allowedCidrs = allowedCidrs; }

    public boolean isEnforceIpAllowlist() { return enforceIpAllowlist; }
    public void setEnforceIpAllowlist(boolean enforceIpAllowlist) { this.enforceIpAllowlist = enforceIpAllowlist; }

    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }
}
