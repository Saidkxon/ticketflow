package com.ticketflow.booking.availability;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import com.ticketflow.booking.security.BearerTokenFilter;
import com.ticketflow.booking.web.CorrelationIdFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The availability endpoint with the real filters and a real database (ADR-0011, ADR-0012). The seat states are set
 * with plain SQL, because the code that takes holds does not exist yet. The integration tests run without the cache
 * (see AbstractPostgresIntegrationTest), so every change is visible at once.
 */
class AvailabilityEndpointTest extends AbstractPostgresIntegrationTest {

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

    // ---- helpers

    /** An event on sale with this many free seats; returns the seat IDs. */
    private List<UUID> eventWithSeats(UUID eventId, int seats) {
        jdbc.update("INSERT INTO event_inventory (event_id, layout_version, currency, total_seats) "
                + "VALUES (?, 1, 'UZS', ?)", eventId, seats);
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= seats; i++) {
            UUID seat = UUID.randomUUID();
            ids.add(seat);
            jdbc.update("INSERT INTO event_seats (event_id, seat_id, section_name, row_label, seat_number, tier_name, "
                    + "price_minor) VALUES (?, ?, 'Hall', 'A', ?, 'Standard', 5000)", eventId, seat, i);
        }
        return ids;
    }

    private void sell(UUID eventId, UUID seat) {
        jdbc.update("UPDATE event_seats SET state = 'SOLD', booking_id = ?, held_until = NULL "
                + "WHERE event_id = ? AND seat_id = ?", UUID.randomUUID(), eventId, seat);
    }

    private void hold(UUID eventId, UUID seat, String interval) {
        jdbc.update("UPDATE event_seats SET state = 'HELD', booking_id = ?, held_until = now() + ?::interval "
                + "WHERE event_id = ? AND seat_id = ?", UUID.randomUUID(), interval, eventId, seat);
    }

    private static List<String> sorted(List<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).map(UUID::toString).toList();
    }

    // ---- what is unavailable

    @Test
    void anEventWithEverySeatFreeHasAnEmptyList() throws Exception {
        UUID event = UUID.randomUUID();
        eventWithSeats(event, 5);

        mockMvc.perform(get("/api/v1/events/" + event + "/availability"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.eventId").value(event.toString()))
                .andExpect(jsonPath("$.unavailableSeatIds").isEmpty())
                .andExpect(header().string("ETag", org.hamcrest.Matchers.startsWith("W/\""))) 
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void soldSeatsAndLiveHoldsAreUnavailableButAnExpiredHoldIsNot() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 6);
        sell(event, seats.get(0));
        hold(event, seats.get(1), "10 minutes");
        hold(event, seats.get(2), "-1 minute");   // expired, and no sweeper has run: the seat is takeable (ADR-0012)

        MvcResult result = mockMvc.perform(get("/api/v1/events/" + event + "/availability"))
                .andExpect(status().isOk()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(seats.get(0).toString(), seats.get(1).toString());
        assertThat(body).doesNotContain(seats.get(2).toString(), seats.get(3).toString());
    }

    @Test
    void theListIsSortedSoItsEtagDoesNotDependOnTheDatabaseOrder() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 8);
        List<UUID> taken = seats.subList(0, 5);
        taken.forEach(seat -> sell(event, seat));

        mockMvc.perform(get("/api/v1/events/" + event + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailableSeatIds.length()").value(5))
                .andExpect(jsonPath("$.unavailableSeatIds", contains(sorted(taken).toArray(new String[0]))));
    }

    @Test
    void otherEventsDoNotLeakIn() throws Exception {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        List<UUID> seatsOne = eventWithSeats(one, 3);
        eventWithSeats(two, 3);
        sell(one, seatsOne.get(0));

        mockMvc.perform(get("/api/v1/events/" + two + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailableSeatIds").isEmpty());
    }

    // ---- conditional requests

    @Test
    void anUnchangedListAnswers304WithNoBody() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 4);
        sell(event, seats.get(0));
        String etag = mockMvc.perform(get("/api/v1/events/" + event + "/availability"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(get("/api/v1/events/" + event + "/availability").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(content().string(""));
    }

    @Test
    void aChangedListAnswers200WithANewEtag() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 4);
        String before = mockMvc.perform(get("/api/v1/events/" + event + "/availability"))
                .andReturn().getResponse().getHeader("ETag");
        sell(event, seats.get(1));

        MvcResult after = mockMvc.perform(get("/api/v1/events/" + event + "/availability")
                        .header("If-None-Match", before))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailableSeatIds.length()").value(1)).andReturn();

        assertThat(after.getResponse().getHeader("ETag")).isNotEqualTo(before);
    }

    // ---- who may ask, and what is not found

    @Test
    void anEventThatIsNotOnSaleIsAProblemJson404() throws Exception {
        mockMvc.perform(get("/api/v1/events/" + UUID.randomUUID() + "/availability"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aBadAuthorizationHeaderDoesNotMatterBecauseTheListIsPublic() throws Exception {
        UUID event = UUID.randomUUID();
        eventWithSeats(event, 2);

        mockMvc.perform(get("/api/v1/events/" + event + "/availability").header("Authorization", "Bearer garbage"))
                .andExpect(status().isOk());
    }

    @Test
    void anEventIdThatIsNotAUuidIsAPlainBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/events/not-a-uuid/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---- the table rules

    @Test
    void theDatabaseRefusesAHeldSeatWithoutADeadline() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 1);

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE event_seats SET state = 'HELD', booking_id = ? WHERE event_id = ? "
                        + "AND seat_id = ?", UUID.randomUUID(), event, seats.get(0)));
    }
}
