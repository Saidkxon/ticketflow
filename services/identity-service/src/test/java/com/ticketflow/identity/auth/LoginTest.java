package com.ticketflow.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticketflow.identity.AbstractPostgresIntegrationTest;
import com.ticketflow.identity.web.CorrelationIdFilter;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** FR-1 (login), ADR-0004 (neutral errors, throttling, token content) and the public keys endpoint. */
class LoginTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery";

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

    @Test
    void loginReturnsATokenThatVerifiesAgainstThePublishedKeys() throws Exception {
        String email = registerNewUser();

        MvcResult result = mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andReturn();

        String token = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        String keysJson = mockMvc.perform(get("/.well-known/jwks.json"))
                .andReturn().getResponse().getContentAsString();
        JWKSet keys = JWKSet.parse(keysJson);
        SignedJWT jwt = SignedJWT.parse(token);
        JWK key = keys.getKeyByKeyId(jwt.getHeader().getKeyID());

        assertThat(key).isNotNull();
        assertThat(jwt.verify(new RSASSAVerifier(key.toRSAKey()))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("ticketflow-identity");
        assertThat(claims.getAudience()).containsExactly("ticketflow-api");
        assertThat(claims.getStringListClaim("roles")).containsExactly("CUSTOMER");
        assertThat(UUID.fromString(claims.getSubject())).isNotNull();
        assertThat(claims.getExpirationTime().toInstant())
                .isAfter(claims.getIssueTime().toInstant().plusSeconds(890))
                .isBefore(claims.getIssueTime().toInstant().plusSeconds(910));
        assertThat(claims.getClaims()).doesNotContainKeys("email", "name", "displayName");
    }

    @Test
    void theEmailMayBeTypedInAnyLetterCaseWithSpaces() throws Exception {
        String email = registerNewUser();

        mockMvc.perform(login("  " + email.toUpperCase() + " ", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSameAnswer() throws Exception {
        String email = registerNewUser();

        String wrongPassword = mockMvc.perform(login(email, "another password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = mockMvc.perform(login(uniqueEmail(), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        // Equal except for the per-request correlation ID.
        assertThat(withoutCorrelationId(wrongPassword)).isEqualTo(withoutCorrelationId(unknownEmail));
    }

    @Test
    void missingOrOversizedFieldsAreInvalidRequests() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='password')]").exists());
        mockMvc.perform(login(uniqueEmail(), "x".repeat(73)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='password')]").exists());
    }

    @Test
    void fiveWrongPasswordsBlockTheEmailAndEvenTheRightPasswordIsRefusedWhileBlocked() throws Exception {
        String email = registerNewUser();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login(email, "wrong password " + i)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void anUnknownEmailIsBlockedInTheSameWay() throws Exception {
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login(email, "wrong password " + i)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login(email, "wrong password")).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLoginAndKeysEndpointsNeedNoToken() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk());
        mockMvc.perform(login(uniqueEmail(), PASSWORD)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void thePublicKeysEndpointListsTheSigningKeyWithoutPrivateParts() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kid").value(SIGNING_KEY_ID))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.keys[0].q").doesNotExist());
    }

    private String registerNewUser() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                                + "\",\"displayName\":\"Test User\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    private static MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private static String withoutCorrelationId(String problemJson) {
        return problemJson.replaceAll("\"correlationId\":\"[^\"]*\",?", "").replace(",}", "}");
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
