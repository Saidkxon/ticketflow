package com.ticketflow.gateway.limits;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LimitsProperties.class)
class LimitsConfig {

    @Bean
    FixedWindowLimiter fixedWindowLimiter() {
        return new FixedWindowLimiter(Clock.systemUTC());
    }
}
