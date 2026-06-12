package ee.doniss.claudeweb.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the shared {@link CidrMatcher} and validates the security config at startup. The two
 * filters ({@link IpAllowlistFilter}, {@link AuthFilter}) are {@code @Component}s ordered via
 * {@code @Order}, so Spring Boot auto-registers them for {@code /*} in the right order.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final SecurityProperties props;

    public SecurityConfig(SecurityProperties props) {
        this.props = props;
    }

    /** Parsed once; a malformed CIDR throws here and fails context startup (fail-fast). */
    @Bean
    public CidrMatcher cidrMatcher() {
        return CidrMatcher.parse(props.getAllowedCidrs());
    }

    @PostConstruct
    void validate() {
        if (!props.isEnabled()) {
            log.warn("claude.security.enabled=false — IP allowlist and login wall are OFF.");
            return;
        }
        if (props.getPassword() == null || props.getPassword().isBlank()) {
            throw new IllegalStateException(
                    "claude.security.password is not set. The server is network-bound and must not run "
                            + "without a password (it reads the filesystem and launches processes). Set the "
                            + "CLAUDE_SECURITY_PASSWORD environment variable, or set claude.security.enabled=false "
                            + "to deliberately run without the login wall.");
        }
        log.info("Security: login wall ON; IP allowlist {} ({} rule(s)).",
                props.isEnforceIpAllowlist() ? "ON" : "OFF", props.getAllowedCidrs().size());
    }
}
