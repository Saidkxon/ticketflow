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

    /** Refunds live in the same table as charges, under their own key, so the two never collide. */
    private static final String REFUND_PREFIX = "refund:";

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
        return record(request.idempotencyKey(), request.scenario(), request.amountMinor(), request.currency());
    }

    /**
     * Records the charge (or refund) under its key unless it exists, then answers from the record: a timeout while its
     * result is still hidden, otherwise the result of the scenario.
     */
    private ChargeOutcome record(String key, TestCardScenario scenario, long amountMinor, String currency) {
        double hiddenSeconds = scenario == TestCardScenario.TIMEOUT_THEN_SUCCESS
                ? properties.lateResultDelay().toMillis() / 1000.0
                : 0.0;
        jdbc.sql("""
                        INSERT INTO sim_provider_charges (idempotency_key, scenario, reference, amount_minor, currency, visible_at)
                        VALUES (:key, :scenario, :reference, :amount, :currency,
                                clock_timestamp() + make_interval(secs => :hidden))
                        ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("key", key)
                .param("scenario", scenario.name())
                .param("reference", "sim_" + UUID.randomUUID())
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("hidden", hiddenSeconds)
                .update();
        Row row = read(key);
        if (row == null) {
            throw new IllegalStateException("A recorded charge vanished");
        }
        if (!row.visible()) {
            throw new ProviderTimeoutException("the simulated provider did not answer in time");
        }
        return outcomeOf(row);
    }

    @Override
    public ChargeOutcome refund(RefundRequest request) {
        Row charge = read(request.idempotencyKey());
        if (charge == null || !charge.visible() || outcomeOf(charge).state() != ChargeState.SUCCEEDED) {
            return ChargeOutcome.declined(ChargeOutcome.NO_CHARGE_TO_REFUND);
        }
        return record(REFUND_PREFIX + request.idempotencyKey(), request.scenario(), request.amountMinor(),
                request.currency());
    }

    @Override
    public ChargeOutcome lookupRefund(String idempotencyKey) {
        return lookup(REFUND_PREFIX + idempotencyKey);
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
