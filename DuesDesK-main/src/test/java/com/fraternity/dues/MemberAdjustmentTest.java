package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Individual fines and discounts from the Dues page, and the saved charges that refill the form. */
class MemberAdjustmentTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @TempDir
    Path tempDir;

    private Path file;

    private Store store() throws Exception {
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }

    private Store freshStore() throws Exception {
        file = tempDir.resolve("store.json");
        Files.writeString(file, """
                {"members":[
                {"id":"m1","name":"Marcus Vance","phoneNumber":"5550001","totalOwed":100,"pastDueBalance":40,"paidThisMonth":0,"status":"Delinquent","lastTextedDate":"Never","muteReminders":false,
                 "charges":[{"id":"c1","date":"2026-09-01","description":"Fall dues","amount":100,"pastDueAmount":40,"dueDate":"2026-09-15","paidAmount":0}]},
                {"id":"m2","name":"Grant Hayes","phoneNumber":"5550002","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false}
                ],"settings":{"ihqBillAmount":0}}""");
        return store();
    }

    @Test
    void aNamedFineIsAddedAsAChargeDueTodayOrOnTheChosenDate() throws Exception {
        Store store = freshStore();
        Store.AdjustmentResult result = store.addMemberAdjustment("m2", ChargePreset.FINE, "  Missed chapter ", 25, "", TODAY);
        assertTrue(result.applied());
        Member grant = store.findMemberById("m2").orElseThrow();
        assertEquals(25, grant.getTotalOwed(), 0.001);
        assertEquals("Missed chapter", grant.getCharges().getLast().getDescription());
        assertEquals(TODAY.toString(), grant.getCharges().getLast().getDueDate());
        assertEquals("Payment Plan", grant.getStatus());

        store.addMemberAdjustment("m2", ChargePreset.FINE, "Late fine", 10, "2026-10-01", TODAY);
        assertEquals(10, grant.getPastDueBalance(), 0.001, "a fine dated in the past is past due");
        assertEquals("Delinquent", grant.getStatus());
        assertTrue(grant.getPaymentHistory() == null || grant.getPaymentHistory().isEmpty(), "a fine is not a payment");
    }

    @Test
    void aDiscountLowersTheBalanceAsACreditNotAPaymentAndStopsAtZero() throws Exception {
        Store store = freshStore();
        Store.AdjustmentResult result = store.addMemberAdjustment("m1", ChargePreset.DISCOUNT, "Scholarship", 30, "", TODAY);
        assertTrue(result.applied());
        Member marcus = store.findMemberById("m1").orElseThrow();
        assertEquals(70, marcus.getTotalOwed(), 0.001);
        assertEquals(10, marcus.getPastDueBalance(), 0.001);
        assertEquals(30, marcus.getCharges().getFirst().getPaidAmount(), 0.001, "the earliest charge is settled first");
        assertEquals("Scholarship", marcus.getCredits().getLast().getDescription());
        assertTrue(marcus.getPaymentHistory() == null || marcus.getPaymentHistory().isEmpty(), "never counted as money received");
        assertEquals(0, marcus.getPaidThisMonth(), 0.001);

        Store.AdjustmentResult capped = store.addMemberAdjustment("m1", ChargePreset.DISCOUNT, "Officer discount", 500, "", TODAY);
        assertEquals(70, capped.amount(), 0.001);
        assertTrue(capped.message().contains("capped"));
        assertEquals(0, marcus.getTotalOwed(), 0.001);
        assertEquals("Paid in Full", marcus.getStatus());

        assertFalse(store.addMemberAdjustment("m2", ChargePreset.DISCOUNT, "Discount", 5, "", TODAY).applied(), "nothing owed, nothing to discount");
        assertFalse(store.addMemberAdjustment("m1", ChargePreset.FINE, " ", 5, "", TODAY).applied(), "a name is required");
        assertFalse(store.addMemberAdjustment("m1", ChargePreset.FINE, "Fine", 0, "", TODAY).applied());
        assertFalse(store.addMemberAdjustment("nobody", ChargePreset.FINE, "Fine", 5, "", TODAY).applied());

        Member reloaded = store().findMemberById("m1").orElseThrow();
        assertEquals(2, reloaded.getCredits().size(), "discounts are saved");
    }

    @Test
    void savedChargesAreKeptUpdatedByNameAndRemovable() throws Exception {
        Store store = freshStore();
        assertTrue(store.saveChargePreset("Missed chapter", 25, ChargePreset.FINE));
        assertTrue(store.saveChargePreset("missed chapter", 30, ChargePreset.FINE), "same name updates the amount");
        assertTrue(store.saveChargePreset("Missed chapter", 5, ChargePreset.DISCOUNT), "a discount with that name is separate");
        assertFalse(store.saveChargePreset("", 5, ChargePreset.FINE));
        assertEquals(2, store.getChargePresets().size());
        assertEquals(30, store.getChargePresets().getFirst().getAmount(), 0.001);

        Store reloaded = store();
        assertEquals(2, reloaded.getChargePresets().size(), "presets survive a restart");
        assertTrue(reloaded.getChargePresets().get(1).isDiscount());
        assertTrue(reloaded.removeChargePreset(reloaded.getChargePresets().getFirst().getId()));
        assertFalse(reloaded.removeChargePreset("missing"));
        assertEquals(1, store().getChargePresets().size());
    }
}
