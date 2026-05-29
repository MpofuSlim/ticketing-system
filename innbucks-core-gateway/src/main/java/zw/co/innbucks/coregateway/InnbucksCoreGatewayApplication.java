package zw.co.innbucks.coregateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import zw.co.innbucks.core.config.FeignInterceptor;
import zw.co.innbucks.core.rest.client.VeenguClient;

// Enable ONLY the VeenguClient Feign client — not a broad scan of core, which would
// re-introduce the JPA/config beans we deliberately excluded. FeignInterceptor is
// core's RequestInterceptor; it tags outbound calls with
// X-Source-Component=${spring.application.name}, which is how veengu identifies callers.
@SpringBootApplication
@EnableFeignClients(clients = VeenguClient.class)
@Import(FeignInterceptor.class)
public class InnbucksCoreGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(InnbucksCoreGatewayApplication.class, args);
    }
}
