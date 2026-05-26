package innbucks.paymentservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Direct-call CORS configuration for payment-service. The api-gateway already
 * applies its own globalcors block to anything that reaches it, but a browser
 * hitting payment-service on its own port (8085 in dev, behind a per-service
 * ingress in staging/prod) bypasses the gateway entirely. Without this
 * config, Spring MVC happily answers the OPTIONS preflight with a default
 * 200 + Allow header but no {@code Access-Control-Allow-Origin}, so the
 * browser kills the request before the actual POST runs — observed as a
 * "NetworkError when attempting to fetch resource" with the preflight row
 * marked failed.
 *
 * <p>payment-service has no Spring Security filter chain (see
 * GlobalExceptionHandler's note) so we register CORS at the MVC layer rather
 * than via a SecurityFilterChain.cors(...) hook. The
 * {@code cors.allowed-origins} property has been defined in application.yaml
 * since the service was first stood up — this class is what finally consumes
 * it, mirroring user-service and loyalty-service.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    // Constructor-injected (not @Value field injection) so the assignment is
    // visible to static analysis — field injection left Qodana thinking the
    // list was "queried but never populated" since it can't see the
    // reflective write. Constructor injection is also the Spring-recommended
    // style: the bean can't exist in a half-built state.
    //
    // setAllowedOriginPatterns is what makes the list compatible with
    // allowCredentials=true (a plain '*' would be rejected by the browser
    // when credentials are in play).
    private final List<String> allowedOrigins;

    public WebConfig(@Value("${cors.allowed-origins:*}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
