package com.innbucks.userservice.support;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.StaffEligibility;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Does a lookup's resolved keys reach an InnBucks STAFF account (design §3.1.2)?
 * A staff email ({@code users.email}), a staff contact phone
 * ({@code staff_profiles.contact_phone}), a legacy staff sign-in phone
 * ({@code users.phone_number}) or a staff account's uuid all count — "staff
 * account" being {@link StaffEligibility#isStaffAccount}: a staff role, or a
 * staff profile.
 *
 * <p>Why it matters: an agent looking up a colleague (or a supervisor, or the
 * platform owner) is the insider path this surface most needs to see, and a
 * support write on a staff account is how one staff member would take over
 * another. Lookups set {@code staffAccount: true} and alert; writes need
 * {@code support-staff-targets:manage}, re-checked against a FRESH read here at
 * write time, never the flag stored with the lookup.
 */
@Component
public class SupportStaffTargets {

    private final UserRepository users;
    private final StaffProfileRepository staffProfiles;
    private final StaffEligibility staffEligibility;

    public SupportStaffTargets(UserRepository users, StaffProfileRepository staffProfiles,
                               StaffEligibility staffEligibility) {
        this.users = users;
        this.staffProfiles = staffProfiles;
        this.staffEligibility = staffEligibility;
    }

    /** The uuids of every staff account the keys reach; empty when none. */
    public Set<UUID> staffAccounts(SupportCustomerKeys keys) {
        Set<UUID> staff = new TreeSet<>();
        if (keys == null) return staff;
        Map<Long, User> candidates = new LinkedHashMap<>();
        for (String email : keys.emails()) {
            for (User u : users.findAllByEmailIgnoreCase(email)) candidates.put(u.getId(), u);
        }
        for (String phone : keys.phones()) {
            users.findByPhoneNumber(phone).ifPresent(u -> candidates.put(u.getId(), u));
        }
        if (!keys.userUuids().isEmpty()) {
            List<UUID> uuids = new ArrayList<>();
            for (String s : keys.userUuids()) {
                try {
                    uuids.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                    // not a uuid: cannot name an account
                }
            }
            for (User u : users.findByUserUuidIn(uuids)) candidates.put(u.getId(), u);
        }
        if (!keys.userIds().isEmpty()) {
            for (User u : users.findAllById(keys.userIds())) candidates.put(u.getId(), u);
        }
        if (!keys.phones().isEmpty()) {
            List<Long> contactHolders = staffProfiles.findAllByContactPhoneIn(keys.phones()).stream()
                    .map(StaffProfile::getUserId).toList();
            for (User u : users.findAllById(contactHolders)) {
                staff.add(u.getUserUuid());
                candidates.remove(u.getId());
            }
        }
        for (User u : candidates.values()) {
            if (staffEligibility.isStaffAccount(u)) staff.add(u.getUserUuid());
        }
        return staff;
    }

    /** True when {@code user} itself is a staff account — the console guard on its writes. */
    public boolean isStaffAccount(User user) {
        return staffEligibility.isStaffAccount(user);
    }
}
