package com.ticketflow.identity.location;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own location consent and positions (contracts/identity.openapi.yaml, ADR-0015).
 * The user ID comes from the verified token, never from the request.
 */
@RestController
@RequestMapping("/api/v1/location")
public class LocationController {

    private final LocationService service;

    public LocationController(LocationService service) {
        this.service = service;
    }

    @GetMapping("/consent")
    public ConsentResponse consent(Authentication authentication) {
        return service.consentOf(UUID.fromString(authentication.getName()));
    }

    @PutMapping("/consent")
    public ConsentResponse answer(@RequestBody ConsentRequest request, Authentication authentication) {
        return service.answer(UUID.fromString(authentication.getName()), request);
    }

    /** 201 when the point is stored, 202 when it is ignored because it came too soon after the previous one. */
    @PostMapping("/positions")
    public ResponseEntity<Void> position(@RequestBody PositionRequest request, Authentication authentication) {
        LocationService.PositionResult result =
                service.record(UUID.fromString(authentication.getName()), request);
        return result == LocationService.PositionResult.STORED
                ? ResponseEntity.status(201).build()
                : ResponseEntity.accepted().build();
    }
}
