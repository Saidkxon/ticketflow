package com.ticketflow.payment.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.payment.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-0006, test obligation 7: the same create request arriving many times at the same moment (a retry storm, a double
 * click) makes exactly one payment, and every caller is told about that one payment. The unique key in the database is
 * what guarantees it, not a check in the code.
 */
class PaymentAttemptConcurrencyTest extends AbstractPostgresIntegrationTest {

    private static final int CALLERS = 12;

    @Autowired
    private PaymentAttemptService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void manySimultaneousRequestsWithTheSameKeyCreateOnePayment() throws Exception {
        UUID booking = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        CountDownLatch ready = new CountDownLatch(CALLERS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<UUID>> results = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                Callable<UUID> call = () -> {
                    ready.countDown();
                    go.await();
                    return service.create(booking, 1, 99_000, "UZS").paymentId();
                };
                results.add(pool.submit(call));
            }
            ready.await();
            go.countDown();

            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> result : results) {
                ids.add(result.get());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from payments where booking_id = ?", Integer.class, booking))
                .isEqualTo(1);
    }
}
