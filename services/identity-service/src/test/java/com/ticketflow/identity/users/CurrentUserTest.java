package com.ticketflow.identity.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.identity.AbstractPostgresIntegrationTest;
import com.ticketflow.identity.tokens.AccessTokenService;
import com.ticketflow.identity.tokens.JwtProperties;
import com.ticketflow.identity.tokens.SigningKeys;
import com.ticketflow.identity.web.CorrelationIdFilter;
import jakarta.servlet.Filter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** GET /api/v1/users/me with real tokens through the real security chain (ADR-0004). */
class CurrentUserTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter securityFilterChain;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private SigningKeys keys;

    @Autowired
    private JwtProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, securityFilterChain)
                .build();
    }

    @Test
    void meReturnsTheSignedInUserWithoutThePasswordHash() throws Exception {
        String email = register("Ada Lovelace");
        String token = login(email);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(content().string(not(containsString("bcrypt"))))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void theBearerSchemeIsCaseInsensitive() throws Exception {
        String token = login(register("Ada"));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void withoutATokenTheAnswerIsAPlain401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void anExpiredTokenGetsTheSamePlain401() throws Exception {
        String token = tokenFor(userWithRoles(Role.CUSTOMER), Clock.fixed(
                Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(content().string(not(containsString("expired"))));
    }

    @Test
    void aTamperedTokenGetsTheSamePlain401() throws Exception {
        String token = login(register("Ada"));
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aValidTokenForADeletedUserGetsA401() throws Exception {
        String email = register("Ada");
        String token = login(email);
        jdbc.update("delete from users where email = ?", email);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void meReadsTheRolesFromTheDatabaseNotFromTheToken() throws Exception {
        String email = register("Ada");
        String id = jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
        // A genuine token that claims ADMIN for a user who is only a CUSTOMER in the database.
        User claimsAdmin = new User(UUID.fromString(id), email, "x", "Ada", Set.of(Role.ADMIN));
        String token = tokenFor(claimsAdmin, Clock.systemUTC());

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void aGarbageTokenOnAPublicPathDoesNotBreakIt() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json").header("Authorization", "Bearer garbage"))
                .andExpect(status().isOk());
    }

    @Test
    void theTokenNeverAppearsInTheResponseToABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer secret-token-value"))
                .andExpect(content().string(not(containsString("secret-token-value"))));
    }

    // ---- helpers

    private String tokenFor(User user, Clock clock) {
        return new AccessTokenService(encoder, keys, properties, clock).issue(user).value();
    }

    private static User userWithRoles(Role... roles) {
        return new User(UUID.randomUUID(), "x@example.com", "x", "X", Set.of(roles));
    }

    private String register(String displayName) throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                                + "\",\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");
        assertThat(token).isNotBlank();
        return token;
    }
}
