package com.ticketflow.identity.users;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** The email must already be normalized (trimmed, lower case). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
