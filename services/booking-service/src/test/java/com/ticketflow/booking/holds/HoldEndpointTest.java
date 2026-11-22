package com.ticketflow.booking.holds;

import static com.ticketflow.booking.holds.HoldFixtures.eventWithSeats;
import static com.ticketflow.booking.holds.HoldFixtures.sell;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import com.ticketflow.booking.security.BearerTokenFilter;
import com.ticketflow.booking.web.CorrelationIdFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** {@code POST /api/v1/bookings} with the real filters, the error format and a real database. */
class HoldEndpointTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private BearerTokenFilter bearerTokenFilter;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, bearerTokenFilter).build();
    }

    private static String body(UUID event, UUID... seats) {
        String ids = String.join(",", java.util.Arrays.stream(seats).map(s -> "\"" + s + "\"").toList());
        return "{\"eventId\":\"" + event + "\",\"seatIds\":[" + ids + "]}";
    }

    private ResultActions holdRequest(String json, UUID user, String key) throws Exception {
        var request = post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(json)
                .header("Authorization", "Bearer " + tokenFor(user, "CUSTOMER"));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private String bookingId(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.id");
    }

    // ---- the happy path

    @Test
    void aSignedInCustomerHoldsSeatsAndTheyShowAsUnavailable() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 4);

        holdRequest(body(event, seats.get(0), seats.get(1)), UUID.randomUUID(), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.eventId").value(event.toString()))
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andExpect(jsonPath("$.totalMinor").value(10_000))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.serverTime").exists())
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.seats[0].section").value("Hall"))
                .andExpect(jsonPath("$.seats[0].priceMinor").value(5_000));

        mockMvc.perform(get("/api/v1/events/" + event + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailableSeatIds",
                        containsInAnyOrder(seats.get(0).toString(), seats.get(1).toString())));
    }

    // ---- who may call

    @Test
    void withoutATokenTheRequestIsRefused() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 1);

        mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).content(body(event, seats.get(0))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- the idempotency key

    @Test
    void aMissingKeyIsRefused() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 1);

        holdRequest(body(event, seats.get(0)), UUID.randomUUID(), null)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aKeyThatIsNotAUuidIsRefused() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 1);

        holdRequest(body(event, seats.get(0)), UUID.randomUUID(), "not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void aRetryWithTheSameKeyGetsTheSameBookingWithTheSameStatus() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 2);
        UUID user = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        String first = bookingId(holdRequest(body(event, seats.get(0)), user, key).andExpect(status().isCreated()));
        String again = bookingId(holdRequest(body(event, seats.get(0)), user, key).andExpect(status().isCreated()));

        org.assertj.core.api.Assertions.assertThat(again).isEqualTo(first);
    }

    @Test
    void theSameKeyForADifferentRequestIs422() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 2);
        UUID user = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        holdRequest(body(event, seats.get(0)), user, key).andExpect(status().isCreated());

        holdRequest(body(event, seats.get(1)), user, key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    // ---- refusals the customer can act on

    @Test
    void takenSeatsAre409WithTheListOfTheTakenOnes() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 3);
        sell(jdbc, event, seats.get(1));

        holdRequest(body(event, seats.get(0), seats.get(1)), UUID.randomUUID(), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.unavailableSeatIds", containsInAnyOrder(seats.get(1).toString())))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void anEventThatIsNotOnSaleIs404() throws Exception {
        holdRequest(body(UUID.randomUUID(), UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"));
    }

    // ---- bad bodies

    @Test
    void aBodyWithoutSeatsIsRefusedWithTheFieldNamed() throws Exception {
        UUID event = UUID.randomUUID();
        eventWithSeats(jdbc, event, 1);

        holdRequest("{\"eventId\":\"" + event + "\"}", UUID.randomUUID(), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("seatIds"));
        holdRequest(body(event), UUID.randomUUID(), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("seatIds"));
    }

    @Test
    void aBodyThatIsNotJsonIsRefused() throws Exception {
        holdRequest("this is not json", UUID.randomUUID(), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aSeatIdThatIsNotAUuidIsRefused() throws Exception {
        UUID event = UUID.randomUUID();
        eventWithSeats(jdbc, event, 1);

        holdRequest("{\"eventId\":\"" + event + "\",\"seatIds\":[\"seat-1\"]}", UUID.randomUUID(),
                UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
