package com.ticketflow.payment.provider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
class ProviderConfig {
}
