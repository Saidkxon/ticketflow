package com.ticketflow.identity.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticketflow.identity.AbstractPostgresIntegrationTest;
import com.ticketflow.identity.web.CorrelationIdFilter;
import jakarta.servlet.Filter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * POST /internal/v1/service-tokens through the whole application (ADR-0004, Part D; test obligation 7): a service
 * with the right secret gets a token, everything else is refused with a plain problem+json that gives nothing away,
 * and a service token does not open a public endpoint.
 */
class ServiceTokenEndpointTest extends AbstractPostgresIntegrationTest {

    private static final String PATH = "/internal/v1/service-tokens";
    private static final String CORRELATION_ID = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter securityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, securityFilterChain)
                .build();
    }

    private static String body(String clientId, String secret, String audience, String scopesJson) {
        return "{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + secret + "\",\"audience\":\"" + audience
                + "\",\"scopes\":" + scopesJson + "}";
    }

    /** The access token inside the answer (a plain JSON text search: the test needs nothing more). */
    private static String tokenIn(String answer) {
        Matcher matcher = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(answer);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String goodBody() {
        return body("booking-service", TEST_CLIENT_SECRET, "payment-service", "[\"payment.attempt.create\"]");
    }

    @Test
    void aServiceWithTheRightSecretGetsATokenThatIsNeverCached() throws Exception {
        String answer = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(goodBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(300))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = tokenIn(answer);
        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("booking-service");
        assertThat(claims.getAudience()).containsExactly("payment-service");
        assertThat(claims.getStringClaim("scope")).isEqualTo("payment.attempt.create");
        assertThat(claims.getClaim("roles")).isNull();
    }

    @Test
    void aWrongSecretAndAnUnknownServiceGetTheSameAnswer() throws Exception {
        String wrongSecret = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                        .content(body("booking-service", "not-the-secret", "payment-service", "[\"payment.attempt.create\"]")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CLIENT"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(content().string(not(containsString("not-the-secret"))))
                .andReturn().getResponse().getContentAsString();
        String unknown = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                        .content(body("nobody-service", "not-the-secret", "payment-service", "[\"payment.attempt.create\"]")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(wrongSecret);
    }

    @Test
    void aKnownServiceAskingForMoreThanItMayHaveIsForbidden() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body("booking-service", TEST_CLIENT_SECRET, "payment-service", "[\"payment.refund.create\"]")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_ALLOWED"));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body("booking-service", TEST_CLIENT_SECRET, "identity-service", "[\"payment.attempt.create\"]")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_ALLOWED"));
    }

    @Test
    void aBadRequestIsRefusedWithTheListOfProblems() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(4));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Booking Service", "x", "payment-service", "[\"Payment Create\"]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body("booking-service", "x", "payment-service", "[]")))
                .andExpect(status().isBadRequest());
        // A field nobody knows is refused, so nobody can slip in something like "roles".
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"booking-service\",\"clientSecret\":\"x\",\"audience\":\"payment-service\","
                                + "\"scopes\":[\"payment.attempt.create\"],\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSecretIsNeverRepeatedInAnAnswer() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body("booking-service", TEST_CLIENT_SECRET, "payment-service", "[\"payment.refund.create\"]")))
                .andExpect(content().string(not(containsString(TEST_CLIENT_SECRET))));
    }

    @Test
    void otherPathsUnderInternalStayClosedAndOnlyPostIsOpen() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/v1/anything")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/v1/anything").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aServiceTokenDoesNotOpenAPublicEndpoint() throws Exception {
        String answer = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(goodBody()))
                .andReturn().getResponse().getContentAsString();
        String token = tokenIn(answer);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
