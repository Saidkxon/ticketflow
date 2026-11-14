package com.ticketflow.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.identity.AbstractPostgresIntegrationTest;
import com.ticketflow.identity.web.CorrelationIdFilter;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** FR-1 (registration) and the registration parts of ADR-0004 and the Identity contract. */
@SpringBootTest
class RegistrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter securityFilterChain;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // The same filters, in the same order, as the running service.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, securityFilterChain)
                .build();
    }

    @Test
    void registersACustomerAndNeverReturnsTheHash() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(register(email, PASSWORD, "Ada Lovelace"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    void storesOnlyAHashOfThePassword() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(register(email, PASSWORD, "Grace Hopper")).andExpect(status().isCreated());

        String stored = jdbc.queryForObject(
                "select password_hash from users where email = ?", String.class, email);
        assertThat(stored).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
        assertThat(passwordEncoder.matches("another password", stored)).isFalse();
    }

    @Test
    void theNewUserHasExactlyTheCustomerRoleInTheDatabase() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(register(email, PASSWORD, "Linus")).andExpect(status().isCreated());

        List<String> roles = jdbc.queryForList(
                "select r.role from user_roles r join users u on u.id = r.user_id where u.email = ?",
                String.class, email);
        assertThat(roles).containsExactly("CUSTOMER");
    }

    @Test
    void emailIsStoredInLowerCaseAndTheSameEmailInAnyCaseIsRejected() throws Exception {
        String lower = uniqueEmail();

        mockMvc.perform(register("  " + lower.toUpperCase() + " ", PASSWORD, "First"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(lower));

        mockMvc.perform(register(lower, PASSWORD, "Second"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.correlationId").exists());

        Integer rows = jdbc.queryForObject(
                "select count(*) from users where lower(email) = ?", Integer.class, lower);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void rejectsInvalidInputWithTheFieldNamed() throws Exception {
        assertInvalid("{}", "email", "password", "displayName");
        assertInvalid(json("not-an-email", PASSWORD, "Name"), "email");
        assertInvalid(json(uniqueEmail(), "123456789", "Name"), "password");
        assertInvalid(json(uniqueEmail(), "x".repeat(73), "Name"), "password");
        assertInvalid(json(uniqueEmail(), "   ".repeat(2) + "abcd", null), "displayName");
        assertInvalid(json(uniqueEmail(), PASSWORD, "   "), "displayName");
        assertInvalid(json(uniqueEmail(), PASSWORD, "n".repeat(81)), "displayName");
    }

    @Test
    void rejectsAPasswordLongerThan72BytesEvenWhenItHasFewerCharacters() throws Exception {
        // 40 characters, but each one takes 2 bytes in UTF-8: 80 bytes, more than the hashing limit.
        String password = "ж".repeat(40);

        assertInvalid(json(uniqueEmail(), password, "Name"), "password");
    }

    @Test
    void aCallerCannotChooseTheirOwnRole() throws Exception {
        String email = uniqueEmail();
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                + "\",\"displayName\":\"Mallory\",\"roles\":[\"ADMIN\"]}";

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Integer rows = jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
        assertThat(rows).isZero();
    }

    @Test
    void rejectsBrokenJson() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void twoRequestsForTheSameEmailAtTheSameMomentCreateOneAccount() throws Exception {
        String email = uniqueEmail();
        int callers = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return mockMvc.perform(register(email, PASSWORD, "Racer"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        Integer rows = jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
        assertThat(rows).isEqualTo(1);
    }

    private void assertInvalid(String body, String... fields) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").exists());
        for (String field : fields) {
            result.andExpect(jsonPath("$.errors[?(@.field=='%s')]", field).exists());
        }
        result.andExpect(content().string(not(containsString(PASSWORD))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
            String email, String password, String displayName) {
        return post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(email, password, displayName));
    }

    private static String json(String email, String password, String displayName) {
        return "{\"email\":" + quote(email) + ",\"password\":" + quote(password)
                + ",\"displayName\":" + quote(displayName) + "}";
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
