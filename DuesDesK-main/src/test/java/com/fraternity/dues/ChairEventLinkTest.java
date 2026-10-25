package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Chair positions held by a member (or no one), and events assigned to a chair counting on its budget. */
class ChairEventLinkTest {
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
                  {"id":"m1","name":"Marcus Vance","phoneNumber":"555","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false},
                  {"id":"m2","name":"Grant Hayes","phoneNumber":"556","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false}],
                 "settings":{"ihqBillAmount":0}}""");
        return store();
    }

    private static PresaleSale ticket(String id, int quantity, double price) {
        return new PresaleSale(id, "2026-10-10", "", PresaleSale.TICKET, "", PresaleSale.MALE, quantity, price, null);
    }

    @Test
    void aChairCanBeHeldByAMemberOrByNoOne() throws Exception {
        Store store = freshStore();
        ChairBudget social = store.saveChairBudget("Social", 500, 0, "m1");
        assertEquals("m1", social.getChairMemberId());
        ChairBudget rush = store.saveChairBudget("Rush", 300, 0, "");
        assertNull(rush.getChairMemberId(), "No member is allowed");
        assertThrows(IllegalArgumentException.class, () -> store.saveChairBudget("Service", 100, 0, "nobody"));

        store.updateChairBudget(social.getId(), "Social", 500, 0, "", "m2");
        assertEquals("m2", store.getChairBudgets().stream().filter(b -> b.getId().equals(social.getId())).findFirst().orElseThrow().getChairMemberId());
        store.updateChairBudget(social.getId(), "Social", 500, 0, "", "");
        assertNull(store.getChairBudgets().stream().filter(b -> b.getId().equals(social.getId())).findFirst().orElseThrow().getChairMemberId());

        store.updateChairBudget(rush.getId(), "Rush", 300, 0, "", "m1");
        store.removeMember("m1");
        assertNull(store.getChairBudgets().stream().filter(b -> b.getId().equals(rush.getId())).findFirst().orElseThrow().getChairMemberId(),
                "a removed member no longer holds the chair");

        DashboardController controller = new DashboardController(store, new SmsService(store));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        controller.saveChairBudget("Brotherhood", 200, 0, "m2", flash);
        assertEquals("Chair budget saved, held by Grant Hayes.", flash.getFlashAttributes().get("toast"));
    }

    @Test
    void anEventAssignedToAChairCountsItsSalesAndExpensesOnThatChairLive() throws Exception {
        Store store = freshStore();
        ChairBudget social = store.saveChairBudget("Social", 500, 1000, "m1");
        store.addReimbursement(social.getId(), new ReimbursementRecord("r1", "2026-10-01", "Decorations", 40, null, null));
        PresaleCampaign formal = new PresaleCampaign(null, "Fall Formal", "2026-11-14", 25, 0, "");
        formal.setChairBudgetId(social.getId());
        formal = store.savePresaleCampaign(formal);
        String id = formal.getId();

        store.addPresaleSale(id, ticket("s1", 4, 25));
        store.addPresaleExpense(id, new PresaleExpense("e1", "2026-10-05", "Venue deposit", 60));
        ChairBudget refreshed = store.getChairBudgets().getFirst();
        assertEquals(1, refreshed.getLinkedEvents().size());
        assertEquals(100, refreshed.getEventRaised(), 0.001);
        assertEquals(60, refreshed.getEventSpent(), 0.001);
        assertEquals(100, refreshed.getSpent(), 0.001, "$40 charge + $60 event expense");
        assertEquals(400, refreshed.getRemaining(), 0.001);
        assertEquals(100, refreshed.getRaisedTotal(), 0.001);

        store.addPresaleSale(id, ticket("s2", 2, 25));
        assertEquals(150, store.getChairBudgets().getFirst().getRaisedTotal(), 0.001, "a new sale shows up on the chair right away");

        PresaleCampaign unassign = new PresaleCampaign(id, "Fall Formal", "2026-11-14", 25, 0, "");
        store.savePresaleCampaign(unassign);
        assertEquals(0, store.getChairBudgets().getFirst().getRaisedTotal(), 0.001, "unassigning takes the event off the chair");
        assertEquals(40, store.getChairBudgets().getFirst().getSpent(), 0.001);

        PresaleCampaign bad = new PresaleCampaign(id, "Fall Formal", "", 0, 0, "");
        bad.setChairBudgetId("missing");
        assertThrows(IllegalArgumentException.class, () -> store.savePresaleCampaign(bad));
    }

    @Test
    void theLinkIsSavedAndDeletingTheChairKeepsTheEvent() throws Exception {
        Store store = freshStore();
        ChairBudget social = store.saveChairBudget("Social", 500, 0, "");
        PresaleCampaign formal = new PresaleCampaign(null, "Fall Formal", "", 25, 0, "");
        formal.setChairBudgetId(social.getId());
        String id = store.savePresaleCampaign(formal).getId();
        store.addPresaleSale(id, ticket("s1", 2, 25));

        Store reloaded = store();
        assertEquals(social.getId(), reloaded.findPresaleCampaign(id).orElseThrow().getChairBudgetId());
        assertEquals(50, reloaded.getChairBudgets().getFirst().getRaisedTotal(), 0.001);
        assertTrue(Files.readString(file).contains("\"chairBudgetId\""));
        assertTrue(!Files.readString(file).contains("linkedEvents"), "the live event totals are never written to the save");

        assertTrue(reloaded.deleteChairBudget(social.getId()));
        assertNull(reloaded.findPresaleCampaign(id).orElseThrow().getChairBudgetId());
        assertEquals(1, reloaded.findPresaleCampaign(id).orElseThrow().getSales().size(), "the event and its sales stay");
    }

    @Test
    void startingChairBudgetsFreshClosesOutTheirEvents() throws Exception {
        Store store = freshStore();
        ChairBudget social = store.saveChairBudget("Social", 500, 0, "");
        PresaleCampaign formal = new PresaleCampaign(null, "Fall Formal", "", 25, 0, "");
        formal.setChairBudgetId(social.getId());
        String id = store.savePresaleCampaign(formal).getId();
        store.addPresaleSale(id, ticket("s1", 2, 25));
        store.addPresaleExpense(id, new PresaleExpense("e1", "2026-10-05", "DJ", 30));

        store.startNewSemester(new Store.RolloverRequest("Spring 2027", List.of(), false, false, true), LocalDate.of(2026, 12, 20));
        ChairBudgetPeriod period = store.getChairBudgetHistory().getFirst();
        assertEquals(50, period.getRaisedTotal(), 0.001, "the closed term keeps the event's sales");
        assertEquals(30, period.getSpent(), 0.001);
        assertEquals(0, store.getChairBudgets().getFirst().getRaisedTotal(), 0.001, "the new term starts at zero");
        assertNull(store.findPresaleCampaign(id).orElseThrow().getChairBudgetId());
    }
}
