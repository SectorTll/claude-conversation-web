package ee.doniss.claudeweb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Long, immutable caching for the content-hashed Vite assets ({@code /assets/index-<hash>.js|css}).
 * The filename changes on every rebuild, so the browser can cache them for a year and never
 * revalidate — a big bandwidth saving on repeat loads (especially over a VPN). {@code index.html}
 * and other root static files keep the default revalidate behaviour ({@code spring.web.resources}).
 *
 * <p>{@code /assets/**} is more specific than the auto-configured {@code /**} handler, so it wins
 * for asset requests while {@code /**} still serves {@code index.html}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
