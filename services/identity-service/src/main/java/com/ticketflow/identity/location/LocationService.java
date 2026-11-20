package com.ticketflow.identity.location;

import com.ticketflow.identity.web.FieldProblem;
import com.ticketflow.identity.web.InvalidRequestException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent, positions, retention and the Admin reads (ADR-0015). Coordinates are never written to a log line:
 * only IDs and counts are.
 */
@Service
public class LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationService.class);

    static final int MAX_HISTORY = 1000;

    /** What happened to a sent point. */
    public enum PositionResult { STORED, IGNORED }

    private final LocationRepository repository;
    private final LocationProperties properties;
    private final Clock clock;

    LocationService(LocationRepository repository, LocationProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- the person's own consent

    @Transactional(readOnly = true)
    public ConsentResponse consentOf(UUID userId) {
        return repository.findConsent(userId)
                .map(c -> new ConsentResponse(c.level(), c.textVersion(), c.decidedAt(),
                        properties.consentTextVersion(), c.textVersion() != properties.consentTextVersion()))
                .orElseGet(() -> new ConsentResponse(null, null, null, properties.consentTextVersion(), true));
    }

    /** Saves the answer. OFF deletes the whole history; MINIMUM deletes points older than its shorter retention. */
    @Transactional
    public ConsentResponse answer(UUID userId, ConsentRequest request) {
        List<FieldProblem> problems = new ArrayList<>();
        if (request == null || request.level() == null) {
            problems.add(new FieldProblem("level", "is required (FULL, MINIMUM or OFF)"));
        }
        if (request == null || request.textVersion() == null) {
            problems.add(new FieldProblem("textVersion", "is required"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
        if (request.textVersion() != properties.consentTextVersion()) {
            throw new ConsentTextOutdatedException();
        }
        Instant now = clock.instant();
        LocationLevel level = request.level();
        repository.saveConsent(userId, level, request.textVersion(), now);
        int deleted = 0;
        if (level == LocationLevel.OFF) {
            deleted = repository.deleteAllPositions(userId);
        } else if (level == LocationLevel.MINIMUM) {
            deleted = repository.deletePositionsBefore(userId, now.minus(properties.minimumRetention()));
        }
        log.info("location consent userId={} level={} deletedPoints={}", userId, level, deleted);
        return consentOf(userId);
    }

    // ---- positions

    /**
     * Stores one point. Needs a current FULL or MINIMUM answer. A point recorded less than the minimum interval
     * after the previous one (or before it) is ignored, not an error: the browser may send more often than needed.
     */
    @Transactional
    public PositionResult record(UUID userId, PositionRequest request) {
        validate(request);
        var consent = repository.findConsent(userId);
        if (consent.isEmpty() || !consent.get().level().tracks()
                || consent.get().textVersion() != properties.consentTextVersion()) {
            throw new LocationConsentRequiredException();
        }
        Optional<Instant> newest = repository.newestRecordedAt(userId);
        if (newest.isPresent() && request.recordedAt().isBefore(newest.get().plus(properties.minInterval()))) {
            return PositionResult.IGNORED;
        }
        repository.insertPosition(userId, request.latitude(), request.longitude(), request.accuracyMeters(),
                request.recordedAt(), clock.instant());
        return PositionResult.STORED;
    }

    private void validate(PositionRequest request) {
        List<FieldProblem> problems = new ArrayList<>();
        if (request.latitude() == null || !Double.isFinite(request.latitude())
                || request.latitude() < -90 || request.latitude() > 90) {
            problems.add(new FieldProblem("latitude", "must be a number between -90 and 90"));
        }
        if (request.longitude() == null || !Double.isFinite(request.longitude())
                || request.longitude() < -180 || request.longitude() > 180) {
            problems.add(new FieldProblem("longitude", "must be a number between -180 and 180"));
        }
        if (request.accuracyMeters() == null || !Double.isFinite(request.accuracyMeters())
                || request.accuracyMeters() < 0 || request.accuracyMeters() > 100_000) {
            problems.add(new FieldProblem("accuracyMeters", "must be a number between 0 and 100000"));
        }
        if (request.recordedAt() == null) {
            problems.add(new FieldProblem("recordedAt", "is required"));
        } else if (request.recordedAt().isAfter(clock.instant().plus(properties.maxClockSkew()))) {
            problems.add(new FieldProblem("recordedAt", "must not be in the future"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
    }

    // ---- Admin reads (each one is written to the access log in the same transaction)

    @Transactional
    public LatestPositions latestOfEveryone(UUID adminId) {
        List<LatestPosition> items = repository.latestOfEveryConsentingUser();
        repository.logAccess(adminId, null, "LATEST_ALL", clock.instant());
        log.info("location read adminId={} kind=LATEST_ALL users={}", adminId, items.size());
        return new LatestPositions(items);
    }

    /** History of one user who consents to tracking. An unknown user and a user who said no look the same: 404. */
    @Transactional
    public UserHistory historyOf(UUID adminId, UUID userId, Instant from, Instant to, int limit) {
        var consent = repository.findConsent(userId);
        Optional<String> name = repository.displayNameOf(userId);
        if (name.isEmpty() || consent.isEmpty() || !consent.get().level().tracks()) {
            throw new LocationNotAvailableException();
        }
        List<HistoryPoint> items = repository.history(userId, from, to, limit);
        repository.logAccess(adminId, userId, "HISTORY", clock.instant());
        log.info("location read adminId={} kind=HISTORY subjectId={} points={}", adminId, userId, items.size());
        return new UserHistory(userId, name.get(), consent.get().level(), items);
    }

    // ---- retention

    /** Deletes expired points. Safe to run any number of times. Returns how many rows were deleted. */
    @Transactional
    public int deleteExpired() {
        Instant now = clock.instant();
        int deleted = repository.deleteExpired(LocationLevel.FULL, now.minus(properties.fullRetention()))
                + repository.deleteExpired(LocationLevel.MINIMUM, now.minus(properties.minimumRetention()))
                + repository.deleteOfUntrackedUsers();
        if (deleted > 0) {
            log.info("location retention deletedPoints={}", deleted);
        }
        return deleted;
    }
}
