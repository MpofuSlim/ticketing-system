package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "user-service",
        url = "${user-service.url}",
        fallback = UserServiceClientFallback.class)
public interface UserServiceClient {

    @GetMapping("/auth/customer/tier/lookup")
    ApiResult<CustomerTierResponseDTO> lookupCustomerTier(@RequestParam("subject") String subject);
}
