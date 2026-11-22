package com.ticketflow.booking.expiry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExpiryProperties.class)
class ExpiryConfig {
}
