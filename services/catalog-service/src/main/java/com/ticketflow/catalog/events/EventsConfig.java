package com.ticketflow.catalog.events;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class EventsConfig {

    /** The application clock, so rules about "now" can be tested. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
