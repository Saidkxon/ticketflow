package com.ticketflow.identity.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import com.ticketflow.identity.AbstractPostgresIntegrationTest;
import com.ticketflow.identity.web.CorrelationIdFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Refresh and logout (ADR-0004): rotation, reuse detection, logout, the cookie, and the
 * header and origin checks. Obligation 5 of ADR-0004 is "an old refresh token revokes the family".
 */
class SessionTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String ORIGIN = "http://localhost:18090";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter securityFilterChain;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, securityFilterChain)
                .build();
    }

    // ---- the cookie set at login

    @Test
    void loginSetsTheRefreshCookieWithAllProtections() throws Exception {
        MvcResult result = login(registerNewUser());

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("refresh_token=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=604800");
        // The refresh token is only in the cookie, never in the body.
        assertThat(result.getResponse().getContentAsString()).doesNotContain(refreshTokenOf(result));
    }

    @Test
    void onlyTheHashOfTheTokenIsStored() throws Exception {
        String token = refreshTokenOf(login(registerNewUser()));

        Integer plain = jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?",
                Integer.class, token);
        Integer hashed = jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?",
                Integer.class, RefreshTokens.hash(token));

        assertThat(plain).isZero();
        assertThat(hashed).isEqualTo(1);
    }

    // ---- refresh and rotation

    @Test
    void refreshGivesANewAccessTokenAndANewCookie() throws Exception {
        String first = refreshTokenOf(login(registerNewUser()));

        MvcResult result = mockMvc.perform(refresh(first))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        String second = refreshTokenOf(result);
        assertThat(second).isNotEqualTo(first);
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(second);
        // The new token works too: a session can be refreshed again and again.
        mockMvc.perform(refresh(second)).andExpect(status().isOk());
    }

    @Test
    void usingAnOldRefreshTokenRevokesTheWholeFamily() throws Exception {
        String a = refreshTokenOf(login(registerNewUser()));
        String b = refreshTokenOf(mockMvc.perform(refresh(a)).andExpect(status().isOk()).andReturn());

        // A is used already: whoever presents it again is a thief or holds a stale copy.
        mockMvc.perform(refresh(a))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // B was never used, but its family is revoked now, so it is dead as well.
        mockMvc.perform(refresh(b))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void anotherLoginOfTheSameUserIsAnotherFamilyAndStaysValid() throws Exception {
        String email = registerNewUser();
        String phone = refreshTokenOf(login(email));
        String laptop = refreshTokenOf(login(email));

        String laptopNext = refreshTokenOf(mockMvc.perform(refresh(laptop)).andReturn());
        mockMvc.perform(refresh(laptop)).andExpect(status().isUnauthorized());

        mockMvc.perform(refresh(laptopNext)).andExpect(status().isUnauthorized());
        mockMvc.perform(refresh(phone)).andExpect(status().isOk());
    }

    @Test
    void twoRefreshesAtTheSameMomentLetExactlyOneWin() throws Exception {
        String token = refreshTokenOf(login(registerNewUser()));
        int callers = 2;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<Integer> call = () -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(refresh(token)).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRoleChangeReachesTheTokenAtTheNextRefresh() throws Exception {
        String email = registerNewUser();
        String token = refreshTokenOf(login(email));
        jdbc.update("insert into user_roles (user_id, role) select id, 'ORGANIZER' from users where email = ?", email);

        MvcResult result = mockMvc.perform(refresh(token)).andExpect(status().isOk()).andReturn();

        String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        assertThat(SignedJWT.parse(accessToken).getJWTClaimsSet().getStringListClaim("roles"))
                .containsExactly("CUSTOMER", "ORGANIZER");
    }

    // ---- bad tokens: always the same neutral answer

    @Test
    void missingUnknownExpiredAndReusedTokensAllGetTheSameAnswer() throws Exception {
        String reused = refreshTokenOf(login(registerNewUser()));
        mockMvc.perform(refresh(reused)).andExpect(status().isOk());
        String expired = refreshTokenOf(login(registerNewUser()));
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 minute' where token_hash = ?",
                RefreshTokens.hash(expired));

        List<String> bodies = new ArrayList<>();
        bodies.add(answerFor(post("/api/v1/auth/refresh").header("X-TicketFlow-Client", "web").header("Origin", ORIGIN)));
        bodies.add(answerFor(refresh("not-a-token-that-exists")));
        bodies.add(answerFor(refresh(expired)));
        bodies.add(answerFor(refresh(reused)));

        assertThat(bodies).hasSize(4).containsOnly(bodies.get(0));
    }

    @Test
    void aRejectedRefreshClearsTheCookie() throws Exception {
        mockMvc.perform(refresh("not-a-token-that-exists"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- logout

    @Test
    void logoutRevokesTheFamilyAndClearsTheCookie() throws Exception {
        String a = refreshTokenOf(login(registerNewUser()));
        String b = refreshTokenOf(mockMvc.perform(refresh(a)).andReturn());

        mockMvc.perform(logout(b))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")));

        mockMvc.perform(refresh(b)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutAValidTokenStillAnswers204() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header("X-TicketFlow-Client", "web").header("Origin", ORIGIN))
                .andExpect(status().isNoContent());
        mockMvc.perform(logout("not-a-token-that-exists")).andExpect(status().isNoContent());
    }

    // ---- request forgery protection

    @Test
    void refreshWithoutTheCustomHeaderIsRefusedAndTheTokenSurvives() throws Exception {
        String token = refreshTokenOf(login(registerNewUser()));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", token)).header("Origin", ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_ORIGIN_NOT_ALLOWED"));

        mockMvc.perform(refresh(token)).andExpect(status().isOk());
    }

    @Test
    void refreshWithAForeignOrNoOriginIsRefusedAndTheTokenSurvives() throws Exception {
        String token = refreshTokenOf(login(registerNewUser()));

        mockMvc.perform(refreshFrom(token, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_ORIGIN_NOT_ALLOWED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", token))
                        .header("X-TicketFlow-Client", "web"))
                .andExpect(status().isForbidden());
        // An origin that merely starts with an allowed one is not an allowed one.
        mockMvc.perform(refreshFrom(token, ORIGIN + ".evil.example")).andExpect(status().isForbidden());

        mockMvc.perform(refresh(token)).andExpect(status().isOk());
    }

    @Test
    void logoutFromAForeignOriginDoesNothing() throws Exception {
        String token = refreshTokenOf(login(registerNewUser()));

        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", token))
                        .header("X-TicketFlow-Client", "web").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());

        mockMvc.perform(refresh(token)).andExpect(status().isOk());
    }

    @Test
    void theRefreshTokenNeverAppearsInAProblemResponse() throws Exception {
        mockMvc.perform(refresh("secret-value-123"))
                .andExpect(content().string(not(containsString("secret-value-123"))));
    }

    // ---- helpers

    private String answerFor(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isUnauthorized()).andReturn();
        String body = result.getResponse().getContentAsString();
        return body.replaceAll("\"correlationId\":\"[^\"]*\",?", "").replace(",}", "}");
    }

    private MvcResult login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String registerNewUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                                + "\",\"displayName\":\"Test User\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    private static String refreshTokenOf(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).as("Set-Cookie header").isNotNull().startsWith("refresh_token=");
        return setCookie.substring("refresh_token=".length(), setCookie.indexOf(';'));
    }

    private static MockHttpServletRequestBuilder refresh(String token) {
        return refreshFrom(token, ORIGIN);
    }

    private static MockHttpServletRequestBuilder refreshFrom(String token, String origin) {
        return post("/api/v1/auth/refresh")
                .cookie(new Cookie("refresh_token", token))
                .header("X-TicketFlow-Client", "web")
                .header("Origin", origin);
    }

    private static MockHttpServletRequestBuilder logout(String token) {
        return post("/api/v1/auth/logout")
                .cookie(new Cookie("refresh_token", token))
                .header("X-TicketFlow-Client", "web")
                .header("Origin", ORIGIN);
    }
}
