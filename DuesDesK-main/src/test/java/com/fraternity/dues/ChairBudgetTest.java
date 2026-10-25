package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Chair budgets: the optional money-raised goal, and charges no longer crediting a member's dues. */
class ChairBudgetTest {
    @TempDir
    Path tempDir;

    private Store store(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, """
                {"members":[{"id":"m1","name":"Marcus Vance","phoneNumber":"555","totalOwed":100,"pastDueBalance":0,
                  "paidThisMonth":0,"status":"Payment Plan","lastTextedDate":"Never","muteReminders":false}],
                 "settings":{"ihqBillAmount":0}}""");
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }

    @Test
    void raiseGoalIsOptionalCentRoundedAndUpdatedByResavingTheChair() throws Exception {
        Store store = store("goal.json");

        ChairBudget none = store.saveChairBudget("Rush", 500);
        assertEquals(0, none.getRaiseGoal());
        assertEquals(0, none.getRaiseRemaining());

        ChairBudget withGoal = store.saveChairBudget("rush", 500, 1500.005);
        assertEquals(none.getId(), withGoal.getId(), "saving the same chair name updates it");
        assertEquals(1500.01, withGoal.getRaiseGoal());
        assertEquals(1, store.getChairBudgets().size());

        assertThrows(IllegalArgumentException.class, () -> store.saveChairBudget("Rush", 500, -1));
        assertThrows(IllegalArgumentException.class, () -> store.saveChairBudget("Rush", 500, Double.NaN));
        assertEquals(1500.01, store.getChairBudgets().getFirst().getRaiseGoal(), "a rejected save changes nothing");
    }

    @Test
    void moneyRaisedAddsUpTowardTheGoalAndInvalidEntriesAreRefused() throws Exception {
        Store store = store("raised.json");
        ChairBudget budget = store.saveChairBudget("Philanthropy", 200, 1000);

        assertTrue(store.addRaised(budget.getId(), new RaisedRecord("r1", "2026-10-05", "Car wash", 325.5)));
        assertTrue(store.addRaised(budget.getId(), new RaisedRecord("r2", "2026-10-12", "Alumni gift", 100)));
        assertFalse(store.addRaised(budget.getId(), new RaisedRecord("r3", "bad-date", "Nope", 10)));
        assertFalse(store.addRaised(budget.getId(), new RaisedRecord("r4", "2026-10-13", " ", 10)));
        assertFalse(store.addRaised(budget.getId(), new RaisedRecord("r5", "2026-10-13", "Zero", 0)));
        assertFalse(store.addRaised("missing", new RaisedRecord("r6", "2026-10-13", "Lost", 10)));

        ChairBudget saved = store.getChairBudgets().getFirst();
        assertEquals(2, saved.getRaised().size());
        assertEquals(425.5, saved.getRaisedTotal());
        assertEquals(574.5, saved.getRaiseRemaining());
        assertEquals(0, saved.getSpent(), "money raised is not a charge against the budget");

        assertTrue(store.addRaised(budget.getId(), new RaisedRecord("r7", "2026-10-20", "Big donor", 700)));
        assertEquals(-125.5, store.getChairBudgets().getFirst().getRaiseRemaining(), "negative once the goal is passed");
    }

    @Test
    void goalAndRaisedMoneySurviveReloadAndExport() throws Exception {
        Store source = store("persist.json");
        ChairBudget budget = source.saveChairBudget("Social", 300, 800);
        source.addRaised(budget.getId(), new RaisedRecord("r1", "2026-10-05", "Bake sale", 120));

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", tempDir.resolve("persist.json").toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        assertEquals(800, reloaded.getChairBudgets().getFirst().getRaiseGoal());
        assertEquals(120, reloaded.getChairBudgets().getFirst().getRaisedTotal());

        Store destination = store("persist-destination.json");
        destination.importData(source.exportData());
        assertEquals(800, destination.getChairBudgets().getFirst().getRaiseGoal());
        assertEquals("Bake sale", destination.getChairBudgets().getFirst().getRaised().getFirst().getDescription());
    }

    @Test
    void chargesNoLongerCreditDuesAndOlderBudgetsWithoutAGoalStillLoad() throws Exception {
        Store store = store("nocredit.json");
        ChairBudget budget = store.saveChairBudget("Rush", 500);

        assertFalse(store.addReimbursement(budget.getId(),
                new ReimbursementRecord("c1", "2026-10-01", "Credited", 20, null, "m1")),
                "a charge that asks for a dues credit is refused");
        assertTrue(store.addReimbursement(budget.getId(),
                new ReimbursementRecord("c2", "2026-10-01", "Shirts", 20, null, null)));
        Member member = store.findMemberById("m1").orElseThrow();
        assertEquals(100, member.getTotalOwed());
        assertTrue(member.getCredits().isEmpty());

        // A save written before raise goals (and before credits were removed) still loads, credit and all.
        Path legacy = tempDir.resolve("legacy.json");
        Files.writeString(legacy, """
                {"members":[],"chairBudgets":[{"id":"b1","chairName":"Old","chairMemberId":"m1","budgetCap":400,
                  "reimbursements":[{"id":"x","date":"2026-09-01","description":"Banner","amount":50,"creditedMemberId":"m1"}]}],
                 "settings":{"ihqBillAmount":0}}""");
        Store old = new Store();
        ReflectionTestUtils.setField(old, "DATA_FILE", legacy.toString());
        ReflectionTestUtils.setField(old, "smsDailyCap", 100);
        old.init();
        ChairBudget loaded = old.getChairBudgets().getFirst();
        assertEquals(0, loaded.getRaiseGoal());
        assertTrue(loaded.getRaised().isEmpty());
        assertEquals(50, loaded.getSpent());
        assertEquals(List.of("m1"), loaded.getReimbursements().stream().map(ReimbursementRecord::getCreditedMemberId).toList());
        assertTrue(loaded.getAccentColor().matches("#[0-9A-F]{6}"), "legacy chairs receive a card color");
    }

    @Test
    void editsPreserveHistoryAndAccentColorsStayUniqueAndPersistent() throws Exception {
        Store store = store("identity.json");
        ChairBudget rush = store.saveChairBudget("Rush", 500, 1000);
        ChairBudget service = store.saveChairBudget("Community Service", 300);
        assertNotEquals(rush.getAccentColor(), service.getAccentColor());
        assertTrue(rush.getAccentColor().matches("#[0-9A-F]{6}"));
        assertEquals("R", rush.getInitials());
        assertEquals("CS", service.getInitials());
        assertTrue(store.addReimbursement(rush.getId(),
                new ReimbursementRecord("c1", "2026-10-01", "Shirts", 20, null, null)));
        assertTrue(store.addRaised(rush.getId(), new RaisedRecord("r1", "2026-10-02", "Car wash", 125)));

        ChairBudget updated = store.updateChairBudget(rush.getId(), "Recruitment", 650, 1200, "#0f766e");
        assertEquals(rush.getId(), updated.getId());
        assertEquals("Recruitment", updated.getChairName());
        assertEquals(650, updated.getBudgetCap());
        assertEquals(1200, updated.getRaiseGoal());
        assertEquals("#0F766E", updated.getAccentColor());
        assertEquals(1, updated.getReimbursements().size());
        assertEquals(1, updated.getRaised().size());
        assertEquals(125, updated.getRaisedTotal());
        assertThrows(IllegalArgumentException.class, () ->
                store.updateChairBudget(rush.getId(), "community service", 650, 1200, "#2563EB"));
        assertThrows(IllegalArgumentException.class, () ->
                store.updateChairBudget(rush.getId(), "Recruitment", 650, 1200, "not-a-color"));
        assertEquals("Recruitment", store.getChairBudgets().stream()
                .filter(item -> item.getId().equals(rush.getId())).findFirst().orElseThrow().getChairName());

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", tempDir.resolve("identity.json").toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        ChairBudget saved = reloaded.getChairBudgets().stream()
                .filter(item -> item.getId().equals(rush.getId())).findFirst().orElseThrow();
        assertEquals("Recruitment", saved.getChairName());
        assertEquals("#0F766E", saved.getAccentColor());
        assertEquals(1, saved.getReimbursements().size());
        assertEquals(1, saved.getRaised().size());
    }

    @Test
    void deletingAChairRemovesItsActivityAndReceiptButLeavesOtherChairs() throws Exception {
        Store store = store("delete.json");
        ChairBudget removed = store.saveChairBudget("Remove Me", 500, 500);
        ChairBudget kept = store.saveChairBudget("Keep Me", 300);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(8, 8,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream imageBytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", imageBytes);
        String receiptFile = store.saveReceipt(new org.springframework.mock.web.MockMultipartFile(
                "receipt", "receipt.png", "image/png", imageBytes.toByteArray()));
        assertTrue(store.addReimbursement(removed.getId(),
                new ReimbursementRecord("c1", "2026-10-01", "Supplies", 20, receiptFile, null)));
        assertTrue(store.addRaised(removed.getId(), new RaisedRecord("r1", "2026-10-02", "Donation", 50)));

        assertTrue(store.deleteChairBudget(removed.getId()));
        assertFalse(store.deleteChairBudget(removed.getId()));
        assertEquals(List.of(kept.getId()), store.getChairBudgets().stream().map(ChairBudget::getId).toList());
        assertThrows(java.nio.file.NoSuchFileException.class, () -> store.readReceipt(receiptFile));
        String exported = new String(store.exportData(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(exported.contains(receiptFile), "deleted receipt data must not remain in backups");

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", tempDir.resolve("delete.json").toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        assertEquals(List.of(kept.getId()), reloaded.getChairBudgets().stream().map(ChairBudget::getId).toList());
    }
}
