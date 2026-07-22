package innbucks.paymentservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient for the InnBucks public notification API — email AND SMS. Targets
 * the public API Gateway with bearer + X-Api-Key auth handled in
 * {@code EmailNotificationClient} ({@code POST /api/notification/email} and
 * {@code POST /api/notification/sms}).
 */
@Configuration
@EnableConfigurationProperties(InnbucksNotifyProperties.class)
public class InnbucksNotifyClientConfig {

    @Bean("innbucksNotifyRestClient")
    public RestClient innbucksNotifyRestClient(InnbucksNotifyProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }
}
