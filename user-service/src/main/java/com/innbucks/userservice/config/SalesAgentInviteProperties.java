package com.innbucks.userservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Sales-agent portal invites (V56), bound from {@code sales-agent-invite.*}. The
 * defaults live in {@code application.yaml}, so no cell key is needed: the link's
 * origin is the staff console's ({@code STAFF_CONSOLE_BASE_URL}, already set per
 * cell), which is the Foundry portal both kinds of invite open.
 *
 * <p>Validated at boot like {@link StaffAccountProperties}: the path starts with
 * {@code /} and carries no {@code ?} or {@code #} (the token is appended as the
 * fragment), and the TTL is positive.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "sales-agent-invite")
public class SalesAgentInviteProperties implements InitializingBean {

    /** {@code SALES_AGENT_INVITE_TTL}: how long a portal invite link works. */
    private Duration ttl = Duration.ofHours(72);

    /** {@code SALES_AGENT_INVITE_PATH}: the portal route that redeems a sales-agent invite. */
    private String path = "/sales-agent-invite";

    @Override
    public void afterPropertiesSet() {
        if (path == null || !path.startsWith("/") || path.contains("#") || path.contains("?")
                || path.chars().anyMatch(c -> c < 0x21 || c > 0x7E)) {
            throw new IllegalStateException("SALES_AGENT_INVITE_PATH must start with '/' and contain no '?', '#', "
                    + "space or non-ASCII character; got '" + path + "'");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("SALES_AGENT_INVITE_TTL must be a positive duration, e.g. PT72H");
        }
    }
}
