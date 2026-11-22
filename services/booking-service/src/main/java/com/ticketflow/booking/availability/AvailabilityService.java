package com.ticketflow.booking.availability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The list of unavailable seats of an event, kept in memory for a second or two (ADR-0011, ADR-0012): fifty browsers
 * polling the same event cost about one database query per refresh. The cache holds only what can be recomputed, so it
 * is not state that must survive a restart. Several Booking copies each keep their own, which is fine at this size.
 *
 * <p>While one request refreshes an event, the others for the same event wait for that result instead of asking the
 * database too. Events that are not on sale are not cached (so unknown IDs cannot fill the memory); the cache is
 * emptied when it grows past a sanity limit.
 */
@Service
public class AvailabilityService {

    /** What the seat map needs to draw the current state, and the ETag that identifies exactly this content. */
    public record Snapshot(UUID eventId, List<UUID> unavailableSeatIds, String etag) {
    }

    private record Entry(Snapshot snapshot, long loadedAtNanos) {
    }

    static final int MAX_ENTRIES = 1_000;

    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final UnavailableSeats source;
    private final long ttlNanos;
    private final LongSupplier nanoTime;

    @Autowired
    AvailabilityService(UnavailableSeats source, AvailabilityProperties properties) {
        this(source, properties, System::nanoTime);
    }

    /** For tests, which need to move the clock. */
    AvailabilityService(UnavailableSeats source, AvailabilityProperties properties, LongSupplier nanoTime) {
        this.source = source;
        this.ttlNanos = properties.cacheTtl().toNanos();
        this.nanoTime = nanoTime;
    }

    /** The current snapshot, or empty when the event is not on sale. */
    public Optional<Snapshot> find(UUID eventId) {
        Entry entry = cache.compute(eventId, (id, current) -> {
            long now = nanoTime.getAsLong();
            if (current != null && now - current.loadedAtNanos() < ttlNanos) {
                return current;
            }
            return source.unavailableSeatIds(id)
                    .map(ids -> new Entry(new Snapshot(id, ids, etagOf(id, ids)), now))
                    .orElse(null);
        });
        if (cache.size() > MAX_ENTRIES) {
            long now = nanoTime.getAsLong();
            cache.values().removeIf(e -> now - e.loadedAtNanos() >= ttlNanos);
            if (cache.size() > MAX_ENTRIES) {
                cache.clear();
            }
        }
        return Optional.ofNullable(entry).map(Entry::snapshot);
    }

    /** The tag value: the same seats unavailable give the same tag, any change gives another. Quotes included, no {@code W/}; the controller sends it as a weak tag. */
    static String etagOf(UUID eventId, List<UUID> sortedIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(eventId.toString().getBytes(StandardCharsets.UTF_8));
            for (UUID id : sortedIds) {
                digest.update((byte) '\n');
                digest.update(id.toString().getBytes(StandardCharsets.UTF_8));
            }
            return "\"" + HexFormat.of().formatHex(digest.digest(), 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether an {@code If-None-Match} header names this ETag: one tag, a list of tags, a weak tag (compared by its
     * value, as the rule for this header says), or {@code *}.
     */
    static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.trim();
            if (value.startsWith("W/")) {
                value = value.substring(2);
            }
            if (value.equals("*") || value.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
