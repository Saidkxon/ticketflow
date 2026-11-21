package com.ticketflow.booking.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0012, part A, and its test obligation 5: provisioning builds the inventory once, whatever happens twice. */
class ProvisioningServiceTest extends AbstractPostgresIntegrationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private ProvisioningService service;

    @Autowired
    private JdbcTemplate jdbc;

    private CatalogLayout parse(LayoutFixtures layout) {
        return json.readValue(layout.json(), CatalogLayout.class);
    }

    private int seatCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_seats WHERE event_id = ?", Integer.class, eventId);
    }

    private int inventoryCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }

    @Test
    void everySeatIsCreatedAvailableWithItsLabelsAndItsPrice() {
        LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
        layout.layoutVersion = 4;

        ProvisioningService.Result result = service.provision(layout.eventId, 4, parse(layout));

        assertThat(result).isEqualTo(ProvisioningService.Result.PROVISIONED);
        Map<String, Object> inventory = jdbc.queryForMap(
                "SELECT layout_version, currency, total_seats FROM event_inventory WHERE event_id = ?", layout.eventId);
        assertThat(inventory).containsEntry("layout_version", 4L).containsEntry("currency", "UZS")
                .containsEntry("total_seats", LayoutFixtures.SEATS);
        assertThat(seatCount(layout.eventId)).isEqualTo(LayoutFixtures.SEATS);
        Map<String, Object> seat = jdbc.queryForMap(
                "SELECT section_name, row_label, seat_number, tier_name, price_minor, state FROM event_seats "
                        + "WHERE event_id = ? AND seat_id = ?", layout.eventId, layout.seatIds.get(1));
        assertThat(seat).containsEntry("section_name", "Stalls").containsEntry("row_label", "A")
                .containsEntry("seat_number", 2).containsEntry("tier_name", "VIP")
                .containsEntry("price_minor", LayoutFixtures.VIP_PRICE).containsEntry("state", "AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_seats WHERE event_id = ? AND state = 'AVAILABLE' "
                + "AND tier_name = 'Standard' AND price_minor = ?", Integer.class, layout.eventId,
                LayoutFixtures.STANDARD_PRICE)).isEqualTo(4);
    }

    @Test
    void doingItAgainChangesNothing() {
        LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
        service.provision(layout.eventId, 1, parse(layout));

        ProvisioningService.Result again = service.provision(layout.eventId, 1, parse(layout));

        assertThat(again).isEqualTo(ProvisioningService.Result.ALREADY_PROVISIONED);
        assertThat(seatCount(layout.eventId)).isEqualTo(LayoutFixtures.SEATS);
        assertThat(inventoryCount(layout.eventId)).isEqualTo(1);
    }

    @Test
    void severalCopiesAtTheSameTimeBuildTheInventoryExactlyOnce() throws Exception {
        LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
        int copies = 8;
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ProvisioningService.Result>> futures = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.provision(layout.eventId, 1, parse(layout));
            }));
        }
        start.countDown();

        int provisioned = 0;
        for (Future<ProvisioningService.Result> future : futures) {
            if (future.get() == ProvisioningService.Result.PROVISIONED) {
                provisioned++;
            }
        }
        pool.shutdown();

        assertThat(provisioned).isEqualTo(1);
        assertThat(inventoryCount(layout.eventId)).isEqualTo(1);
        assertThat(seatCount(layout.eventId)).isEqualTo(LayoutFixtures.SEATS);
    }

    @Test
    void aLayoutThatIsNotConsistentIsRefusedAndNothingIsWritten() {
        List<Consumer<LayoutFixtures>> spoilers = List.of(
                layout -> layout.layoutEventId = UUID.randomUUID(),
                layout -> layout.layoutVersion = 2,
                layout -> layout.currency = "uzs",
                layout -> layout.totalSeats = LayoutFixtures.SEATS + 1,
                layout -> layout.duplicateSeat = true,
                layout -> layout.unknownTier = true);

        for (Consumer<LayoutFixtures> spoil : spoilers) {
            LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
            spoil.accept(layout);

            assertThatThrownBy(() -> service.provision(layout.eventId, 1, parse(layout)))
                    .isInstanceOf(InvalidMessageException.class);
            assertThat(inventoryCount(layout.eventId)).isZero();
            assertThat(seatCount(layout.eventId)).isZero();
        }
    }
}
