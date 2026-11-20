package com.ticketflow.identity.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the retention rule every hour (ADR-0015, L6). The work is in LocationService.deleteExpired and is idempotent. */
@Component
class LocationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(LocationRetentionJob.class);

    private final LocationService service;

    LocationRetentionJob(LocationService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    void run() {
        try {
            service.deleteExpired();
        } catch (RuntimeException e) {
            // The next run tries again; one failure must not stop the schedule.
            log.error("location retention failed", e);
        }
    }
}
