-- V19: renames V18's TRIAL_RUN to TRAIL_RUN ("Trail Run"), which is what the
-- category was meant to be: an off-road running event. V18 is applied history
-- and stays as it is.
--
-- Order matters: drop the constraint, move any event filed under TRIAL_RUN
-- (there may be none), then re-add the constraint with TRAIL_RUN in its
-- place, so no row is ever outside the allowed list.
--
-- Same lock as V18: a brief ACCESS EXCLUSIVE on events (a small table),
-- bounded by lock_timeout so a busy moment fails the migration rather than
-- queueing event reads; the old pod serves on and a retry succeeds.

SET LOCAL lock_timeout = '10s';

ALTER TABLE events DROP CONSTRAINT chk_events_category;

UPDATE events SET category = 'TRAIL_RUN' WHERE category = 'TRIAL_RUN';

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
        'TRAIL_RUN',
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
