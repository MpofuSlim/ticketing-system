-- V18: adds TRIAL_RUN ("Trial Run", under Sports beside FUN_RUN) to the event
-- categories, so the list is now 65 values.
--
-- chk_events_category holds a fixed list (V4, V10, V12), so adding an
-- EventCategory value means recreating it with the new superset. Every
-- existing row holds a value from V12's list, so the new check passes with no
-- data change. The column stays VARCHAR(30); TRIAL_RUN is 9 characters.
--
-- Dropping and re-adding the constraint takes a brief ACCESS EXCLUSIVE lock on
-- events and checks every row once, which is a small table. lock_timeout keeps
-- a busy moment from queueing event reads behind it: the migration fails and
-- the old pod serves on, and a retry at a quieter time succeeds.

SET LOCAL lock_timeout = '10s';

ALTER TABLE events DROP CONSTRAINT chk_events_category;
ALTER TABLE events
    ADD CONSTRAINT chk_events_category
        CHECK (category IN ('CONCERT',
        'GOSPEL_CONCERT',
        'MUSIC_FESTIVAL',
        'MUSIC_GALA',
        'SUNGURA_SHOW',
        'DANCEHALL_SHOW',
        'ALBUM_LAUNCH',
        'NIGHTLIFE',
        'ARTS_FESTIVAL',
        'CULTURAL_FESTIVAL',
        'THEATRE',
        'DANCE_PERFORMANCE',
        'COMEDY',
        'FILM_SCREENING',
        'POETRY_SPOKEN_WORD',
        'BOOKS',
        'ART_EXHIBITION',
        'FASHION_SHOW',
        'PAGEANT',
        'CHURCH_CONFERENCE',
        'CRUSADE',
        'ISLAMIC_EVENT',
        'RETREAT',
        'AWARDS_CEREMONY',
        'CHARITY_FUNDRAISER',
        'DINNER_DANCE',
        'GRADUATION',
        'ALUMNI_REUNION',
        'SCHOOL_EVENT',
        'SEASONAL_HOLIDAY',
        'CONFERENCE',
        'TECH_STARTUP',
        'WORKSHOP_MASTERCLASS',
        'NETWORKING',
        'PRODUCT_LAUNCH',
        'CAREER_FAIR',
        'AGRICULTURAL_SHOW',
        'TRADE_EXPO',
        'FOOD_DRINK_FESTIVAL',
        'DINING_EXPERIENCE',
        'KIDS_FAMILY',
        'GAMING_ESPORTS',
        'BOAT_CRUISE',
        'OUTDOOR_ADVENTURE',
        'TOUR_GETAWAY',
        'HEALTH_WELLNESS',
        'SPORT',
        'FOOTBALL',
        'CRICKET',
        'RUGBY',
        'RUGBY_SEVENS',
        'ATHLETICS',
        'FUN_RUN',
        'TRIAL_RUN',
        'HALF_MARATHON',
        'MARATHON',
        'MOTORSPORT',
        'COMBAT_SPORTS',
        'GOLF',
        'BASKETBALL',
        'NETBALL',
        'VOLLEYBALL',
        'HORSE_RACING',
        'CYCLING',
        'OTHER'));
