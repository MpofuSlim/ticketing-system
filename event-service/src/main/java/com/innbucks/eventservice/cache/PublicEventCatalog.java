package com.innbucks.eventservice.cache;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.mapper.EventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * The cache behind the ANONYMOUS-BRANCH event listings — {@code GET /events}
 * and {@code /events/active} for a caller who is neither an organizer nor a
 * team member, {@code /events/search} and {@code /events/by-country}. What it
 * holds is the database's answer (which events, in which order, how many in
 * total) as immutable {@link EventSnapshot}s; availability and organizer
 * details are attached per request by the caller, from live / separately
 * cached sources.
 *
 * <p><b>Why caching these is safe for every caller.</b> Each of the four
 * service methods that reach this answers the same thing whoever asks —
 * published events only, the organizer id stripped — and the controller sends
 * organizers and team members to DIFFERENT, uncached methods. So the key needs
 * no caller identity, only every query input: the query kind, the date window,
 * venue, country, category, search text, page, size and sort. The cell is
 * implicit (a local cache never leaves its pod, and a pod serves one cell).
 *
 * <p><b>Staleness.</b> Any event write in event-service clears the whole cache
 * on the pod that served it (a write can move an event in or out of any page).
 * Other replicas keep their pages until the TTL ({@code events.cache.public-event-pages.ttl},
 * 30s): an event just published, edited, unpublished, rejected or deleted can
 * be shown as it was for up to that long by another pod. The by-id detail read
 * is NOT cached, so opening an unpublished event is a 404 at once. The list's
 * "ended" cutoff is evaluated at load time, so an event can linger up to the
 * TTL past its end. The stored {@code availableTickets} fallback can lag by
 * the TTL too; it is shown only where booking-service reports no live count,
 * and availability writes deliberately do not clear the cache (a confirmed
 * booking would otherwise empty it on every sale).
 */
@Component
public class PublicEventCatalog {

    private final ReadThroughCache pages;
    private final EventMapper mapper;

    @Autowired
    public PublicEventCatalog(@Qualifier(ReadCacheConfig.READ_CACHE_MANAGER) CacheManager cacheManager,
                              EventMapper mapper) {
        this(ReadThroughCache.of(cacheManager, ReadCacheConfig.PUBLIC_EVENT_PAGES), mapper);
    }

    private PublicEventCatalog(ReadThroughCache pages, EventMapper mapper) {
        this.pages = pages;
        this.mapper = mapper;
    }

    /** A catalog that caches nothing: every call loads. */
    public static PublicEventCatalog uncached(EventMapper mapper) {
        return new PublicEventCatalog(ReadThroughCache.disabled(ReadCacheConfig.PUBLIC_EVENT_PAGES), mapper);
    }

    /**
     * The page for {@code key}, from the cache or from {@code loader}. The
     * returned page is new on every call and carries the caller's own
     * {@code pageable}.
     */
    public Page<EventSnapshot> page(PageKey key, Pageable pageable, Supplier<Page<Event>> loader) {
        SnapshotPage cached = pages.get(key, () -> SnapshotPage.of(loader.get(), mapper));
        return new PageImpl<>(cached.content(), pageable, cached.total());
    }

    /** Clears every cached page (now, and again after the caller's commit). */
    public void invalidateAll() {
        pages.invalidateAll();
    }

    /**
     * Every input that shapes a public list page. {@code kind} separates the
     * query families, so a search for "Harare" and a venue filter "Harare"
     * never share an entry. Strings are kept exactly as received: two
     * spellings that the query treats alike are two entries, never one entry
     * answering for a different question.
     */
    public record PageKey(String kind, LocalDateTime from, LocalDateTime to, String venue, String country,
                          EventCategory category, String query, int page, int size, String sort) {

        public static PageKey active(LocalDateTime from, LocalDateTime to, String venue, String country,
                                     EventCategory category, int page, int size, String sort) {
            return new PageKey("active", from, to, venue, country, category, null, page, size, sort);
        }

        public static PageKey search(String query, int page, int size, String sort) {
            return new PageKey("search", null, null, null, null, null, query, page, size, sort);
        }

        public static PageKey byCountry(String country, int page, int size) {
            return new PageKey("by-country", null, null, null, country, null, null, page, size, "startDateTime");
        }
    }

    /** What is cached for one page: immutable snapshots plus the total. */
    record SnapshotPage(List<EventSnapshot> content, long total) implements ReadCacheConfig.Weighted {

        static SnapshotPage of(Page<Event> page, EventMapper mapper) {
            List<EventSnapshot> snapshots = new ArrayList<>(page.getNumberOfElements());
            for (Event event : page.getContent()) {
                snapshots.add(EventSnapshot.of(mapper.toDTO(event)));
            }
            // unmodifiableList, not List.copyOf: a mocked mapper may yield null.
            return new SnapshotPage(Collections.unmodifiableList(snapshots), page.getTotalElements());
        }

        @Override
        public int weight() {
            return content.size();
        }
    }
}
