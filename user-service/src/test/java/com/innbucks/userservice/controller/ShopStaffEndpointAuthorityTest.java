package com.innbucks.userservice.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permissions the two shop-staff LIST handlers are gated on, pinned by
 * reflection (the controllers are tested standalone, without method security,
 * so nothing else fails if a {@code @PreAuthorize} changes).
 *
 * <p>{@code by-shop/{shopId}} also admits {@code shop-staff:read} because the
 * console lists a shop admin's staff through it; the service pins such a caller
 * to its own shop. {@code by-merchant/{merchantId}} stays merchant-wide only — a
 * shop admin has no business listing every shop of its merchant.
 */
class ShopStaffEndpointAuthorityTest {

    private static String preAuthorize(String method) {
        List<Method> matches = Arrays.stream(ShopStaffController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(method)).toList();
        assertThat(matches).as("ShopStaffController.%s", method).hasSize(1);
        PreAuthorize annotation = matches.get(0).getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("@PreAuthorize on ShopStaffController.%s", method).isNotNull();
        return annotation.value();
    }

    @Test
    @DisplayName("by-shop admits the merchant-wide read and the own-shop read")
    void byShopAdmitsBothReads() {
        assertThat(preAuthorize("listShopStaff"))
                .isEqualTo("hasAnyAuthority('shop-staff:merchant:read', 'shop-staff:read')");
    }

    @Test
    @DisplayName("by-merchant stays merchant-wide only")
    void byMerchantStaysMerchantWide() {
        assertThat(preAuthorize("listMerchantStaff"))
                .isEqualTo("hasAuthority('shop-staff:merchant:read')");
    }
}
