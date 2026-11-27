package com.ticketflow.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.payment.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The simulated provider behaves like a real one: one charge per key, and a lookup that tells the truth (R5). */
class SimulatedPaymentProviderTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private SimulatedPaymentProvider provider;

    @Autowired
    private JdbcTemplate jdbc;

    private static String newKey() {
        return UUID.randomUUID() + ":1";
    }

    private static ChargeRequest request(String key, TestCardScenario scenario) {
        return new ChargeRequest(key, 99_000, "UZS", scenario);
    }

    private int chargesFor(String key) {
        return jdbc.queryForObject("select count(*) from sim_provider_charges where idempotency_key = ?",
                Integer.class, key);
    }

    @Test
    void theSuccessCardSucceedsAtOnce() {
        ChargeOutcome outcome = provider.charge(request(newKey(), TestCardScenario.SUCCESS));

        assertThat(outcome.state()).isEqualTo(ChargeState.SUCCEEDED);
        assertThat(outcome.reference()).startsWith("sim_");
        assertThat(outcome.deliveries()).isEqualTo(1);
    }

    @Test
    void theDeclinedCardAndMissingCardAreRefusedWithTheirOwnReasons() {
        ChargeOutcome declined = provider.charge(request(newKey(), TestCardScenario.DECLINED));
        ChargeOutcome none = provider.charge(request(newKey(), TestCardScenario.NO_PAYMENT_METHOD));

        assertThat(declined.state()).isEqualTo(ChargeState.DECLINED);
        assertThat(declined.reason()).isEqualTo("card_declined");
        assertThat(none.state()).isEqualTo(ChargeState.DECLINED);
        assertThat(none.reason()).isEqualTo("no_payment_method");
    }

    @Test
    void theDoubleReportCardReportsItsSuccessTwice() {
        ChargeOutcome outcome = provider.charge(request(newKey(), TestCardScenario.DOUBLE_REPORT));

        assertThat(outcome.state()).isEqualTo(ChargeState.SUCCEEDED);
        assertThat(outcome.deliveries()).isEqualTo(2);
    }

    @Test
    void aRepeatedChargeWithTheSameKeyChargesOnceAndReturnsTheSameResult() {
        String key = newKey();

        ChargeOutcome first = provider.charge(request(key, TestCardScenario.SUCCESS));
        ChargeOutcome second = provider.charge(request(key, TestCardScenario.SUCCESS));

        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(chargesFor(key)).isEqualTo(1);
    }

    @Test
    void theTimeoutCardTimesOutThenIsProcessingThenShowsItsSuccess() {
        String key = newKey();

        assertThatThrownBy(() -> provider.charge(request(key, TestCardScenario.TIMEOUT_THEN_SUCCESS)))
                .isInstanceOf(ProviderTimeoutException.class);
        // The charge exists although the caller never heard: asking again charges nothing more.
        assertThatThrownBy(() -> provider.charge(request(key, TestCardScenario.TIMEOUT_THEN_SUCCESS)))
                .isInstanceOf(ProviderTimeoutException.class);
        assertThat(chargesFor(key)).isEqualTo(1);
        assertThat(provider.lookup(key).state()).isEqualTo(ChargeState.PROCESSING);

        jdbc.update("update sim_provider_charges set visible_at = clock_timestamp() - interval '1 second' "
                + "where idempotency_key = ?", key);

        ChargeOutcome late = provider.lookup(key);
        assertThat(late.state()).isEqualTo(ChargeState.SUCCEEDED);
        assertThat(late.reference()).startsWith("sim_");
    }

    @Test
    void aLookupForAKeyThatWasNeverChargedConfirmsThatNothingWasTaken() {
        ChargeOutcome outcome = provider.lookup(newKey());

        assertThat(outcome.state()).isEqualTo(ChargeState.NOT_CHARGED);
        assertThat(outcome.reason()).isEqualTo("provider_no_charge");
    }
}
