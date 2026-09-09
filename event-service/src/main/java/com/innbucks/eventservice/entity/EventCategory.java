package com.innbucks.eventservice.entity;

/**
 * The category vocabulary an organizer files an event under — 64 values
 * covering the ticketed-event landscape of Zimbabwe and Kenya (galas, gospel,
 * crusades, harambee-style fundraisers, agricultural shows, rugby sevens,
 * boat cruises, …), extended from the original seven in V12.
 *
 * <p><b>Declared in curated display order</b>: {@code
 * GET /events/categories} returns {@code values()} verbatim, grouped so the
 * FE can render section headers without its own ordering table. Safe because
 * the column is {@code @Enumerated(STRING)} and nothing persists or compares
 * ordinals — pinned by {@code EventCategoryVocabularyTest}.
 *
 * <p><b>Adding a value?</b> Three places must move together or creates start
 * failing with a DB CHECK violation (V10's lesson): this enum, a new
 * migration recreating {@code chk_events_category} with the full list, and
 * the FE learns it automatically via {@code GET /events/categories}. Codes
 * must fit the {@code VARCHAR(30)} column (V12). Never remove or rename a
 * value — rows reference the name.
 */
public enum EventCategory {

    // ── Music & live shows ──────────────────────────────────────
    CONCERT("Concert"),  // original-seven value — pre-V12 rows reference it
    GOSPEL_CONCERT("Gospel Concert"),
    MUSIC_FESTIVAL("Music Festival"),
    MUSIC_GALA("Musical Gala"),
    SUNGURA_SHOW("Sungura Show"),
    DANCEHALL_SHOW("Dancehall Show"),
    ALBUM_LAUNCH("Album Launch"),
    NIGHTLIFE("Nightlife & Parties"),

    // ── Arts & culture ──────────────────────────────────────────
    ARTS_FESTIVAL("Arts Festival"),
    CULTURAL_FESTIVAL("Cultural & Heritage Festival"),
    THEATRE("Theatre & Stage Play"),
    DANCE_PERFORMANCE("Dance Performance"),
    COMEDY("Comedy Show"),  // original-seven value — pre-V12 rows reference it
    FILM_SCREENING("Film & Cinema"),
    POETRY_SPOKEN_WORD("Poetry & Spoken Word"),
    BOOKS("Books & Literature"),  // original-seven value — pre-V12 rows reference it
    ART_EXHIBITION("Art Exhibition"),
    FASHION_SHOW("Fashion Show"),
    PAGEANT("Beauty Pageant"),

    // ── Faith ───────────────────────────────────────────────────
    CHURCH_CONFERENCE("Church Conference"),
    CRUSADE("Crusade & Revival"),
    ISLAMIC_EVENT("Islamic Event"),
    RETREAT("Retreat & Camp"),

    // ── Social & ceremonies ─────────────────────────────────────
    AWARDS_CEREMONY("Awards Ceremony"),
    CHARITY_FUNDRAISER("Charity & Fundraiser"),
    DINNER_DANCE("Dinner & Dance"),
    GRADUATION("Graduation Ceremony"),
    ALUMNI_REUNION("Alumni Reunion"),
    SCHOOL_EVENT("School & Campus Event"),
    SEASONAL_HOLIDAY("Seasonal & Holiday"),

    // ── Business & learning ─────────────────────────────────────
    CONFERENCE("Business Conference & Summit"),
    TECH_STARTUP("Tech & Startup Event"),
    WORKSHOP_MASTERCLASS("Workshop & Masterclass"),
    NETWORKING("Networking & Mixer"),
    PRODUCT_LAUNCH("Product Launch"),
    CAREER_FAIR("Career & Education Fair"),

    // ── Shows & expos ───────────────────────────────────────────
    AGRICULTURAL_SHOW("Agricultural Show"),
    TRADE_EXPO("Trade Fair & Expo"),

    // ── Food, family & lifestyle ────────────────────────────────
    FOOD_DRINK_FESTIVAL("Food & Drink Festival"),
    DINING_EXPERIENCE("Pop-Up Dining & Brunch"),
    KIDS_FAMILY("Kids & Family Fun Day"),
    GAMING_ESPORTS("Gaming & Esports"),
    BOAT_CRUISE("Boat Cruise & Party"),
    OUTDOOR_ADVENTURE("Outdoor & Adventure"),
    TOUR_GETAWAY("Tours & Getaways"),
    HEALTH_WELLNESS("Health & Wellness"),

    // ── Sports ──────────────────────────────────────────────────
    SPORT("Sports (General)"),  // original-seven value — pre-V12 rows reference it
    FOOTBALL("Football Match"),
    CRICKET("Cricket Match"),
    RUGBY("Rugby Match"),
    RUGBY_SEVENS("Rugby Sevens"),
    ATHLETICS("Athletics Meet"),
    FUN_RUN("Fun Run"),  // original-seven value — pre-V12 rows reference it
    HALF_MARATHON("Half Marathon"),  // original-seven value — pre-V12 rows reference it
    MARATHON("Marathon"),  // original-seven value — pre-V12 rows reference it
    MOTORSPORT("Motorsport & Car Show"),
    COMBAT_SPORTS("Boxing & MMA"),
    GOLF("Golf Tournament"),
    BASKETBALL("Basketball Game"),
    NETBALL("Netball Match"),
    VOLLEYBALL("Volleyball Match"),
    HORSE_RACING("Horse Racing"),
    CYCLING("Cycling Event"),

    // ── Fallback ────────────────────────────────────────────────
    OTHER("Other");

    private final String displayName;

    EventCategory(String displayName) {
        this.displayName = displayName;
    }

    /** Human label for FE dropdowns; the enum name stays the wire code. */
    public String getDisplayName() {
        return displayName;
    }
}
