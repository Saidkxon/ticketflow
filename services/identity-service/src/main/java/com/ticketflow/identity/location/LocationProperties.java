package com.ticketflow.identity.location;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for location tracking (ADR-0015).
 *
 * @param consentTextVersion  the version of the consent text the web app shows; raise it when the text changes
 *                            and everybody is asked again
 * @param fullRetention       how long FULL keeps points
 * @param minimumRetention    how long MINIMUM keeps points
 * @param minInterval         a point recorded less than this after the previous one is ignored
 * @param maxClockSkew        how far ahead of the server clock a point's time may be
 */
@ConfigurationProperties("ticketflow.identity.location")
public record LocationProperties(
        @DefaultValue("1") int consentTextVersion,
        @DefaultValue("30d") Duration fullRetention,
        @DefaultValue("5d") Duration minimumRetention,
        @DefaultValue("30s") Duration minInterval,
        @DefaultValue("60s") Duration maxClockSkew) {

    Duration retentionOf(LocationLevel level) {
        return level == LocationLevel.FULL ? fullRetention : minimumRetention;
    }
}
