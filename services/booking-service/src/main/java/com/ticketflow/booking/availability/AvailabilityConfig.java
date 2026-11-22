package com.ticketflow.booking.availability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AvailabilityProperties.class)
class AvailabilityConfig {
}
