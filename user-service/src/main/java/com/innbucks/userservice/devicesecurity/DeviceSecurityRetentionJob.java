package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.devicesecurity.repository.CustomerDeviceRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceLoginTicketRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceOtpChallengeRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceSecurityEventRepository;
import com.innbucks.userservice.devicesecurity.repository.SignInLocationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Keeps only what §12 says DTX keeps: decisions, rounded locations and device
 * rows for 12 months (bans are kept — they are security records, not habits);
 * OTP challenges and login tickets for a week (long enough to answer "did that
 * code go out?", far longer than any of them lives). Nightly, off-peak.
 */
@Component
@Slf4j
public class DeviceSecurityRetentionJob {

    private final DeviceSecurityEventRepository events;
    private final SignInLocationRepository locations;
    private final CustomerDeviceRepository devices;
    private final DeviceOtpChallengeRepository challenges;
    private final DeviceLoginTicketRepository tickets;
    private final DeviceSecurityProperties properties;
    private final Clock clock;

    public DeviceSecurityRetentionJob(DeviceSecurityEventRepository events, SignInLocationRepository locations,
                                      CustomerDeviceRepository devices, DeviceOtpChallengeRepository challenges,
                                      DeviceLoginTicketRepository tickets, DeviceSecurityProperties properties,
                                      Clock deviceSecurityClock) {
        this.events = events;
        this.locations = locations;
        this.devices = devices;
        this.challenges = challenges;
        this.tickets = tickets;
        this.properties = properties;
        this.clock = deviceSecurityClock;
    }

    @Scheduled(cron = "${device-security.retention.cron:0 17 2 * * *}", zone = "UTC")
    @Transactional
    public void purge() {
        LocalDateTime now = LocalDateTime.now(clock);
        DeviceSecurityProperties.Retention r = properties.getRetention();
        int t = tickets.deleteIssuedBefore(now.minusDays(r.getTicketsDays()));
        int c = challenges.deleteCreatedBefore(now.minusDays(r.getChallengesDays()));
        int l = locations.deleteOccurredBefore(now.minusDays(r.getLocationsDays()));
        int e = events.deleteOccurredBefore(now.minusDays(r.getEventsDays()));
        int d = devices.deleteUnseenSince(now.minusDays(r.getDevicesDays()));
        if (t + c + l + e + d > 0) {
            log.info("Device-security retention purged tickets={} challenges={} locations={} events={} devices={}",
                    t, c, l, e, d);
        }
    }
}
