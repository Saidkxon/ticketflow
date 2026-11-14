package com.ticketflow.identity.tokens;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

    @Bean
    SigningKeys signingKeys(JwtProperties properties) {
        return SigningKeys.load(properties.keyDirectory(), properties.signingKeyId());
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeys keys) {
        return new NimbusJwtEncoder(keys.encoderSource());
    }

    /** One clock for the service, so tests can replace it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
