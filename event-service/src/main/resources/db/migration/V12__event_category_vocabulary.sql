-- V12: the full ZW+KE event-category vocabulary (64 values, was 7).
--
-- chk_events_category was created in V4 with a fixed value list and already
-- recreated once in V10 (FUN_RUN); extending the EventCategory enum always
-- means recreating the constraint with the new superset. Existing rows all
-- hold values from the old list, so the new check passes with no data
-- migration. Also widens category from VARCHAR(20): the longest new code
-- (WORKSHOP_MASTERCLASS) is exactly 20 chars, which would work but leaves
-- zero headroom for the next addition — 30 keeps the invariant boring.

ALTER TABLE events ALTER COLUMN category TYPE VARCHAR(30);

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
