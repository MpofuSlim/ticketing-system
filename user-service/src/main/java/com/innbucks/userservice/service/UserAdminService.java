package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-layer home for SUPER_ADMIN-scoped user-administration operations.
 *
 * <p>Pre-refactor AdminUserController#updateActiveStatus did its own
 * {@code userRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found: " + id))}
 * inline — bypassing the service layer every other controller routes
 * through, surfacing a 400 instead of 404 on misses, and missing the
 * @Transactional boundary that pairs the read + write into one
 * commit. Moved here so the controller can stay thin (translate HTTP <->
 * DTO, nothing else) and so future admin operations don't keep
 * reinventing the pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;

    @Transactional
    public User setActive(Long id, boolean active) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        // Idempotent — admin tools that retry the call should see the same
        // outcome without flipping state. No DB write needed when nothing
        // changed.
        if (user.isActive() == active) {
            log.info("setActive no-op userId={} active={}", id, active);
            return user;
        }

        user.setActive(active);
        User saved = userRepository.save(user);
        log.info("User {} userId={}", active ? "activated" : "deactivated", id);
        return saved;
    }
}
