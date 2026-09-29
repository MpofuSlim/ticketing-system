-- V15: ShedLock table.
--
-- Leader-elects payment-service's @Scheduled jobs across replicas
-- (SchedulerLockConfig). Without it every pod runs every sweep: two replicas
-- poll the same TOKEN_ISSUED rows, double each upstream status read, and can
-- spend a ZimSwitch checkout's one-shot final-status read (throttled to two
-- per checkout per minute) on the replica that loses the race. With it,
-- exactly one pod holds each job's lock per tick.
--
-- Same shape as booking-service V7 and seat-service V4. IF NOT EXISTS because
-- SchedulerLockConfig also creates it at boot, for a cell run with
-- FLYWAY_ENABLED=false.

CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
