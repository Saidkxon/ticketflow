package com.ticketflow.payment.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class TokenConfig {

    /**
     * Identity's public keys, fetched on first use (so Payment starts even when Identity is not up
     * yet), kept for the cache time, and fetched again when a token names a key ID that is not known:
     * that is how a key rotation reaches this service without a restart (ADR-0004, test obligation 8).
     */
    @Bean
    JWKSource<SecurityContext> identityKeys(JwtProperties properties) throws MalformedURLException {
        if (properties.jwksUri() == null || properties.jwksUri().isBlank()) {
            throw new IllegalStateException("The keys address is not set (ticketflow.payment.jwt.jwks-uri)");
        }
        URL url = URI.create(properties.jwksUri()).toURL();
        // Short timeouts and a small size limit: the key list is tiny and Identity is next door.
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(2000, 2000, 51200);
        JWKSourceBuilder<SecurityContext> builder = JWKSourceBuilder.<SecurityContext>create(url, retriever)
                .cache(properties.cacheTtl().toMillis(), 15_000)
                .retrying(true);
        if (properties.minRefetchInterval().isZero()) {
            builder = builder.rateLimited(false);
        } else {
            builder = builder.rateLimited(properties.minRefetchInterval().toMillis());
        }
        return builder.build();
    }

    @Bean
    ServiceTokenVerifier serviceTokenVerifier(JWKSource<SecurityContext> identityKeys, JwtProperties properties) {
        return new ServiceTokenVerifier(identityKeys, properties.issuer(), properties.audience());
    }
}
