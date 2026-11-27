package com.ticketflow.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestCardScenarioTest {

    @Test
    void eachTestCardPlaysItsScenario() {
        assertThat(TestCardScenario.forCard("4242424242424242")).isEqualTo(TestCardScenario.SUCCESS);
        assertThat(TestCardScenario.forCard("4000000000000002")).isEqualTo(TestCardScenario.DECLINED);
        assertThat(TestCardScenario.forCard("4000000000000259")).isEqualTo(TestCardScenario.TIMEOUT_THEN_SUCCESS);
        assertThat(TestCardScenario.forCard("4000000000000077")).isEqualTo(TestCardScenario.DOUBLE_REPORT);
    }

    @Test
    void anyOtherNumberIsDeclinedAndNoNumberIsNoPaymentMethod() {
        assertThat(TestCardScenario.forCard("5555555555554444")).isEqualTo(TestCardScenario.DECLINED);
        assertThat(TestCardScenario.forCard("4242424242424243")).isEqualTo(TestCardScenario.DECLINED);
        assertThat(TestCardScenario.forCard(null)).isEqualTo(TestCardScenario.NO_PAYMENT_METHOD);
    }
}
