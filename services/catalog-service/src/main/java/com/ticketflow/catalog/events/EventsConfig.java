package com.ticketflow.catalog.events;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class EventsConfig {

    /** The application clock, so rules about "now" can be tested. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** For work that must be one transaction, such as replacing a whole layout. */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
