package com.ticketflow.identity.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first Admin (ADR-0004), never through public registration: the operator registers
 * normally, sets IDENTITY_BOOTSTRAP_ADMIN_EMAIL to that email and restarts Identity. The setting
 * can stay in place (it does nothing when the user is already an Admin) or be removed afterwards.
 */
@Component
class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final BootstrapProperties properties;
    private final AdminBootstrapService service;

    AdminBootstrapRunner(BootstrapProperties properties, AdminBootstrapService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.adminEmail();
        if (email == null || email.isBlank()) {
            return;
        }
        switch (service.promote(email)) {
            case PROMOTED -> log.warn("bootstrap admin: the configured user is an Admin now");
            case ALREADY_ADMIN -> log.info("bootstrap admin: the configured user is already an Admin");
            case NOT_FOUND -> log.warn("bootstrap admin: no user is registered with the configured email. "
                    + "Register first, then restart Identity.");
        }
    }
}
