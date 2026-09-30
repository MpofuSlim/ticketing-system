package com.innbucks.userservice.support;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.SupportPolicyException;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves {@link SupportAgent} from the request's authentication and the live account. */
@Component
public class SupportAgentResolver {

    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;

    public SupportAgentResolver(UserRepository users, StaffProfileRepository staffProfiles) {
        this.users = users;
        this.staffProfiles = staffProfiles;
    }

    /** The agent, resolved or not — the device-security reads accept either. */
    public SupportAgent resolve(Authentication auth) {
        String subject = auth == null ? null : auth.getName();
        Set<String> authorities = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        if (subject == null || subject.isBlank()) {
            return new SupportAgent("anonymous", null, null, null, null, null, authorities);
        }
        Optional<User> account = subject.contains("@")
                ? users.findByEmail(subject)
                : users.findByPhoneNumber(subject);
        if (account.isEmpty() || !account.get().isActive()) {
            return new SupportAgent(subject, null, null, null, null, null, authorities);
        }
        User u = account.get();
        String contact = staffProfiles.findById(u.getId()).map(StaffProfile::getContactPhone).orElse(null);
        return new SupportAgent(subject, u.getId(), u.getUserUuid(), u.getEmail(), u.getPhoneNumber(), contact,
                authorities);
    }

    /** The agent, which must resolve to an active account — every {@code /admin/support/**} call. */
    public SupportAgent require(Authentication auth) {
        SupportAgent agent = resolve(auth);
        if (!agent.resolved()) {
            throw SupportPolicyException.agentNotResolved();
        }
        return agent;
    }
}
