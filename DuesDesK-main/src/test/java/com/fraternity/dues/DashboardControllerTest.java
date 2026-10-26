package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class DashboardControllerTest {

    @Test
    void chargeIsSavedToTheBudgetWithoutAnyDuesCredit() {
        Store store = mock(Store.class);
        ChairBudget budget = new ChairBudget("b1", "Rush", 500);
        when(store.getChairBudgets()).thenReturn(List.of(budget));
        when(store.addReimbursement(eq("b1"), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        DashboardController controller = new DashboardController(store, new SmsService(store));
        var captor = org.mockito.ArgumentCaptor.forClass(ReimbursementRecord.class);

        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        controller.addReimbursement("b1", "2026-10-01", "Shirts", 40, null, redirect);

        verify(store).addReimbursement(eq("b1"), captor.capture());
        assertEquals(null, captor.getValue().getCreditedMemberId());
        assertEquals("Saved $40.00 to the Rush budget.", redirect.getFlashAttributes().get("toast"));
    }

    @Test
    void savingAChairPassesItsOptionalRaiseGoalAndRaisedMoneyIsRecorded() {
        Store store = mock(Store.class);
        ChairBudget budget = new ChairBudget("b1", "Philanthropy", 200);
        when(store.getChairBudgets()).thenReturn(List.of(budget));
        when(store.addRaised(eq("b1"), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        DashboardController controller = new DashboardController(store, new SmsService(store));

        when(store.saveChairBudget("Philanthropy", 200, 1500, "")).thenReturn(new ChairBudget("b9", "Philanthropy", 200));
        RedirectAttributesModelMap save = new RedirectAttributesModelMap();
        assertEquals("redirect:/chair-budgeting", controller.saveChairBudget("Philanthropy", 200, 1500, "", save));
        verify(store).saveChairBudget("Philanthropy", 200, 1500, "");
        assertEquals("Chair budget saved.", save.getFlashAttributes().get("toast"));

        var captor = org.mockito.ArgumentCaptor.forClass(RaisedRecord.class);
        RedirectAttributesModelMap raised = new RedirectAttributesModelMap();
        controller.addRaised("b1", "2026-10-05", " Car wash ", 325.5, "", raised);
        verify(store).addRaised(eq("b1"), captor.capture());
        assertEquals("Car wash", captor.getValue().getDescription());
        assertEquals(325.5, captor.getValue().getAmount());
        assertEquals("Recorded $325.50 raised for Philanthropy.", raised.getFlashAttributes().get("toast"));
        assertEquals("redirect:/chair-budgeting", controller.addRaised("b1", "2026-10-06", "Bake sale", 20, "", new RedirectAttributesModelMap()));
        assertEquals("redirect:/chair-budgeting#budget-b1", controller.addRaised("b1", "2026-10-06", "Bake sale", 20, "panel", new RedirectAttributesModelMap()),
                "entries made in the full-screen view return to that view");

        RedirectAttributesModelMap bad = new RedirectAttributesModelMap();
        controller.addRaised("b1", "not-a-date", "Car wash", 10, "", bad);
        assertEquals("Choose a valid date for the money raised.", bad.getFlashAttributes().get("toast"));
    }
    @Test
    void chairChargesSaveEveryListedRowKeepingCommasAndSkippingBlankRows() {
        Store store = mock(Store.class);
        ChairBudget budget = new ChairBudget("b1", "Rush", 500);
        when(store.getChairBudgets()).thenReturn(List.of(budget));
        when(store.addChairCharges(eq("b1"), org.mockito.ArgumentMatchers.anyList())).thenReturn(2);
        DashboardController controller = new DashboardController(store, new SmsService(store));
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.addAll("chargeDate", List.of("2026-10-01", "2026-10-02", "2026-10-03"));
        form.addAll("chargeDescription", List.of("Shirts, hats", " Pizza ", ""));
        form.addAll("chargeAmount", List.of("40", "25.5", ""));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertEquals("redirect:/chair-budgeting#budget-b1", controller.addChairCharges("b1", form, redirect));
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<ReimbursementRecord>>) (Class<?>) List.class);
        verify(store).addChairCharges(eq("b1"), captor.capture());
        List<ReimbursementRecord> saved = captor.getValue();
        assertEquals(2, saved.size());
        assertEquals("Shirts, hats", saved.get(0).getDescription());
        assertEquals("Pizza", saved.get(1).getDescription());
        assertEquals(25.5, saved.get(1).getAmount());
        assertEquals(null, saved.get(0).getCreditedMemberId());
        assertEquals("Saved 2 charges ($65.50) to the Rush budget.", redirect.getFlashAttributes().get("toast"));
    }

    @Test
    void chairChargesRejectAnInvalidRowWithoutSavingAny() {
        Store store = mock(Store.class);
        when(store.getChairBudgets()).thenReturn(List.of(new ChairBudget("b1", "Rush", 500)));
        DashboardController controller = new DashboardController(store, new SmsService(store));
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.addAll("chargeDate", List.of("2026-10-01", "2026-10-02"));
        form.addAll("chargeDescription", List.of("Shirts", "Pizza"));
        form.addAll("chargeAmount", List.of("40", "-3"));

        controller.addChairCharges("b1", form, new RedirectAttributesModelMap());
        assertEquals("redirect:/chair-budgeting", controller.addChairCharges("missing", form, new RedirectAttributesModelMap()));
        verify(store, never()).addChairCharges(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void calendarOutflowIsSavedAsManualCategorizedEntry() {
        Store store = mock(Store.class);
        when(store.addManualFlowEntry(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        DashboardController controller = new DashboardController(store, new SmsService(store));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.recordCalendarTransaction("out", "Venue & rentals", "2026-10-14", 500, "",
                " Grand Hall ", null, "key-1", redirect);

        assertEquals("redirect:/calendar?month=2026-10&date=2026-10-14", view);
        var captor = org.mockito.ArgumentCaptor.forClass(FlowEntry.class);
        verify(store).addManualFlowEntry(captor.capture());
        FlowEntry entry = captor.getValue();
        assertEquals("manual-key-1", entry.getId());
        assertEquals(-500, entry.getAmount());
        assertEquals("Venue & rentals", entry.getCategory());
        assertEquals("Venue & rentals", entry.getDescription());
        assertEquals("Grand Hall", entry.getCounterparty());
        assertEquals("MANUAL", entry.getReconciliationStatus());
        assertEquals("Outflow of $500.00 recorded under Venue & rentals.", redirect.getFlashAttributes().get("toast"));
    }

    @Test
    void calendarMemberDuesInflowRecordsPaymentOnMember() {
        Store store = mock(Store.class);
        when(store.recordPayment(eq("m1"), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        DashboardController controller = new DashboardController(store, new SmsService(store));

        controller.recordCalendarTransaction("in", TransactionCategories.MEMBER_DUES, "2026-10-03", 75, "Installment",
                "", "m1", "key-2", new RedirectAttributesModelMap());

        var captor = org.mockito.ArgumentCaptor.forClass(PaymentRecord.class);
        verify(store).recordPayment(eq("m1"), captor.capture());
        assertEquals(75, captor.getValue().getAmount());
        assertEquals("Installment", captor.getValue().getDescription());
        verify(store, never()).addManualFlowEntry(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void calendarTransactionRejectsMismatchedCategoryAndMissingMember() {
        Store store = mock(Store.class);
        DashboardController controller = new DashboardController(store, new SmsService(store));

        // An outflow category submitted as money in, and dues without a member, are both refused.
        assertEquals("redirect:/calendar?month=2026-10", controller.recordCalendarTransaction("in", "Insurance",
                "2026-10-03", 10, "", "", null, "k", new RedirectAttributesModelMap()));
        assertEquals("redirect:/calendar?month=2026-10", controller.recordCalendarTransaction("in",
                TransactionCategories.MEMBER_DUES, "2026-10-03", 10, "", "", "", "k", new RedirectAttributesModelMap()));
        assertEquals("redirect:/calendar?month=2026-10", controller.recordCalendarTransaction("out", "Insurance",
                "2026-10-03", 0, "", "", null, "k", new RedirectAttributesModelMap()));
        verify(store, never()).addManualFlowEntry(org.mockito.ArgumentMatchers.any());
        verify(store, never()).recordPayment(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void appearancePreferenceIsSavedAndRejectsUnknownModes() {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        when(store.getSettings()).thenReturn(settings);
        DashboardController controller = new DashboardController(store, new SmsService(store));

        assertEquals(204, controller.updateAppearance("dark").getStatusCode().value());
        assertEquals("dark", settings.getAppearanceMode());
        verify(store).save();

        assertEquals(400, controller.updateAppearance("sepia").getStatusCode().value());
        assertEquals("dark", settings.getAppearanceMode());
    }

    @Test
    void cloudBackupRecoveryPasswordRequiresEnabledBackups() throws Exception {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        when(store.getSettings()).thenReturn(settings);
        CloudBackupService cloudBackupService = mock(CloudBackupService.class);
        DashboardController controller = new DashboardController(store, new SmsService(store),
                cloudBackupService, new FinanceDocumentService(store));

        var response = controller.cloudBackupRecoveryPassword();

        assertEquals(400, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(cloudBackupService, never()).getRecoveryPassword();
    }

    @Test
    void cloudBackupRecoveryPasswordIsReturnedWithoutCachingAndClearsPasswordArray() throws Exception {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        settings.setCloudBackupEnabled(true);
        when(store.getSettings()).thenReturn(settings);
        CloudBackupService cloudBackupService = mock(CloudBackupService.class);
        char[] password = "recovery-key".toCharArray();
        when(cloudBackupService.getRecoveryPassword()).thenReturn(password);
        DashboardController controller = new DashboardController(store, new SmsService(store),
                cloudBackupService, new FinanceDocumentService(store));

        var response = controller.cloudBackupRecoveryPassword();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getFirst("Pragma"));
        assertEquals("recovery-key", response.getBody());
        assertTrue(java.util.Arrays.equals(new char[password.length], password));
        verify(cloudBackupService).getRecoveryPassword();
    }

    @Test
    void treasurySettingsPersistLateFeeClearanceAndMeetingPacketPolicies() {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        when(store.getSettings()).thenReturn(settings);
        DashboardController controller = new DashboardController(store, new SmsService(store));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String result = controller.updateTreasurySettings(false, "", 30, false, 9, "PERCENT", 5,
                15, true, 1200, "Housing | 2026-11-01 | 900", null, redirect);

        assertEquals("redirect:/settings", result);
        assertFalse(settings.isLateFeesEnabled());
        assertEquals(9, settings.getLateFeeGraceDays());
        assertEquals("PERCENT", settings.getLateFeeType());
        assertEquals(5, settings.getLateFeeAmount());
        assertEquals(15, settings.getSocialClearanceTolerance());
        assertTrue(settings.isSocialClearanceDuesOnly());
        assertEquals(1200, settings.getOpeningCashBalance());
        assertEquals("Housing | 2026-11-01 | 900", settings.getUpcomingBills());
        verify(store).save();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardGroupsMemberPaymentsByMonthAndTotalsEachMonth() {
        Store store = mock(Store.class);
        Member member = new Member("member-1", "Alex Member", "555-000-0000",
                0, 0, 0, "Paid in Full", "Never", false);
        member.setPaymentHistory(List.of(
                new PaymentRecord("2026-10-01", 40, "First payment"),
                new PaymentRecord("2026-10-21", 60, "Second payment"),
                new PaymentRecord("2026-09-04", 25, "September payment")
        ));
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getSettings()).thenReturn(new AppSettings());
        when(store.getSmsLogs()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        DashboardController controller = new DashboardController(store, new SmsService(store));

        controller.dashboard(1, null, model);

        Map<String, List<DashboardController.PaymentMonth>> history =
                (Map<String, List<DashboardController.PaymentMonth>>) model.getAttribute("paymentHistoryByMember");
        List<DashboardController.PaymentMonth> monthlyHistory = history.get(member.getId());

        assertEquals(2, monthlyHistory.size());
        assertEquals("October 2026", monthlyHistory.get(0).getMonth());
        assertEquals(100, monthlyHistory.get(0).getTotal());
        assertEquals(2, monthlyHistory.get(0).getPayments().size());
        assertEquals("September 2026", monthlyHistory.get(1).getMonth());
        assertEquals(25, monthlyHistory.get(1).getTotal());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardStatCatalogIncludesCurrentRosterCount() {
        Store store = mock(Store.class);
        Member first = new Member("member-1", "Alex Member", "555-000-0000",
                0, 0, 0, "Paid in Full", "Never", false);
        Member second = new Member("member-2", "Sam Member", "555-000-0001",
                100, 25, 0, "Delinquent", "Never", false);
        when(store.getMembers()).thenReturn(List.of(first, second));
        when(store.getSettings()).thenReturn(new AppSettings());
        when(store.getSmsLogs()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        new DashboardController(store, new SmsService(store)).dashboard(1, null, model);

        Map<String, List<DashboardController.DashStat>> catalog =
                (Map<String, List<DashboardController.DashStat>>) model.getAttribute("statCatalog");
        DashboardController.DashStat memberCount = catalog.values().stream()
                .flatMap(List::stream)
                .filter(stat -> "member-count".equals(stat.key()))
                .findFirst().orElseThrow();

        assertEquals("Members on Roster", memberCount.name());
        assertEquals("2", memberCount.value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardShowsAccountScheduleProgressAndPastDueAttention() {
        Store store = mock(Store.class);
        Member member = new Member("member-1", "Alex Member", "555-000-0000",
                120, 20, 0, "Delinquent", "Never", false);
        member.setInstallments(List.of(
                new PaymentInstallment("installment-1", LocalDate.now().plusDays(3).toString(),
                        "First installment", 60),
                new PaymentInstallment("installment-2", LocalDate.now().plusDays(10).toString(),
                        "Second installment", 60)
        ));
        member.getInstallments().getFirst().setPaidAmount(20);
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getSettings()).thenReturn(new AppSettings());
        when(store.getSmsLogs()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of(
                new FlowEntry("flow-1", LocalDate.now().toString(), "Deposit", "Alex Member", 25, "Bank")
        ));

        Model model = new ExtendedModelMap();
        new DashboardController(store, new SmsService(store)).dashboard(1, null, model);

        Map<String, DashboardController.MemberAccountSummary> summaries =
                (Map<String, DashboardController.MemberAccountSummary>) model.getAttribute("accountSummaries");
        DashboardController.MemberAccountSummary summary = summaries.get(member.getId());
        assertEquals(LocalDate.now().plusDays(3).toString(), summary.nextDueDate());
        assertEquals(40, summary.nextDueAmount());
        assertEquals("Upcoming", summary.nextDueStatus());
        assertEquals(0, summary.installmentsPaid());
        assertEquals(2, summary.installmentCount());
        assertEquals(20, summary.installmentPaidAmount());
        assertEquals(120, summary.installmentTotalAmount());

        List<DashboardController.AttentionItem> attention =
                (List<DashboardController.AttentionItem>) model.getAttribute("attentionItems");
        assertTrue(attention.stream().anyMatch(item -> item.title().contains("past-due balance")));
        assertTrue(attention.stream().anyMatch(item -> item.title().contains("Transactions need review")));
        assertTrue(attention.stream().anyMatch(item -> item.href().contains("#member-member-1")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardOrdersAttentionByUrgencyAndOmitsSnoozedMembers() {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        settings.getAttentionSnoozes().put("snoozed-member", LocalDate.now().plusDays(3).toString());
        Member pastDue = new Member("past-due-member", "Past Due", "555-000-0001",
                100, 35, 0, "Delinquent", "Never", false);
        Member overdueInstallment = new Member("overdue-member", "Overdue Plan", "555-000-0002",
                100, 0, 0, "Payment Plan", "Never", false);
        overdueInstallment.setInstallments(List.of(new PaymentInstallment("late", LocalDate.now().minusDays(2).toString(),
                "Late installment", 100)));
        Member dueToday = new Member("today-member", "Due Today", "555-000-0003",
                100, 0, 0, "Payment Plan", "Never", false);
        dueToday.setInstallments(List.of(new PaymentInstallment("today", LocalDate.now().toString(),
                "Today's installment", 100)));
        Member snoozed = new Member("snoozed-member", "Snoozed", "555-000-0004",
                100, 50, 0, "Delinquent", "Never", false);
        when(store.getMembers()).thenReturn(List.of(pastDue, overdueInstallment, dueToday, snoozed));
        when(store.getSettings()).thenReturn(settings);
        when(store.getSmsLogs()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        new DashboardController(store, new SmsService(store)).dashboard(1, null, model);

        List<DashboardController.AttentionItem> attention =
                (List<DashboardController.AttentionItem>) model.getAttribute("attentionItems");
        assertEquals(List.of("past-due-member", "overdue-member", "today-member"),
                attention.stream().filter(item -> item.memberId() != null)
                        .map(DashboardController.AttentionItem::memberId).toList());
        assertEquals(List.of(0, 1, 2), attention.stream().filter(item -> item.memberId() != null)
                .map(DashboardController.AttentionItem::urgency).toList());
        assertTrue(attention.getLast().urgency() >= 4);
    }

    @Test
    void snoozingAttentionPersistsTheChosenDuration() {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        Member member = new Member("member-1", "Alex", "555-000-0000",
                100, 20, 0, "Delinquent", "Never", false);
        when(store.getSettings()).thenReturn(settings);
        when(store.findMemberById("member-1")).thenReturn(Optional.of(member));

        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        String result = new DashboardController(store, new SmsService(store))
                .snoozeAttention("member-1", 7, redirect);

        assertEquals("redirect:/", result);
        assertEquals(LocalDate.now().plusDays(7).toString(),
                settings.getAttentionSnoozes().get("member-1"));
        verify(store).save();
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarCountsPaymentsAndListsTheSelectedDate() {
        Store store = mock(Store.class);
        Member member = new Member("member-1", "Alex Member", "555-000-0000",
                100, 0, 0, "Payment Plan", "Never", false);
        member.setPaymentHistory(List.of(
                new PaymentRecord("2026-10-21", 40, "First payment"),
                new PaymentRecord("2026-10-21", 60, "Second payment"),
                new PaymentRecord("2026-10-22", 25, "Next-day payment")
        ));
        when(store.getMembers()).thenReturn(List.of(member));

        Model model = new ExtendedModelMap();
        DashboardController controller = new DashboardController(store, new SmsService(store));
        controller.calendar("2026-10", "2026-10-21", model);

        List<DashboardController.CalendarPayment> selected =
                (List<DashboardController.CalendarPayment>) model.getAttribute("selectedPayments");
        List<DashboardController.CalendarDay> days =
                (List<DashboardController.CalendarDay>) model.getAttribute("days");
        DashboardController.CalendarDay selectedDay = days.stream()
                .filter(day -> "2026-10-21".equals(day.getDate())).findFirst().orElseThrow();

        assertEquals(2, selected.size());
        assertEquals(100.0, (Double) model.getAttribute("selectedTotal"), 0.001);
        assertEquals(2, selectedDay.getPaymentCount());
        assertEquals(100.0, selectedDay.getTotal(), 0.001);
        assertEquals(3, model.getAttribute("monthPaymentCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarSelectionShowsMemberPaymentsAndLedgerTransactionsForThatDay() {
        Store store = mock(Store.class);
        Member member = new Member("member-1", "Alex Member", "555-000-0000",
                100, 0, 0, "Payment Plan", "Never", false);
        member.setPaymentHistory(List.of(
                new PaymentRecord("2026-10-21", 40, "Installment")
        ));
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getFlowEntries()).thenReturn(List.of(
                new FlowEntry("flow-1", "2026-10-21", "Supply purchase", "Hardware store", -15, "Venmo"),
                new FlowEntry("flow-2", "2026-10-20", "Other day", "Member", 20, "Venmo")
        ));

        Model model = new ExtendedModelMap();
        new DashboardController(store, new SmsService(store)).calendar("2026-10", "2026-10-21", model);

        List<DashboardController.CalendarPayment> selected =
                (List<DashboardController.CalendarPayment>) model.getAttribute("selectedPayments");
        assertEquals(2, selected.size());
        assertTrue(selected.stream().anyMatch(item -> "Alex Member".equals(item.getMemberName())
                && "Installment".equals(item.getPayment().getDescription())));
        assertTrue(selected.stream().anyMatch(item -> "Hardware store".equals(item.getMemberName())
                && "Supply purchase".equals(item.getPayment().getDescription())
                && item.getPayment().getAmount() == -15));
        assertEquals(40.0, (Double) model.getAttribute("selectedTotal"), 0.001);
        assertEquals(15.0, (Double) model.getAttribute("selectedOutflowTotal"), 0.001);
    }

    @Test
    void recordingPaymentUpdatesBalancesAndPersistsHistory() {
        Store store = mock(Store.class);
        Member member = new Member("member-1", "Alex Member", "555-000-0000",
                100, 30, 0, "Delinquent", "Never", false);
        member.setPaymentHistory(new ArrayList<>());
        when(store.findMemberById(member.getId())).thenReturn(Optional.of(member));
        when(store.recordPayment(eq(member.getId()), org.mockito.ArgumentMatchers.any(PaymentRecord.class))).thenReturn(true);

        DashboardController controller = new DashboardController(store, new SmsService(store));
        String date = LocalDate.now().toString();
        String redirect = controller.recordPayment(member.getId(), date, 40,
                "Dues installment", "request-1", new RedirectAttributesModelMap());

        assertTrue(redirect.contains("date=" + date));
        verify(store).recordPayment(eq(member.getId()), org.mockito.ArgumentMatchers.any(PaymentRecord.class));
    }

    @Test
    void addingAndRemovingChargeReversesItsBalanceImpact() {
        Store store = mock(Store.class);
        Member member = new Member("member-1", "Alex Member", "555-000-0000",
                100, 20, 0, "Delinquent", "Never", false);
        member.setCharges(new ArrayList<>());
        when(store.findMemberById(member.getId())).thenReturn(Optional.of(member));
        when(store.determineStatus(150, 70)).thenReturn("Delinquent");
        when(store.determineStatus(100, 20)).thenReturn("Delinquent");

        DashboardController controller = new DashboardController(store, new SmsService(store));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        controller.addCharge(member.getId(), "Late fee", 50, "", true, redirect);

        assertEquals(150, member.getTotalOwed());
        assertEquals(70, member.getPastDueBalance());
        String chargeId = member.getCharges().getFirst().getId();
        controller.removeCharge(member.getId(), chargeId, redirect);

        assertEquals(100, member.getTotalOwed());
        assertEquals(20, member.getPastDueBalance());
        assertTrue(member.getCharges().isEmpty());
        verify(store, org.mockito.Mockito.times(2)).save();
    }

    @Test
    @SuppressWarnings("unchecked")
    void numbersShowsAllRequestedMetricsAndIncludesPaidMembersInRosterAverage() {
        Store store = mock(Store.class);
        Member paidMember = new Member("member-1", "Alex Member", "555-000-0000",
                0, 0, 0, "Paid in Full", "Never", false);
        paidMember.setPaymentHistory(List.of(new PaymentRecord("2026-10-01", 100, "Dues")));
        Member balanceMember = new Member("member-2", "Sam Member", "555-000-0001",
                100, 25, 0, "Delinquent", "Never", false);
        when(store.getMembers()).thenReturn(List.of(paidMember, balanceMember));

        Model model = new ExtendedModelMap();
        new DashboardController(store, new SmsService(store)).numbers(model);

        List<DashboardController.MetricGroup> groups =
                (List<DashboardController.MetricGroup>) model.getAttribute("metricGroups");
        List<DashboardController.Metric> metrics = groups.stream()
                .flatMap(group -> group.getMetrics().stream()).toList();
        DashboardController.Metric averageDues = metrics.stream()
                .filter(metric -> metric.getName().startsWith("Average Dues Per Member"))
                .findFirst().orElseThrow();

        assertEquals(4, groups.size());
        assertEquals(22, metrics.size());
        assertEquals("$50.00", averageDues.getValue());
    }

    @Test
    void metricHistoryReturnsOnlySnapshotsWithinCustomDateRange() {
        Store store = mock(Store.class);
        LocalDate today = LocalDate.now();
        when(store.getMetricHistory()).thenReturn(List.of(
                new Store.MetricSnapshot(today.minusDays(5).toString(), Map.of("net-collection-rate", 20.0)),
                new Store.MetricSnapshot(today.minusDays(2).toString(), Map.of("net-collection-rate", 40.0)),
                new Store.MetricSnapshot(today.toString(), Map.of("net-collection-rate", 60.0))
        ));
        DashboardController controller = new DashboardController(store, new SmsService(store));

        org.springframework.http.ResponseEntity<?> response = controller.metricHistory(
                "net-collection-rate", today.minusDays(3).toString(), today.toString());

        assertEquals(200, response.getStatusCode().value());
        DashboardController.MetricHistoryResponse body =
                (DashboardController.MetricHistoryResponse) response.getBody();
        assertEquals(2, body.points().size());
        assertEquals(40, body.points().getFirst().value());
        assertEquals(60, body.points().getLast().value());
    }

    @Test
    void metricHistoryRejectsInvalidOrReversedCustomDateRanges() {
        Store store = mock(Store.class);
        DashboardController controller = new DashboardController(store, new SmsService(store));

        assertEquals(400, controller.metricHistory("net-collection-rate", "not-a-date",
                LocalDate.now().toString()).getStatusCode().value());
        assertEquals(400, controller.metricHistory("net-collection-rate", LocalDate.now().toString(),
                LocalDate.now().minusDays(1).toString()).getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardPaginatesLargeRosterWithoutChangingChapterTotals() {
        Store store = mock(Store.class);
        List<Member> roster = java.util.stream.IntStream.range(0, 30)
                .mapToObj(index -> new Member("member-" + index, "Member " + index, "555-000-0000",
                        10, 0, 0, "Payment Plan", "Never", false))
                .toList();
        when(store.getMembers()).thenReturn(roster);
        when(store.getSettings()).thenReturn(new AppSettings());
        when(store.getSmsLogs()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        new DashboardController(store, new SmsService(store)).dashboard(2, null, model);

        List<Member> visibleMembers = (List<Member>) model.getAttribute("members");
        assertEquals(5, visibleMembers.size());
        assertEquals(2, model.getAttribute("currentPage"));
        assertEquals(2, model.getAttribute("totalPages"));
        assertEquals(30, model.getAttribute("totalMembers"));
        assertEquals(300.0, (Double) model.getAttribute("totalOwed"), 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLedgerShowsOneRosterAtATimeWhileTotalsCoverEveryone() {
        Store store = mock(Store.class);
        Member active = new Member("a", "Active Member", "1", 100, 0, 0, "Payment Plan", "Never", false);
        Member inactive = new Member("i", "Inactive Member", "2", 50, 0, 0, "Payment Plan", "Never", false);
        inactive.setMembershipStatus("inactive");
        Member alumnus = new Member("o", "Alumni Member", "3", 25, 0, 0, "Payment Plan", "Never", false);
        alumnus.setMembershipStatus(Member.ALUMNI);
        when(store.getMembers()).thenReturn(new ArrayList<>(List.of(active, inactive, alumnus)));
        when(store.getSettings()).thenReturn(new AppSettings());
        when(store.getSmsLogs()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());
        DashboardController controller = new DashboardController(store, new SmsService(store));

        Model members = new ExtendedModelMap();
        controller.dashboard(1, null, members);
        assertEquals(List.of("a"), ((List<Member>) members.getAttribute("members")).stream().map(Member::getId).toList());
        assertEquals(Member.MEMBER, members.getAttribute("rosterGroup"));
        assertEquals(Map.of("Member", 1L, "Inactive", 1L, "Alumni", 1L), members.getAttribute("rosterGroups"));
        assertEquals(175.0, (Double) members.getAttribute("totalOwed"), 0.001);

        Model alumni = new ExtendedModelMap();
        controller.dashboard(1, null, "alumni", alumni);
        assertEquals(List.of("o"), ((List<Member>) alumni.getAttribute("members")).stream().map(Member::getId).toList());
        assertEquals("Alumni", alumni.getAttribute("rosterGroup"));
        assertEquals(1, alumni.getAttribute("totalMembers"));
    }

    @Test
    void theProfileStatusMovesAMemberBetweenRostersAndReturnsToTheSameView() {
        Store store = mock(Store.class);
        Member member = new Member("m1", "Jordan Hayes", "555", 0, 0, 0, "Paid in Full", "Never", false);
        when(store.findMemberById("m1")).thenReturn(Optional.of(member));
        when(store.determineStatus(0, 0)).thenReturn("Paid in Full");
        DashboardController controller = new DashboardController(store, new SmsService(store));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String result = controller.editMember("m1", "Jordan Hayes", "555", 0, 0, 0, null, null, null, "Alumni", "inactive", redirect);
        assertEquals("redirect:/?group=inactive", result);
        assertEquals(Member.ALUMNI, member.getMembershipStatus());
        assertEquals("Updated profile for Jordan Hayes and moved them to Alumni.", redirect.getFlashAttributes().get("toast"));
        assertEquals(Member.MEMBER, Member.normalizeMembershipStatus("something else"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inactiveAndAlumniDuesHaveTheirOwnCollectionRateAndUnpaidStats() {
        Store store = mock(Store.class);
        Member active = new Member("a", "Active", "1", 100, 0, 0, "Payment Plan", "Never", false);
        Member inactive = new Member("i", "Inactive", "2", 150, 0, 50, "Payment Plan", "Never", false);
        inactive.setMembershipStatus(Member.INACTIVE);
        Member alumnus = new Member("o", "Alumnus", "3", 0, 0, 30, "Paid in Full", "Never", false);
        alumnus.setMembershipStatus(Member.ALUMNI);
        AppSettings settings = new AppSettings();
        settings.setDashboardStats(new ArrayList<>(List.of("inactive-rate", "inactive-unpaid", "alumni-rate", "alumni-unpaid", "rate")));
        when(store.getMembers()).thenReturn(new ArrayList<>(List.of(active, inactive, alumnus)));
        when(store.getSettings()).thenReturn(settings);
        when(store.getSmsLogs()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        new DashboardController(store, new SmsService(store)).dashboard(1, null, model);

        List<DashboardController.DashStat> boxes = (List<DashboardController.DashStat>) model.getAttribute("statBoxes");
        assertEquals("Inactive Collection Rate", boxes.get(0).name());
        assertEquals("25.0%", boxes.get(0).value());
        assertEquals("Inactive Dues Unpaid", boxes.get(1).name());
        assertEquals("$150.00", boxes.get(1).value());
        assertEquals("100.0%", boxes.get(2).value());
        assertEquals("$0.00", boxes.get(3).value());
        Map<String, List<DashboardController.DashStat>> catalog = (Map<String, List<DashboardController.DashStat>>) model.getAttribute("statCatalog");
        assertEquals(4, catalog.get("Inactive & Alumni Dues").size());
    }
}
