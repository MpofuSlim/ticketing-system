package zw.co.innbucks.coregateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import zw.co.innbucks.core.config.FeignInterceptor;
import zw.co.innbucks.core.rest.client.MessengerClient;
import zw.co.innbucks.core.rest.client.VeenguClient;

// Explicit client list keeps the JPA/config beans in core out of our context.
// FeignInterceptor adds X-Source-Component=${spring.application.name} to every
// outbound Feign call — required by both veengu and messenger-interface for
// caller identification.
@SpringBootApplication
@EnableFeignClients(clients = {VeenguClient.class, MessengerClient.class})
@Import(FeignInterceptor.class)
public class InnbucksCoreGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(InnbucksCoreGatewayApplication.class, args);
    }
}
