package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PaymentRecordTest {

    @Test
    void monthLabelUsesMonthFromIsoDate() {
        PaymentRecord payment = new PaymentRecord("2026-10-21", 50.00, "Monthly dues");

        assertEquals("October 2026", payment.monthLabel());
    }

    @Test
    void monthLabelSupportsYearMonthDates() {
        PaymentRecord payment = new PaymentRecord("2026-10", 50.00, "Monthly dues");

        assertEquals("October 2026", payment.monthLabel());
    }

    @Test
    void monthLabelFallsBackWhenDateIsUnavailable() {
        PaymentRecord payment = new PaymentRecord(null, 50.00, "Monthly dues");

        assertEquals("Unspecified month", payment.monthLabel());
    }

    @Test
    void monthLabelIsNotAddedToPersistedPaymentData() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new PaymentRecord("2026-10-21", 50.00, "Monthly dues")
        );

        assertFalse(json.contains("monthLabel"));
    }
}
