package com.ticketflow.payment.provider;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the payment processing (ticketflow.payment.provider).
 *
 * @param scheduleEnabled  whether the processor runs by itself every {@code interval}; tests switch it off and call it
 * @param interval         the wait between two runs
 * @param batchSize        how many payments one run takes
 * @param recheckInterval  how long to wait before asking the provider again about a payment that is still undecided
 * @param lateResultDelay  how long the simulator keeps a timed-out charge hidden before it shows the result; longer than
 *                         the 2-minute grace of Booking, so the "late success" case really arrives late
 */
@ConfigurationProperties("ticketflow.payment.provider")
public record ProviderProperties(
        @DefaultValue("true") boolean scheduleEnabled,
        @DefaultValue("PT1S") Duration interval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("PT30S") Duration recheckInterval,
        @DefaultValue("PT3M") Duration lateResultDelay) {
}
