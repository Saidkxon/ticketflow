package com.ticketflow.payment.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.payment.AbstractPostgresIntegrationTest;
import com.ticketflow.payment.security.ServiceTokenFilter;
import com.ticketflow.payment.web.CorrelationIdFilter;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Creating and reading payment attempts through the whole application, with the real filters (ADR-0006 rule R2, test
 * obligation 7; ADR-0004 Part D): a repeated request makes one payment, a changed request under the same key is refused,
 * and only a service with the right scope gets in.
 */
class PaymentAttemptTest extends AbstractPostgresIntegrationTest {

    private static final String PATH = "/internal/v1/payment-attempts";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private ServiceTokenFilter serviceTokenFilter;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, serviceTokenFilter)
                .build();
    }

    private static String createToken() {
        return serviceToken("booking-service", "payment.attempt.create", "payment.attempt.read");
    }

    private static String body(UUID bookingId, long amountMinor, String currency) {
        return "{\"bookingId\":\"" + bookingId + "\",\"amountMinor\":" + amountMinor + ",\"currency\":\"" + currency + "\"}";
    }

    private static MockHttpServletRequestBuilder create(String token, String key, String json) {
        MockHttpServletRequestBuilder request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request;
    }

    private int rowsOf(UUID bookingId) {
        return jdbc.queryForObject("select count(*) from payments where booking_id = ?", Integer.class, bookingId);
    }

    private static String paymentIdIn(String json) {
        Matcher matcher = Pattern.compile("\"paymentId\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    // ---- creating

    @Test
    void anAttemptIsCreatedPendingAndTheAnswerIsNeverCached() throws Exception {
        UUID booking = UUID.randomUUID();

        String answer = mockMvc.perform(create(createToken(), booking + ":1", body(booking, 150_000, "UZS")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/internal/v1/payment-attempts/")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.bookingId").value(booking.toString()))
                .andExpect(jsonPath("$.attemptNumber").value(1))
                .andExpect(jsonPath("$.amountMinor").value(150_000))
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(rowsOf(booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from payments where payment_id = ?::uuid", String.class,
                paymentIdIn(answer))).isEqualTo("PENDING");
    }

    @Test
    void theSameRequestRepeatedGivesTheSamePaymentAndCreatesNoSecondOne() throws Exception {
        UUID booking = UUID.randomUUID();
        String first = mockMvc.perform(create(createToken(), booking + ":1", body(booking, 99_000, "UZS")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(create(createToken(), booking + ":1", body(booking, 99_000, "UZS")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(paymentIdIn(second)).isEqualTo(paymentIdIn(first));
        assertThat(rowsOf(booking)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithAnotherAmountOrCurrencyIsRefusedAndChangesNothing() throws Exception {
        UUID booking = UUID.randomUUID();
        mockMvc.perform(create(createToken(), booking + ":1", body(booking, 99_000, "UZS")))
                .andExpect(status().isAccepted());

        mockMvc.perform(create(createToken(), booking + ":1", body(booking, 1_000, "UZS")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(create(createToken(), booking + ":1", body(booking, 99_000, "USD")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(rowsOf(booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount_minor from payments where booking_id = ?", Long.class, booking))
                .isEqualTo(99_000L);
    }

    @Test
    void aNewAttemptNumberOfTheSameBookingIsANewPayment() throws Exception {
        UUID booking = UUID.randomUUID();
        String first = mockMvc.perform(create(createToken(), booking + ":1", body(booking, 99_000, "UZS")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(create(createToken(), booking + ":2", body(booking, 99_000, "UZS")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptNumber").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(paymentIdIn(second)).isNotEqualTo(paymentIdIn(first));
        assertThat(rowsOf(booking)).isEqualTo(2);
    }

    // ---- bad requests

    @Test
    void aMissingOrMalformedIdempotencyKeyIsRefused() throws Exception {
        UUID booking = UUID.randomUUID();
        String json = body(booking, 99_000, "UZS");

        mockMvc.perform(create(createToken(), null, json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        for (String key : new String[] {booking.toString(), booking + ":", booking + ":0", booking + ":100",
                booking + ":x", "not-a-uuid:1", ":1", booking + ":1:2"}) {
            mockMvc.perform(create(createToken(), key, json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
        }
        assertThat(rowsOf(booking)).isZero();
    }

    @Test
    void aBodyThatDisagreesWithTheKeyOrIsIncompleteIsRefused() throws Exception {
        UUID booking = UUID.randomUUID();

        mockMvc.perform(create(createToken(), booking + ":1", body(UUID.randomUUID(), 99_000, "UZS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(create(createToken(), booking + ":1", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
        for (String json : new String[] {body(booking, 0, "UZS"), body(booking, -5, "UZS"),
                body(booking, 2_000_000_000_000L, "UZS"), body(booking, 99_000, "uzs"), body(booking, 99_000, "UZSX")}) {
            mockMvc.perform(create(createToken(), booking + ":1", json)).andExpect(status().isBadRequest());
        }
        // A field nobody knows is refused, so a caller cannot slip in something like a status.
        mockMvc.perform(create(createToken(), booking + ":1",
                        "{\"bookingId\":\"" + booking + "\",\"amountMinor\":1000,\"currency\":\"UZS\",\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isBadRequest());
        assertThat(rowsOf(booking)).isZero();
    }

    // ---- who may call

    @Test
    void noTokenAUsersTokenOrTheWrongScopeAreRefusedAndCreateNothing() throws Exception {
        UUID booking = UUID.randomUUID();
        String json = body(booking, 99_000, "UZS");

        mockMvc.perform(create(null, booking + ":1", json)).andExpect(status().isUnauthorized());
        mockMvc.perform(create(userToken(UUID.randomUUID(), "CUSTOMER", "ADMIN"), booking + ":1", json))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(create(serviceToken("booking-service", "payment.attempt.read"), booking + ":1", json))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(rowsOf(booking)).isZero();
    }

    // ---- reading

    @Test
    void anAttemptCanBeReadByItsPaymentIdWithTheReadScope() throws Exception {
        UUID booking = UUID.randomUUID();
        String created = mockMvc.perform(create(createToken(), booking + ":1", body(booking, 99_000, "UZS")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = paymentIdIn(created);

        mockMvc.perform(get(PATH + "/" + id)
                        .header("Authorization", "Bearer " + serviceToken("booking-service", "payment.attempt.read")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.paymentId").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void readingNeedsItsOwnScopeAndAnUnknownPaymentIsA404() throws Exception {
        mockMvc.perform(get(PATH + "/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + serviceToken("booking-service", "payment.attempt.create")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH + "/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + serviceToken("booking-service", "payment.attempt.read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get(PATH + "/not-a-uuid")
                        .header("Authorization", "Bearer " + serviceToken("booking-service", "payment.attempt.read")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
