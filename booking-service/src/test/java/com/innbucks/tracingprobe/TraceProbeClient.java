package com.innbucks.tracingprobe;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * A Feign client for booking-service's TracingConfigurationTest. Deliberately
 * OUTSIDE com.innbucks.bookingservice: the application's @EnableFeignClients
 * scans that package (test classes included), and would otherwise register
 * this probe — with its test-only url placeholder — in every
 * @SpringBootTest context.
 */
@FeignClient(name = "trace-probe", url = "${test.feign-url}")
public interface TraceProbeClient {

    @GetMapping("/probe")
    String probe();
}
