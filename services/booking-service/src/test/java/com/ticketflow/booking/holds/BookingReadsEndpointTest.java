package com.ticketflow.booking.holds;

import static com.ticketflow.booking.holds.HoldFixtures.eventWithSeats;
import static com.ticketflow.booking.holds.HoldFixtures.stateOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
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

/**
 * {@code GET /api/v1/bookings}, {@code GET /api/v1/bookings/{id}} and {@code POST /api/v1/bookings/{id}/release} with
 * the real filters, the error format and a real database.
 */
class BookingReadsEndpointTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private BearerTokenFilter bearerTokenFilter;

    @Autowired
    private HoldService holds;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, bearerTokenFilter).build();
    }

    // ---- helpers

    private BookingResponse hold(UUID user, UUID event, List<UUID> seats) {
        return holds.hold(user, UUID.randomUUID(), event, seats);
    }

    private ResultActions read(String path, UUID user) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + tokenFor(user, "CUSTOMER")));
    }

    private ResultActions release(UUID booking, UUID user) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings/" + booking + "/release")
                .header("Authorization", "Bearer " + tokenFor(user, "CUSTOMER")));
    }

    // ---- read one

    @Test
    void aCustomerReadsTheirOwnBookingWithItsSeats() throws Exception {
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 3);
        BookingResponse booking = hold(user, event, List.of(seats.get(0), seats.get(1)));

        read("/api/v1/bookings/" + booking.id(), user)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").value(booking.id().toString()))
                .andExpect(jsonPath("$.eventId").value(event.toString()))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.totalMinor").value(10_000))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.serverTime").exists())
                .andExpect(jsonPath("$.seats", hasSize(2)));
    }

    @Test
    void aBookingOfSomebodyElseLooksLikeOneThatDoesNotExist() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 1);
        BookingResponse booking = hold(owner, event, seats);

        read("/api/v1/bookings/" + booking.id(), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        read("/api/v1/bookings/" + UUID.randomUUID(), owner)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anIdThatIsNotAUuidIsRefused() throws Exception {
        read("/api/v1/bookings/not-a-uuid", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("bookingId"));
    }

    @Test
    void withoutATokenNothingIsAnswered() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bookings/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/bookings/" + UUID.randomUUID() + "/release"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aHoldWhoseDeadlineHasPassedIsShownAsExpiredBeforeTheSweeperRuns() throws Exception {
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 1);
        BookingResponse booking = hold(user, event, seats);
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 minute' WHERE booking_id = ?", booking.id());

        read("/api/v1/bookings/" + booking.id(), user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        // The stored status is still HELD: the sweeper has not run, and reading changes nothing.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM bookings WHERE booking_id = ?", String.class, booking.id())).isEqualTo("HELD");
    }

    // ---- list

    @Test
    void theListHasOnlyMyBookingsNewestFirstAndIsPaged() throws Exception {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        BookingResponse[] mine = new BookingResponse[3];
        for (int i = 0; i < 3; i++) {
            UUID event = UUID.randomUUID();
            List<UUID> seats = eventWithSeats(jdbc, event, 2);
            mine[i] = hold(me, event, List.of(seats.get(0)));
            hold(other, event, List.of(seats.get(1)));
        }

        read("/api/v1/bookings?size=2", me)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].id", contains(mine[2].id().toString(), mine[1].id().toString())))
                .andExpect(jsonPath("$.items[0].seats", hasSize(1)));
        read("/api/v1/bookings?size=2&page=1", me)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(mine[0].id().toString())));
    }

    @Test
    void aCustomerWithoutBookingsGetsAnEmptyList() throws Exception {
        read("/api/v1/bookings", UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void thePagingIsChecked() throws Exception {
        UUID user = UUID.randomUUID();
        read("/api/v1/bookings?size=0", user)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        read("/api/v1/bookings?size=51", user).andExpect(status().isBadRequest());
        read("/api/v1/bookings?page=-1", user)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    // ---- release

    @Test
    void releasingAHoldFreesTheSeatsAtOnce() throws Exception {
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 2);
        BookingResponse booking = hold(user, event, seats);

        release(booking.id(), user)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").value(booking.id().toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.seats", hasSize(2)));

        for (UUID seat : seats) {
            org.assertj.core.api.Assertions.assertThat(stateOf(jdbc, event, seat)).isEqualTo("AVAILABLE");
        }
        mockMvc.perform(get("/api/v1/events/" + event + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailableSeatIds", empty()));
        read("/api/v1/bookings/" + booking.id(), user).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void releasingTwiceAnswersTheSameWayAndAfterwardsTheCustomerCanHoldAgain() throws Exception {
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 2);
        BookingResponse first = hold(user, event, seats);

        release(first.id(), user).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        release(first.id(), user).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        // The released booking does not count as an active hold any more.
        BookingResponse second = hold(user, event, seats);
        org.assertj.core.api.Assertions.assertThat(second.id()).isNotEqualTo(first.id());
        release(first.id(), user).andExpect(status().isOk());
        // Releasing the old booking again must not take the new holder's seats away.
        org.assertj.core.api.Assertions.assertThat(stateOf(jdbc, event, seats.get(0))).isEqualTo("HELD");
    }

    @Test
    void aCustomerCannotReleaseSomebodyElsesHold() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 1);
        BookingResponse booking = hold(owner, event, seats);

        release(booking.id(), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        release(UUID.randomUUID(), owner).andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(stateOf(jdbc, event, seats.get(0))).isEqualTo("HELD");
    }

    @Test
    void aHoldThatHasRunOutCannotBeReleased() throws Exception {
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 1);
        BookingResponse booking = hold(user, event, seats);
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 minute' WHERE booking_id = ?", booking.id());
        jdbc.update("UPDATE event_seats SET held_until = now() - interval '1 minute' WHERE booking_id = ?",
                booking.id());

        release(booking.id(), user)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_ACTIVE"));
    }

    @Test
    void anIdThatIsNotAUuidCannotBeReleased() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/not-a-uuid/release")
                        .header("Authorization", "Bearer " + tokenFor(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bookingId"));
    }
}
