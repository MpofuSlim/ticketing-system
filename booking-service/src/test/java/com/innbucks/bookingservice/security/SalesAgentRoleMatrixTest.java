package com.innbucks.bookingservice.security;

import com.innbucks.bookingservice.controller.BoxOfficeController;
import com.innbucks.bookingservice.controller.SalesAgentReportController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may call what (V36), read from the annotations the way
 * {@link PlatformStaffRoleMatrixTest} does: a sale is open to the three roles
 * a sales agent's session can carry (whether THIS caller may sell for THIS
 * event is decided live, per sale); a void and a handover are the
 * organizer's alone; the organizer's reads also answer platform staff.
 */
class SalesAgentRoleMatrixTest {

    private static String preAuthorize(Class<?> controller, String method) {
        Method m = Arrays.stream(controller.getDeclaredMethods()).filter(x -> x.getName().equals(method))
                .findFirst().orElseThrow(() -> new AssertionError(controller.getSimpleName() + "." + method));
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        if (pa != null) {
            return pa.value();
        }
        PreAuthorize classLevel = controller.getAnnotation(PreAuthorize.class);
        assertThat(classLevel).as("%s.%s has no @PreAuthorize at all", controller.getSimpleName(), method)
                .isNotNull();
        return classLevel.value();
    }

    @Test
    void aSale_isOpenToTheThreeRolesAnAgentsSessionCarries_andNoStaffRole() {
        String sell = preAuthorize(BoxOfficeController.class, "sell");
        assertThat(sell).contains("'EVENT_ORGANIZER'", "'SALES_AGENT'", "'CUSTOMER'")
                .doesNotContain("SUPER_ADMIN", "PRODUCT_");
        assertThat(preAuthorize(BoxOfficeController.class, "mySales")).isEqualTo(sell);
    }

    @Test
    void aVoid_andEveryHandoverWrite_areTheOrganizersAlone() {
        assertThat(preAuthorize(BoxOfficeController.class, "voidSale")).isEqualTo("hasRole('EVENT_ORGANIZER')");
        assertThat(preAuthorize(SalesAgentReportController.class, "recordHandover"))
                .isEqualTo("hasRole('EVENT_ORGANIZER')");
        assertThat(preAuthorize(SalesAgentReportController.class, "deleteHandover"))
                .isEqualTo("hasRole('EVENT_ORGANIZER')");
    }

    @Test
    void theOrganizersReads_answerPlatformStaffToo() {
        for (String read : new String[] {"report", "listHandovers"}) {
            assertThat(preAuthorize(SalesAgentReportController.class, read)).as(read)
                    .contains("'EVENT_ORGANIZER'", "'SUPER_ADMIN'", "'PRODUCT_OFFICER'", "'PRODUCT_MANAGER'")
                    .doesNotContain("CUSTOMER", "SALES_AGENT");
        }
    }
}
