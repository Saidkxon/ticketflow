package com.ticketflow.payment.provider;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A payment provider that plays the scenario of a test card (see {@link TestCardScenario}). It remembers every charge
 * in its own table, keyed by the idempotency key, so it behaves like a real provider: a repeated key never charges
 * twice, and a lookup tells the truth even after a timeout. When a real provider is added, this class and its table go.
 *
 * <p>For the timeout scenario the charge is recorded at once but stays hidden for {@code lateResultDelay}: the charge
 * call times out, lookups say "processing", and later the success shows. That is the situation rule R5 is about: the
 * money moved while Payment could not know.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    private final JdbcClient jdbc;
    private final ProviderProperties properties;

    SimulatedPaymentProvider(JdbcClient jdbc, ProviderProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    private record Row(TestCardScenario scenario, String reference, boolean visible) {
    }

    @Override
    public ChargeOutcome charge(ChargeRequest request) {
        double hiddenSeconds = request.scenario() == TestCardScenario.TIMEOUT_THEN_SUCCESS
                ? properties.lateResultDelay().toMillis() / 1000.0
                : 0.0;
        jdbc.sql("""
                        INSERT INTO sim_provider_charges (idempotency_key, scenario, reference, amount_minor, currency, visible_at)
                        VALUES (:key, :scenario, :reference, :amount, :currency,
                                clock_timestamp() + make_interval(secs => :hidden))
                        ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("key", request.idempotencyKey())
                .param("scenario", request.scenario().name())
                .param("reference", "sim_" + UUID.randomUUID())
                .param("amount", request.amountMinor())
                .param("currency", request.currency())
                .param("hidden", hiddenSeconds)
                .update();
        Row row = read(request.idempotencyKey());
        if (row == null) {
            throw new IllegalStateException("A recorded charge vanished");
        }
        if (!row.visible()) {
            throw new ProviderTimeoutException("the simulated provider did not answer in time");
        }
        return outcomeOf(row);
    }

    @Override
    public ChargeOutcome lookup(String idempotencyKey) {
        Row row = read(idempotencyKey);
        if (row == null) {
            return ChargeOutcome.notCharged();
        }
        return row.visible() ? outcomeOf(row) : ChargeOutcome.processing();
    }

    private Row read(String key) {
        return jdbc.sql("""
                        SELECT scenario, reference, visible_at <= clock_timestamp() AS visible
                        FROM sim_provider_charges WHERE idempotency_key = :key""")
                .param("key", key)
                .query((rs, n) -> new Row(TestCardScenario.valueOf(rs.getString("scenario")),
                        rs.getString("reference"), rs.getBoolean("visible")))
                .optional()
                .orElse(null);
    }

    private static ChargeOutcome outcomeOf(Row row) {
        return switch (row.scenario()) {
            case SUCCESS, TIMEOUT_THEN_SUCCESS -> ChargeOutcome.succeeded(row.reference(), 1);
            case DOUBLE_REPORT -> ChargeOutcome.succeeded(row.reference(), 2);
            case DECLINED -> ChargeOutcome.declined(ChargeOutcome.CARD_DECLINED);
            case NO_PAYMENT_METHOD -> ChargeOutcome.declined(ChargeOutcome.NO_PAYMENT_METHOD);
        };
    }
}
