package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Event Pre-Sales: campaigns, sales and expenses, live profitability, and the seller leaderboard. */
class PresaleTest {
    @TempDir
    Path tempDir;

    private Store store(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, """
                {"members":[
                  {"id":"m1","name":"Marcus Vance","phoneNumber":"555","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false},
                  {"id":"m2","name":"Grant Hayes","phoneNumber":"555","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false},
                  {"id":"m3","name":"Cole Henderson","phoneNumber":"555","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false}],
                 "settings":{"ihqBillAmount":0}}""");
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }

    private static PresaleSale sale(String id, String item, int quantity, double price, String seller) {
        boolean admission = PresaleSale.TICKET.equals(item) || PresaleSale.WRISTBAND.equals(item);
        return new PresaleSale(id, "2026-10-10", "Buyer", item, "", admission ? PresaleSale.MALE : "", quantity, price, seller);
    }

    private static PresaleSale guestSale(String id, String item, String guestType, int quantity, double price) {
        return new PresaleSale(id, "2026-10-10", "", item, "", guestType, quantity, price, null);
    }

    @Test
    void campaignTotalsGiveGrossExpensesNetAndMargin() throws Exception {
        Store store = store("totals.json");
        PresaleCampaign campaign = store.savePresaleCampaign(new PresaleCampaign(null, " Fall Formal ", "2026-11-14", 25, 2000, "Free ticket"));
        assertEquals("Fall Formal", campaign.getName());

        assertTrue(store.addPresaleSale(campaign.getId(), sale("s1", PresaleSale.TICKET, 4, 25, "m1")));
        assertTrue(store.addPresaleSale(campaign.getId(), sale("s2", PresaleSale.MERCH, 2, 15.5, null)));
        assertTrue(store.addPresaleExpense(campaign.getId(), new PresaleExpense("e1", "2026-10-11", "Venue deposit", 80)));

        PresaleCampaign saved = store.findPresaleCampaign(campaign.getId()).orElseThrow();
        assertEquals(131, saved.getGrossRevenue());
        assertEquals(80, saved.getExpenseTotal());
        assertEquals(51, saved.getNetProfit());
        assertEquals(51.0 / 131 * 100, saved.getMarginPercent(), 1e-9);
        assertEquals(4, saved.getTicketsSold());
        assertEquals(2, saved.getMerchSold());
        assertEquals(31, saved.getUncreditedRevenue());

        PresaleCampaign empty = store.savePresaleCampaign(new PresaleCampaign(null, "Mixer", "", 10, 0, ""));
        assertEquals(0, empty.getMarginPercent(), "no margin before anything sells");
    }

    @Test
    void leaderboardRanksByRevenueThenTicketsAndTiesShareARank() {
        PresaleCampaign campaign = new PresaleCampaign("c1", "Formal", "", 20, 0, "");
        campaign.getSales().addAll(List.of(
                sale("a", PresaleSale.TICKET, 3, 20, "m1"),   // Marcus $60, 3 tickets
                sale("b", PresaleSale.TICKET, 2, 20, "m2"),   // Grant  $40 ...
                sale("c", PresaleSale.MERCH, 1, 20, "m2"),    // ... + $20 merch = $60, 2 tickets
                sale("d", PresaleSale.TICKET, 1, 20, "m3"),   // Cole   $20
                sale("e", PresaleSale.TICKET, 5, 20, "gone"), // a member who left: $100
                sale("f", PresaleSale.TICKET, 9, 20, null))); // door sales: not on the board

        List<PresaleCampaign.LeaderboardRow> board = campaign.leaderboard(Map.of("m1", "Marcus Vance", "m2", "Grant Hayes", "m3", "Cole Henderson"));

        assertEquals(List.of("Removed member", "Marcus Vance", "Grant Hayes", "Cole Henderson"),
                board.stream().map(PresaleCampaign.LeaderboardRow::name).toList());
        assertEquals(List.of(1, 2, 2, 4), board.stream().map(PresaleCampaign.LeaderboardRow::rank).toList(),
                "Marcus and Grant tie on $60 (Marcus listed first on tickets) and Cole is fourth, not third");
        assertEquals(2, board.get(2).sales());
        assertEquals(2, board.get(2).admissions());
    }

    @Test
    void invalidCampaignsSalesAndExpensesAreRefused() throws Exception {
        Store store = store("invalid.json");
        assertThrows(IllegalArgumentException.class, () -> store.savePresaleCampaign(new PresaleCampaign(null, " ", "", 10, 0, "")));
        assertThrows(IllegalArgumentException.class, () -> store.savePresaleCampaign(new PresaleCampaign(null, "X", "not-a-date", 10, 0, "")));
        assertThrows(IllegalArgumentException.class, () -> store.savePresaleCampaign(new PresaleCampaign(null, "X", "", -1, 0, "")));
        assertThrows(IllegalArgumentException.class, () -> store.savePresaleCampaign(new PresaleCampaign(null, "X", "", 10, 0, "p".repeat(201))));
        assertTrue(store.getPresaleCampaigns().isEmpty());

        PresaleCampaign campaign = store.savePresaleCampaign(new PresaleCampaign(null, "Formal", "", 20, 0, ""));
        String id = campaign.getId();
        assertFalse(store.addPresaleSale(id, sale("s1", PresaleSale.TICKET, 0, 20, null)), "quantity must be at least 1");
        assertFalse(store.addPresaleSale(id, sale("s2", PresaleSale.TICKET, 1, -5, null)), "no negative prices");
        assertFalse(store.addPresaleSale(id, sale("s3", "Raffle", 1, 5, null)), "only tickets or merch");
        assertFalse(store.addPresaleSale(id, sale("s4", PresaleSale.TICKET, 1, 20, "not-a-member")), "seller must be on the roster");
        assertFalse(store.addPresaleSale("missing", sale("s5", PresaleSale.TICKET, 1, 20, null)));
        assertFalse(store.addPresaleSale(id, new PresaleSale("s6", "bad", "", PresaleSale.TICKET, "", PresaleSale.MALE, 1, 20, null)));
        assertFalse(store.addPresaleExpense(id, new PresaleExpense("e1", "2026-10-10", " ", 10)));
        assertFalse(store.addPresaleExpense(id, new PresaleExpense("e2", "2026-10-10", "DJ", 0)));
        assertTrue(store.findPresaleCampaign(id).orElseThrow().getSales().isEmpty());
        assertTrue(store.findPresaleCampaign(id).orElseThrow().getExpenses().isEmpty());
    }

    @Test
    void editingDetailsKeepsSalesAndRemovingEntriesUpdatesTotals() throws Exception {
        Store store = store("edit.json");
        PresaleCampaign campaign = store.savePresaleCampaign(new PresaleCampaign(null, "Formal", "", 20, 0, ""));
        store.addPresaleSale(campaign.getId(), sale("s1", PresaleSale.TICKET, 2, 20, "m1"));
        store.addPresaleSale(campaign.getId(), sale("s2", PresaleSale.TICKET, 1, 20, "m2"));
        store.addPresaleExpense(campaign.getId(), new PresaleExpense("e1", "2026-10-10", "DJ", 30));

        store.savePresaleCampaign(new PresaleCampaign(campaign.getId(), "Fall Formal", "2026-11-14", 25, 500, "Free ticket"));
        PresaleCampaign edited = store.findPresaleCampaign(campaign.getId()).orElseThrow();
        assertEquals("Fall Formal", edited.getName());
        assertEquals(25, edited.getTicketPrice());
        assertEquals("Free ticket", edited.getPrize());
        assertEquals(2, edited.getSales().size(), "editing details never touches logged sales");
        assertEquals(1, store.getPresaleCampaigns().size());

        assertTrue(store.removePresaleSale(campaign.getId(), "s1"));
        assertFalse(store.removePresaleSale(campaign.getId(), "s1"));
        assertTrue(store.removePresaleExpense(campaign.getId(), "e1"));
        PresaleCampaign after = store.findPresaleCampaign(campaign.getId()).orElseThrow();
        assertEquals(20, after.getGrossRevenue());
        assertEquals(0, after.getExpenseTotal());
    }

    @Test
    void ticketsAndWristbandsAreSplitByMaleAndFemale() throws Exception {
        Store store = store("audience.json");
        PresaleCampaign campaign = store.savePresaleCampaign(new PresaleCampaign(null, "Formal", "", 25, 0, ""));
        String id = campaign.getId();

        assertTrue(store.addPresaleSale(id, guestSale("t1", PresaleSale.TICKET, PresaleSale.MALE, 3, 25)));
        assertTrue(store.addPresaleSale(id, guestSale("t2", PresaleSale.TICKET, PresaleSale.FEMALE, 5, 25)));
        assertTrue(store.addPresaleSale(id, guestSale("w1", PresaleSale.WRISTBAND, PresaleSale.FEMALE, 2, 5)));
        assertTrue(store.addPresaleSale(id, guestSale("w2", PresaleSale.WRISTBAND, PresaleSale.MALE, 4, 5.5)));
        // A save written before male/female existed has a ticket with no guest type; it is counted apart.
        store.findPresaleCampaign(id).orElseThrow().getSales().add(guestSale("old", PresaleSale.TICKET, "", 2, 25));

        PresaleCampaign saved = store.findPresaleCampaign(id).orElseThrow();
        PresaleCampaign.Audience tickets = saved.audience(PresaleSale.TICKET);
        assertEquals(3, tickets.male());
        assertEquals(5, tickets.female());
        assertEquals(2, tickets.unspecified());
        assertEquals(75, tickets.maleRevenue());
        assertEquals(125, tickets.femaleRevenue());
        assertEquals("Female", tickets.leader());
        assertEquals(37.5, tickets.malePercent(), 1e-9);
        assertEquals(62.5, tickets.femalePercent(), 1e-9);

        PresaleCampaign.Audience wristbands = saved.audience(PresaleSale.WRISTBAND);
        assertEquals(4, wristbands.male());
        assertEquals(2, wristbands.female());
        assertEquals(22, wristbands.maleRevenue());
        assertEquals("Male", wristbands.leader());

        assertEquals(10, saved.getTicketsSold());
        assertEquals(6, saved.getWristbandsSold());
        assertEquals("", new PresaleCampaign("x", "Even", "", 1, 0, "").audience(PresaleSale.TICKET).leader());
    }

    @Test
    void ticketsAndWristbandsNeedAGuestTypeButOtherItemsDoNot() throws Exception {
        Store store = store("guest.json");
        String id = store.savePresaleCampaign(new PresaleCampaign(null, "Formal", "", 25, 0, "")).getId();

        assertFalse(store.addPresaleSale(id, guestSale("a", PresaleSale.TICKET, "", 1, 25)), "ticket without male/female");
        assertFalse(store.addPresaleSale(id, guestSale("b", PresaleSale.WRISTBAND, "Other", 1, 5)), "only male or female");
        assertTrue(store.addPresaleSale(id, guestSale("c", PresaleSale.WRISTBAND, PresaleSale.FEMALE, 1, 5)));
        PresaleSale merch = new PresaleSale("d", "2026-10-10", "", PresaleSale.MERCH, "Hoodie", PresaleSale.FEMALE, 1, 40, null);
        assertTrue(store.addPresaleSale(id, merch));
        assertEquals("", merch.getGuestType(), "male/female is dropped for items it does not apply to");
        assertEquals(2, store.findPresaleCampaign(id).orElseThrow().getSales().size());
    }

    @Test
    void customItemsNeedANameAreCountedAsOtherAndOfferedAgain() throws Exception {
        Store store = store("custom.json");
        String id = store.savePresaleCampaign(new PresaleCampaign(null, "Formal", "", 25, 0, "")).getId();

        assertFalse(store.addPresaleSale(id, new PresaleSale("a", "2026-10-10", "", PresaleSale.OTHER, " ", "", 1, 3, null)), "a custom item must be named");
        assertTrue(store.addPresaleSale(id, new PresaleSale("b", "2026-10-10", "", PresaleSale.OTHER, "Koozie", "", 4, 3, null)));
        assertTrue(store.addPresaleSale(id, new PresaleSale("c", "2026-10-11", "", PresaleSale.OTHER, "Koozie", "", 1, 3, null)));
        assertTrue(store.addPresaleSale(id, new PresaleSale("d", "2026-10-11", "", PresaleSale.OTHER, "Poster", "", 2, 10, null)));
        assertTrue(store.addPresaleSale(id, new PresaleSale("e", "2026-10-11", "", PresaleSale.MERCH, "Hoodie", "", 1, 40, null)));

        PresaleCampaign saved = store.findPresaleCampaign(id).orElseThrow();
        assertEquals(List.of("Koozie", "Poster"), saved.customItemNames());
        assertEquals(8, saved.getMerchSold(), "merch and custom items together: 4 + 1 + 2 + 1");
        assertEquals(0, saved.getTicketsSold());
        assertEquals(75, saved.getGrossRevenue());
        assertEquals("Koozie", saved.getSales().getFirst().getLabel());
        assertEquals("Merch · Hoodie", saved.getSales().getLast().getLabel());
        assertEquals("Wristband", new PresaleSale("x", "2026-10-10", "", PresaleSale.WRISTBAND, "", PresaleSale.MALE, 1, 5, null).getLabel());
    }

    @Test
    void campaignsSurviveReloadAndBackupImport() throws Exception {
        Store source = store("persist.json");
        PresaleCampaign campaign = source.savePresaleCampaign(new PresaleCampaign(null, "Formal", "2026-11-14", 20, 400, "Pizza"));
        source.addPresaleSale(campaign.getId(), new PresaleSale("s1", "2026-10-10", "Ava", PresaleSale.MERCH, "Hoodie", "", 1, 35, "m1"));
        source.addPresaleExpense(campaign.getId(), new PresaleExpense("e1", "2026-10-11", "Hoodies", 20));

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", tempDir.resolve("persist.json").toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        PresaleCampaign loaded = reloaded.getPresaleCampaigns().getFirst();
        assertEquals("Hoodie", loaded.getSales().getFirst().getDescription());
        assertEquals(15, loaded.getNetProfit());

        Store destination = store("persist-destination.json");
        destination.importData(source.exportData());
        assertEquals(400, destination.getPresaleCampaigns().getFirst().getRevenueGoal());
    }

    @Test
    void controllerLogsCreditedSalesAndShowsTheSelectedCampaign() throws Exception {
        Store store = store("controller.json");
        PresaleController controller = new PresaleController(store);
        RedirectAttributesModelMap created = new RedirectAttributesModelMap();
        String view = controller.saveCampaign("", "Fall Formal", "2026-11-14", 25, 1000, "Free ticket", "", created);
        String id = store.getPresaleCampaigns().getFirst().getId();
        assertEquals("redirect:/presales?campaign=" + id, view);
        assertEquals("Created Fall Formal.", created.getFlashAttributes().get("toast"));

        RedirectAttributesModelMap sold = new RedirectAttributesModelMap();
        controller.addSale(id, "2026-10-10", " Ava ", "Ticket", "", "Female", 3, 25, "m2", sold);
        assertEquals("Logged 3 tickets (Female) ($75.00) credited to Grant Hayes.", sold.getFlashAttributes().get("toast"));
        RedirectAttributesModelMap band = new RedirectAttributesModelMap();
        controller.addSale(id, "2026-10-10", "", "Wristband", "", "Male", 1, 5, "", band);
        assertEquals("Logged 1 wristband (Male) ($5.00).", band.getFlashAttributes().get("toast"));
        RedirectAttributesModelMap noGuest = new RedirectAttributesModelMap();
        controller.addSale(id, "2026-10-10", "", "Ticket", "", "", 1, 25, "", noGuest);
        assertEquals("Choose Male or Female for tickets and wristbands.", noGuest.getFlashAttributes().get("toast"));
        RedirectAttributesModelMap unnamed = new RedirectAttributesModelMap();
        controller.addSale(id, "2026-10-10", "", "Custom", " ", "", 1, 3, "", unnamed);
        assertEquals("Name the custom item you sold.", unnamed.getFlashAttributes().get("toast"));
        RedirectAttributesModelMap custom = new RedirectAttributesModelMap();
        controller.addSale(id, "2026-10-10", "", "Custom", " Koozie ", "", 4, 3, "", custom);
        assertEquals("Logged 4 × Koozie ($12.00).", custom.getFlashAttributes().get("toast"));
        RedirectAttributesModelMap bad = new RedirectAttributesModelMap();
        controller.addSale(id, "2026-10-10", "", "Ticket", "", "Male", 0, 25, "", bad);
        assertEquals("Sale was not saved. Check the date, quantity (at least 1) and price.", bad.getFlashAttributes().get("toast"));
        controller.addExpense(id, "2026-10-11", "DJ", 50, new RedirectAttributesModelMap());

        RedirectAttributesModelMap badCampaign = new RedirectAttributesModelMap();
        assertEquals("redirect:/presales", controller.saveCampaign("", " ", "", 0, 0, "", "", badCampaign));
        assertEquals("Enter an event name (up to 100 characters).", badCampaign.getFlashAttributes().get("toast"));

        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("presales", controller.presales(null, model));
        PresaleCampaign shown = (PresaleCampaign) model.getAttribute("campaign");
        assertEquals(id, shown.getId());
        assertEquals(42, shown.getNetProfit(), "$75 tickets + $5 wristband + $12 custom items = $92, less the $50 DJ");
        assertEquals(9.2, (double) model.getAttribute("goalPercent"), 1e-9);
        @SuppressWarnings("unchecked")
        List<PresaleCampaign.LeaderboardRow> board = (List<PresaleCampaign.LeaderboardRow>) model.getAttribute("leaderboard");
        assertEquals("Grant Hayes", board.getFirst().name());
    }

    // ---- Events: completion, ordering, multi-item sales, optional ticket price ----

    @Test
    void anEventCanBeCreatedWithoutATicketPrice() throws Exception {
        Store store = store("free.json");
        PresaleController controller = new PresaleController(store);
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        controller.saveCampaign("", "Philanthropy night", "", 0, 0, "", "", flash);
        assertEquals("Created Philanthropy night.", flash.getFlashAttributes().get("toast"));
        assertEquals(0, store.getPresaleCampaigns().getFirst().getTicketPrice());
    }

    @Test
    void completingAnEventTucksItAwayButKeepsEverythingAndCanBeReopened() throws Exception {
        Store store = store("complete.json");
        PresaleController controller = new PresaleController(store);
        PresaleCampaign formal = store.savePresaleCampaign(new PresaleCampaign(null, "Formal", "2026-11-14", 25, 0, ""));
        PresaleCampaign mixer = store.savePresaleCampaign(new PresaleCampaign(null, "Mixer", "2026-10-30", 10, 0, ""));
        store.addPresaleSale(formal.getId(), sale("s1", PresaleSale.TICKET, 2, 25, null));

        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        assertEquals("redirect:/presales?campaign=" + formal.getId(), controller.complete(formal.getId(), true, flash));
        assertTrue(String.valueOf(flash.getFlashAttributes().get("toast")).contains("Completed events"));
        PresaleCampaign saved = store.findPresaleCampaign(formal.getId()).orElseThrow();
        assertTrue(saved.isCompleted());
        assertFalse(saved.getCompletedDate().isBlank());
        assertEquals(1, saved.getSales().size(), "sales are kept");

        ExtendedModelMap model = new ExtendedModelMap();
        controller.presales(null, model);
        assertEquals(mixer.getId(), ((PresaleCampaign) model.getAttribute("campaign")).getId(), "the open event is shown by default");
        @SuppressWarnings("unchecked")
        List<PresaleController.EventTab> active = (List<PresaleController.EventTab>) model.getAttribute("activeEvents");
        @SuppressWarnings("unchecked")
        List<PresaleController.EventTab> done = (List<PresaleController.EventTab>) model.getAttribute("completedEvents");
        assertEquals(List.of("Mixer"), active.stream().map(tab -> tab.campaign().getName()).toList());
        assertEquals(List.of("Formal"), done.stream().map(tab -> tab.campaign().getName()).toList());

        ExtendedModelMap viewDone = new ExtendedModelMap();
        controller.presales(formal.getId(), viewDone);
        assertEquals(formal.getId(), ((PresaleCampaign) viewDone.getAttribute("campaign")).getId(), "a completed event can still be opened");

        controller.complete(formal.getId(), false, new RedirectAttributesModelMap());
        assertFalse(store.findPresaleCampaign(formal.getId()).orElseThrow().isCompleted());
        assertEquals("", store.findPresaleCampaign(formal.getId()).orElseThrow().getCompletedDate());
        assertEquals("redirect:/presales", controller.complete("missing", true, new RedirectAttributesModelMap()));

        controller.complete(formal.getId(), true, new RedirectAttributesModelMap());
        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", tempDir.resolve("complete.json").toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        assertTrue(reloaded.findPresaleCampaign(formal.getId()).orElseThrow().isCompleted(), "completion is saved");
    }

    @Test
    void openEventsAreOrderedSoonestFirstThenUndatedThenPast() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 24);
        PresaleCampaign later = new PresaleCampaign("a", "Later", "2026-12-01", 0, 0, "");
        PresaleCampaign soon = new PresaleCampaign("b", "Soon", "2026-10-26", 0, 0, "");
        PresaleCampaign undated = new PresaleCampaign("c", "Undated", "", 0, 0, "");
        PresaleCampaign past = new PresaleCampaign("d", "Past", "2026-10-20", 0, 0, "");
        PresaleCampaign tonight = new PresaleCampaign("e", "Tonight", "2026-10-24", 0, 0, "");
        PresaleCampaign finished = new PresaleCampaign("f", "Finished", "2026-09-01", 0, 0, "");
        finished.setCompleted(true);
        List<PresaleCampaign> all = List.of(later, soon, undated, past, tonight, finished);

        List<PresaleController.EventTab> tabs = PresaleController.activeTabs(all, today);
        assertEquals(List.of("Tonight", "Soon", "Later", "Undated", "Past"), tabs.stream().map(tab -> tab.campaign().getName()).toList());
        assertEquals(List.of("Today", "In 2 days", "In 38 days", "No date yet", "Ended 4 days ago"), tabs.stream().map(PresaleController.EventTab::when).toList());
        assertEquals(List.of("Finished"), PresaleController.completedTabs(all, today).stream().map(tab -> tab.campaign().getName()).toList());
    }

    @Test
    void severalItemsAreLoggedTogetherAndNothingIsSavedIfOneLineIsInvalid() throws Exception {
        Store store = store("multi.json");
        PresaleController controller = new PresaleController(store);
        PresaleCampaign campaign = store.savePresaleCampaign(new PresaleCampaign(null, "Formal", "", 0, 0, ""));
        String id = campaign.getId();

        org.springframework.util.LinkedMultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.put("items", List.of("Ticket", "Ticket", "Merch", "Custom"));
        form.put("guestTypes", List.of("Male", "Female", "", ""));
        form.put("descriptions", List.of("", "", "Hoodie, grey", "Koozie"));
        form.put("quantities", List.of("2", "1", "1", "4"));
        form.put("unitPrices", List.of("25", "25", "40", "3"));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        assertEquals("redirect:/presales?campaign=" + id, controller.addSales(id, "2026-10-10", "Ava", "m1", form, flash));
        assertEquals(4, store.findPresaleCampaign(id).orElseThrow().getSales().size());
        assertEquals(127, store.findPresaleCampaign(id).orElseThrow().getGrossRevenue(), 0.001, "$50 + $25 + $40 + $12");
        assertTrue(String.valueOf(flash.getFlashAttributes().get("toast")).contains("$127.00"));
        assertEquals("Hoodie, grey", store.findPresaleCampaign(id).orElseThrow().getSales().get(2).getDescription(), "a comma in a name is kept");
        assertEquals("Ava", store.findPresaleCampaign(id).orElseThrow().getSales().getFirst().getBuyerName());

        org.springframework.util.LinkedMultiValueMap<String, String> bad = new org.springframework.util.LinkedMultiValueMap<>();
        bad.put("items", List.of("Merch", "Ticket"));
        bad.put("guestTypes", List.of("", ""));
        bad.put("descriptions", List.of("Tee", ""));
        bad.put("quantities", List.of("1", "1"));
        bad.put("unitPrices", List.of("15", "25"));
        RedirectAttributesModelMap badFlash = new RedirectAttributesModelMap();
        controller.addSales(id, "2026-10-10", "", "", bad, badFlash);
        assertEquals("Choose Male or Female for each ticket and wristband.", badFlash.getFlashAttributes().get("toast"));
        assertEquals(4, store.findPresaleCampaign(id).orElseThrow().getSales().size(), "the valid merch line was not saved alone");

        assertFalse(store.addPresaleSales(id, List.of(sale("ok", PresaleSale.MERCH, 1, 5, null), sale("bad", PresaleSale.MERCH, 0, 5, null))));
        assertEquals(4, store.findPresaleCampaign(id).orElseThrow().getSales().size());
        RedirectAttributesModelMap empty = new RedirectAttributesModelMap();
        controller.addSales(id, "2026-10-10", "", "", new org.springframework.util.LinkedMultiValueMap<>(), empty);
        assertEquals("Add at least one item to the sale.", empty.getFlashAttributes().get("toast"));
    }
}
