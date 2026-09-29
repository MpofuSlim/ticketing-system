package com.innbucks.userservice.devicesecurity;

import jakarta.servlet.http.HttpServletRequest;

/** Reading the request the way every DTX endpoint must agree on. */
public final class DeviceSecurityRequests {

    public static final String DEVICE_HEADER = "x-device-id";
    public static final String APP_CHECK_HEADER = "x-innbucks-app-check";

    private DeviceSecurityRequests() {
    }

    /**
     * The customer's address. Every app-facing call is made BY the broker, which
     * forwards the phone's address as the first {@code X-Forwarded-For} entry
     * (§6.2), so the leftmost entry is the one that describes the customer. Only
     * requests that passed the broker key are ever read this way.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) return first.length() > 64 ? first.substring(0, 64) : first;
        }
        return request.getRemoteAddr();
    }

    public static DeviceSignInService.RequestMeta meta(HttpServletRequest request) {
        return new DeviceSignInService.RequestMeta(clientIp(request), request.getHeader(DEVICE_HEADER),
                request.getHeader(APP_CHECK_HEADER), request.getHeader("User-Agent"));
    }
}
