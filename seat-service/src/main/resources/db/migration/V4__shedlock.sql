-- V4: ShedLock table.
--
-- Used by net.javacrumbs.shedlock to leader-elect SeatLockReaper across
-- replicas. Without this, every pod runs reaper.reap() every minute
-- (configurable via app.seat-lock-reaper.interval-ms) simultaneously --
-- same batch of expired-lock candidates pulled three times in a three-pod deploy,
-- each thread then attempting per-row pessimistic locks via
-- SeatService.releaseStaleLock and tripping on the others. Optimistic
-- locking on Seat prevents bad writes but the wasted query traffic and
-- lock-conflict noise is real. With ShedLock, exactly one pod holds the
-- lock for the duration of each scheduled tick.

CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
