package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Recurring bills: schedules, reminders, automatic vs manual payment, and the Calendar forms. */
class RecurringBillTest {
    private static final LocalDate TODAY = LocalDate.now();

    @TempDir
    Path tempDir;

    private Store store(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "{\"members\":[],\"settings\":{\"ihqBillAmount\":0}}");
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }

    private static RecurringBill details(String name, String frequency, LocalDate start, String mode, Integer... reminders) {
        return new RecurringBill(null, name, 800, frequency, start.toString(), "", "Venue & rentals", "Landlord", mode, List.of(reminders), null);
    }

    @Test
    void schedulesFollowTheFrequencyAndStopAtTheEndDate() {
        RecurringBill monthly = new RecurringBill("a", "Rent", 800, "MONTHLY", "2026-01-31", "", "Venue & rentals", "", "MANUAL", List.of(), "2026-01-01");
        assertEquals(LocalDate.of(2026, 2, 28), monthly.dueDate(1), "a bill on the 31st lands on each month's last day");
        assertEquals(LocalDate.of(2026, 3, 31), monthly.dueDate(2));
        assertEquals(List.of(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)),
                monthly.dueDatesBetween(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31)));

        assertEquals(LocalDate.of(2026, 1, 15), new RecurringBill("b", "x", 1, "WEEKLY", "2026-01-01", "", "c", "", "MANUAL", List.of(), null).dueDate(2));
        assertEquals(LocalDate.of(2026, 1, 29), new RecurringBill("c", "x", 1, "BIWEEKLY", "2026-01-01", "", "c", "", "MANUAL", List.of(), null).dueDate(2));
        assertEquals(LocalDate.of(2026, 7, 1), new RecurringBill("d", "x", 1, "QUARTERLY", "2026-01-01", "", "c", "", "MANUAL", List.of(), null).dueDate(2));
        assertEquals(LocalDate.of(2028, 1, 1), new RecurringBill("e", "x", 1, "YEARLY", "2026-01-01", "", "c", "", "MANUAL", List.of(), null).dueDate(2));

        RecurringBill ending = new RecurringBill("f", "Lease", 1, "MONTHLY", "2026-01-10", "2026-03-10", "c", "", "MANUAL", List.of(), "2026-01-01");
        assertEquals(3, ending.dueDatesBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)).size(), "Jan, Feb and Mar only");
    }

    @Test
    void dueDatesBeforeTheBillWasCreatedAreNotTracked() {
        RecurringBill bill = new RecurringBill("a", "Rent", 800, "WEEKLY", TODAY.minusWeeks(10).toString(), "", "c", "", "MANUAL", List.of(7), TODAY.toString());
        assertEquals(TODAY.minusWeeks(10).plusWeeks(10), bill.nextPending(), "the first tracked due date is on or after the day it was created");
        assertFalse(bill.isTracked(TODAY.minusWeeks(1)));
        assertTrue(bill.isTracked(TODAY));
    }

    @Test
    void remindersFireInsideTheLargestLeadTimeAndNameTheOneReached() {
        RecurringBill bill = new RecurringBill("a", "Insurance", 300, "MONTHLY", TODAY.plusDays(10).toString(), "", "c", "", "MANUAL",
                List.of(7, 3, 0), TODAY.toString());
        assertNull(bill.reminder(TODAY), "10 days out is earlier than the 7-day reminder");

        RecurringBill.Reminder week = bill.reminder(TODAY.plusDays(3));
        assertNotNull(week);
        assertEquals(7, week.daysUntil());
        assertEquals(7, week.triggeredLeadDays());
        assertEquals("info", week.getTone());
        assertEquals("Due in 7 days", week.getWhen());

        RecurringBill.Reminder soon = bill.reminder(TODAY.plusDays(8));
        assertEquals(2, soon.daysUntil());
        assertEquals(3, soon.triggeredLeadDays(), "the smallest lead time still at or above the days left");
        assertEquals("warning", soon.getTone());
        assertEquals("3 days before", soon.getLeadLabel());

        assertEquals("Due today", bill.reminder(TODAY.plusDays(10)).getWhen());
        assertEquals("danger", bill.reminder(TODAY.plusDays(10)).getTone());
    }

    @Test
    void overdueManualBillsAlwaysShowButAutomaticOnesNeverDoAndNoLeadsMeansNoReminder() {
        RecurringBill manual = new RecurringBill("a", "Rent", 800, "MONTHLY", TODAY.toString(), "", "c", "", "MANUAL", List.of(), TODAY.toString());
        assertNull(manual.reminder(TODAY.minusDays(3)), "reminders are off, and it isn't due yet");
        RecurringBill.Reminder overdue = manual.reminder(TODAY.plusDays(5));
        assertNotNull(overdue, "a missed manual payment shows even with reminders turned off");
        assertTrue(overdue.isOverdue());
        assertEquals("5 days overdue", overdue.getWhen());

        RecurringBill automatic = new RecurringBill("b", "Netflix", 20, "MONTHLY", TODAY.toString(), "", "c", "", "AUTOMATIC", List.of(3), TODAY.toString());
        assertNull(automatic.reminder(TODAY.plusDays(5)), "an overdue automatic bill is recorded, not nagged about");
        assertNotNull(automatic.reminder(TODAY.minusDays(2)), "but it gets a heads-up inside its reminder window");
    }

    @Test
    void savingValidatesEverythingAndEditingKeepsPaymentHistory() throws Exception {
        Store store = store("validate.json");
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(details(" ", "MONTHLY", TODAY, "MANUAL")));
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(new RecurringBill(null, "X", 0, "MONTHLY", TODAY.toString(), "", "Venue & rentals", "", "MANUAL", List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(details("X", "DAILY", TODAY, "MANUAL")));
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(new RecurringBill(null, "X", 5, "MONTHLY", "nope", "", "Venue & rentals", "", "MANUAL", List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(new RecurringBill(null, "X", 5, "MONTHLY", TODAY.toString(), TODAY.minusDays(1).toString(), "Venue & rentals", "", "MANUAL", List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(new RecurringBill(null, "X", 5, "MONTHLY", TODAY.toString(), "", "Not a category", "", "MANUAL", List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(details("X", "MONTHLY", TODAY, "SOMETIMES")));
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(details("X", "MONTHLY", TODAY, "MANUAL", 400)), "lead times top out at a year");
        assertThrows(IllegalArgumentException.class, () -> store.saveRecurringBill(details("X", "MONTHLY", TODAY, "MANUAL", 1, 2, 3, 4, 5, 6, 7, 8, 9)));
        assertTrue(store.getRecurringBills().isEmpty());

        RecurringBill created = store.saveRecurringBill(details(" Rent ", "MONTHLY", TODAY.plusDays(5), "MANUAL", 1, 7, 7, 0));
        assertEquals("Rent", created.getName());
        assertEquals(List.of(7, 1, 0), created.getReminderDays(), "sorted, most notice first, duplicates dropped");
        assertEquals(TODAY.toString(), created.getCreatedDate());

        assertTrue(store.payRecurringBill(created.getId(), TODAY.plusDays(5), TODAY, 800));
        RecurringBill edited = store.saveRecurringBill(new RecurringBill(created.getId(), "House rent", 850, "MONTHLY", TODAY.plusDays(5).toString(),
                "", "Venue & rentals", "Landlord", "AUTOMATIC", List.of(3), null));
        assertEquals("House rent", edited.getName());
        assertEquals(850, edited.getAmount());
        assertTrue(edited.isAutomatic());
        assertEquals(1, edited.getSettlements().size(), "editing never erases what was already paid");
        assertEquals(1, store.getRecurringBills().size());
    }

    @Test
    void manualBillsAreMarkedPaidOnceAndLeaveAnOutflowInTheTimeline() throws Exception {
        Store store = store("manual.json");
        RecurringBill bill = store.saveRecurringBill(details("Rent", "MONTHLY", TODAY.minusDays(0), "MANUAL", 7, 0));
        LocalDate due = bill.nextPending();
        assertEquals(TODAY, due);

        assertTrue(store.payRecurringBill(bill.getId(), due, TODAY, 812.5));
        assertFalse(store.payRecurringBill(bill.getId(), due, TODAY, 812.5), "a due date can only be paid once");
        assertFalse(store.skipRecurringBill(bill.getId(), due), "and a paid one can't be skipped");
        assertFalse(store.payRecurringBill(bill.getId(), due.plusDays(3), TODAY, 10), "the date must be one of the bill's due dates");
        assertFalse(store.payRecurringBill(bill.getId(), due.plusMonths(1), TODAY, 0), "amount must be positive");

        FlowEntry entry = store.getFlowEntries().stream().filter(e -> e.getId().equals("bill-" + bill.getId() + "-" + due)).findFirst().orElseThrow();
        assertEquals(-812.5, entry.getAmount());
        assertEquals("Rent", entry.getDescription());
        assertEquals("Landlord", entry.getCounterparty());
        assertEquals("Venue & rentals", entry.getCategory());
        assertEquals("MANUAL", entry.getReconciliationStatus());
        assertEquals("Recurring bill", entry.getSource());
        assertEquals(due.plusMonths(1), store.getRecurringBills().getFirst().nextPending(), "next month's due date is now the one waiting");

        assertTrue(store.skipRecurringBill(bill.getId(), due.plusMonths(1)));
        assertEquals(due.plusMonths(2), store.getRecurringBills().getFirst().nextPending());
        assertEquals(1, store.getFlowEntries().size(), "skipping records nothing");
    }

    @Test
    void automaticBillsRecordThemselvesOnTheDueDateAndCatchUpAfterTheAppWasClosed() throws Exception {
        Store store = store("automatic.json");
        RecurringBill bill = store.saveRecurringBill(details("Insurance", "WEEKLY", TODAY.minusDays(20), "AUTOMATIC", 3));
        assertTrue(store.getFlowEntries().isEmpty(), "nothing before the due date; earlier dates were before the bill existed");
        bill.setCreatedDate(TODAY.minusDays(20).toString()); // as if it had been set up three weeks ago and the app stayed closed

        assertEquals(3, store.processRecurringBills(TODAY), "20, 13 and 6 days ago were all missed");
        assertEquals(0, store.processRecurringBills(TODAY), "running again records nothing twice");
        List<FlowEntry> entries = store.getFlowEntries();
        assertEquals(3, entries.size());
        assertTrue(entries.stream().allMatch(e -> e.getAmount() == -800 && "Recurring bill (automatic)".equals(e.getSource())));
        assertEquals(TODAY.minusDays(20).toString(), entries.stream().map(FlowEntry::getDate).min(String::compareTo).orElseThrow());
        assertEquals(TODAY.plusDays(1), store.getRecurringBills().getFirst().nextPending(), "the next charge is the upcoming one");
        assertTrue(store.getRecurringBills().getFirst().getSettlements().stream().allMatch(RecurringBill.Settlement::isAutomatic));

        // A manual bill is never recorded for you, however long it has been due.
        store.saveRecurringBill(details("Cleaning", "WEEKLY", TODAY, "MANUAL"));
        assertEquals(5, store.processRecurringBills(TODAY.plusDays(30)), "only the automatic Insurance bill: 5 more weekly charges");
        assertTrue(store.getFlowEntries().stream().noneMatch(e -> "Cleaning".equals(e.getDescription())));
    }

    @Test
    void deletingABillKeepsThePaymentsAlreadyRecorded() throws Exception {
        Store store = store("delete.json");
        RecurringBill bill = store.saveRecurringBill(details("Rent", "MONTHLY", TODAY, "MANUAL"));
        store.payRecurringBill(bill.getId(), TODAY, TODAY, 800);

        assertTrue(store.deleteRecurringBill(bill.getId()));
        assertFalse(store.deleteRecurringBill(bill.getId()));
        assertTrue(store.getRecurringBills().isEmpty());
        assertEquals(1, store.getFlowEntries().size(), "the payment stays in Inflow/Outflow");
    }

    @Test
    void billsAndTheirHistorySurviveReloadAndBackupImport() throws Exception {
        Store source = store("persist.json");
        RecurringBill bill = source.saveRecurringBill(details("Rent", "QUARTERLY", TODAY, "AUTOMATIC", 14, 1));
        source.processRecurringBills(TODAY);

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", tempDir.resolve("persist.json").toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        RecurringBill loaded = reloaded.getRecurringBills().getFirst();
        assertEquals("QUARTERLY", loaded.getFrequency());
        assertEquals(List.of(14, 1), loaded.getReminderDays());
        assertEquals(1, loaded.getSettlements().size());
        assertEquals(1, reloaded.getFlowEntries().size());

        Store destination = store("persist-destination.json");
        destination.importData(source.exportData());
        assertEquals(bill.getId(), destination.getRecurringBills().getFirst().getId());
        assertTrue(destination.getRecurringBills().getFirst().isAutomatic());
    }

    @Test
    void calendarHelpersListRemindersRowsAndBillsByDay() throws Exception {
        Store store = store("calendar.json");
        RecurringBill rent = store.saveRecurringBill(details("Rent", "MONTHLY", TODAY.plusDays(2), "MANUAL", 7));
        RecurringBill auto = store.saveRecurringBill(details("Netflix", "MONTHLY", TODAY.plusDays(20), "AUTOMATIC", 3));
        List<RecurringBill> bills = store.getRecurringBills();

        List<RecurringBill.Reminder> reminders = BillCalendar.reminders(bills, TODAY);
        assertEquals(List.of("Rent"), reminders.stream().map(r -> r.bill().getName()).toList(), "Netflix is 20 days away, outside its window");

        List<BillCalendar.BillRow> rows = BillCalendar.rows(bills, TODAY);
        assertEquals(List.of("Rent", "Netflix"), rows.stream().map(r -> r.bill().getName()).toList(), "soonest first");
        assertEquals("Due in 2 days", rows.get(0).status());
        assertEquals("Charged in 20 days", rows.get(1).status());

        var byDay = BillCalendar.dueByDay(bills, YearMonth.from(TODAY.plusDays(2)), TODAY);
        assertTrue(byDay.containsKey(TODAY.plusDays(2).toString()));
        assertEquals("Rent", byDay.get(TODAY.plusDays(2).toString()).getFirst().get("name"));
        assertEquals(rent.getId(), BillCalendar.editData(bills).getFirst().get("id"));
        assertNotNull(auto);
    }

    @Test
    void theCalendarFormsCreateEditPayAndSkipBills() throws Exception {
        Store store = store("controller.json");
        RecurringBillController controller = new RecurringBillController(store);

        RedirectAttributesModelMap created = new RedirectAttributesModelMap();
        assertEquals("redirect:/calendar#bills", controller.saveBill("", "Rent", 800, "MONTHLY", TODAY.toString(), "",
                "Venue & rentals", "Landlord", "MANUAL", List.of(7, 1), "10, 21 3", created));
        assertEquals("Added Rent: $800.00 every month, paid manually.", created.getFlashAttributes().get("toast"));
        RecurringBill bill = store.getRecurringBills().getFirst();
        assertEquals(List.of(21, 10, 7, 3, 1), bill.getReminderDays(), "preset checkboxes plus custom days, separated by commas or spaces");

        RedirectAttributesModelMap badCustom = new RedirectAttributesModelMap();
        controller.saveBill("", "Other", 5, "MONTHLY", TODAY.toString(), "", "Venue & rentals", "", "MANUAL", null, "soon", badCustom);
        assertEquals("Custom reminders are whole numbers of days, like 10 or 21.", badCustom.getFlashAttributes().get("toast"));
        assertEquals(1, store.getRecurringBills().size());

        RedirectAttributesModelMap updated = new RedirectAttributesModelMap();
        controller.saveBill(bill.getId(), "House rent", 850, "MONTHLY", TODAY.toString(), "", "Venue & rentals", "", "AUTOMATIC", null, "", updated);
        assertEquals("Updated House rent: $850.00 every month, paid automatically.", updated.getFlashAttributes().get("toast"));
        assertTrue(store.getRecurringBills().getFirst().getReminderDays().isEmpty(), "no checkboxes ticked means no reminders");

        RecurringBill manual = store.saveRecurringBill(details("Cleaning", "WEEKLY", TODAY, "MANUAL", 1));
        RedirectAttributesModelMap paid = new RedirectAttributesModelMap();
        controller.pay(manual.getId(), TODAY.toString(), "", null, paid);
        assertEquals("Recorded $800.00 paid for Cleaning (due " + TODAY + ").", paid.getFlashAttributes().get("toast"));
        RedirectAttributesModelMap again = new RedirectAttributesModelMap();
        controller.pay(manual.getId(), TODAY.toString(), "", null, again);
        assertTrue(again.getFlashAttributes().get("toast").toString().startsWith("That payment wasn't recorded."));

        RedirectAttributesModelMap skipped = new RedirectAttributesModelMap();
        controller.skip(manual.getId(), TODAY.plusWeeks(1).toString(), skipped);
        assertEquals("Skipped the " + TODAY.plusWeeks(1) + " due date.", skipped.getFlashAttributes().get("toast"));

        RedirectAttributesModelMap removed = new RedirectAttributesModelMap();
        controller.delete(manual.getId(), removed);
        assertTrue(removed.getFlashAttributes().get("toast").toString().startsWith("Recurring bill removed."));
        assertEquals(1, store.getRecurringBills().size());
    }
}
