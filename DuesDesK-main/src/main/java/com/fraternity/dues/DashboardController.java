package com.fraternity.dues;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Controller
public class DashboardController {

    private final Store store;
    private final SmsService smsService;
    private final MemberDocumentService documentService;
    private final BackupEncryptionService backupEncryptionService = new BackupEncryptionService();
    private final FinanceDocumentService financeDocumentService;
    private final FlowReportService flowReportService;
    private final TableExportService tableExportService;
    private final CloudBackupService cloudBackupService;
    private ReminderSequenceService reminderSequence;

    public DashboardController(Store store, SmsService smsService) {
        this(store, smsService, null, new FinanceDocumentService(store));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardController(Store store, SmsService smsService, CloudBackupService cloudBackupService,
                               FinanceDocumentService financeDocumentService) {
        this.store = store;
        this.smsService = smsService;
        this.cloudBackupService = cloudBackupService;
        this.financeDocumentService = financeDocumentService;
        this.flowReportService = new FlowReportService(store);
        this.documentService = new MemberDocumentService(() -> ExecutivePdf.chapterOf(store));
        this.tableExportService = new TableExportService(() -> ExecutivePdf.chapterOf(store));
    }

    /** The automatic reminder sequence, when running inside the app (tests may leave it unset). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setReminderSequence(ReminderSequenceService reminderSequence) {
        this.reminderSequence = reminderSequence;
    }

    public String dashboard(int page, String sort, Model model) {
        return dashboard(page, sort, null, model);
    }

    /** The ledger shows one roster at a time: active Members (the default), Inactive, or Alumni. */
    static String rosterGroup(String group) {
        return Member.normalizeMembershipStatus(group);
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "page", defaultValue = "1") int page,
                            @RequestParam(name = "sort", required = false) String sort,
                            @RequestParam(name = "group", required = false) String group, Model model) {
        String rosterGroup = rosterGroup(group);
        // Automatic bills that have come due are recorded first, so the numbers and reminders below are current.
        store.processRecurringBills(LocalDate.now());
        List<Member> allMembers = store.getMembers();
        java.util.Comparator<Member> order = RosterSort.comparator(sort, Member::getName,
                Map.of("owed", Member::getTotalOwed, "pastdue", Member::getPastDueBalance, "paid", Member::getPaidThisMonth));
        if (order != null) {
            allMembers.sort(order);
            model.addAttribute("sort", sort);
        }
        // The table shows the chosen roster; every total, reminder and attention item still covers everyone.
        List<Member> groupMembers = allMembers.stream().filter(member -> rosterGroup.equals(member.getMembershipStatus())).toList();
        Map<String, Long> groupCounts = new LinkedHashMap<>();
        Member.MEMBERSHIP_STATUSES.forEach(status -> groupCounts.put(status,
                allMembers.stream().filter(member -> status.equals(member.getMembershipStatus())).count()));
        int pageSize = 25;
        int totalPages = Math.max(1, (int) Math.ceil(groupMembers.size() / (double) pageSize));
        int currentPage = Math.min(Math.max(1, page), totalPages);
        int firstIndex = (currentPage - 1) * pageSize;
        List<Member> members = groupMembers.subList(firstIndex, Math.min(firstIndex + pageSize, groupMembers.size()));
        // The page also carries each other roster's first page, so switching Members / Inactive / Alumni happens in
        // place without reloading. The chosen roster keeps the requested page.
        List<Member> ledgerMembers = new ArrayList<>(members);
        Map<String, RosterPage> rosterPages = new LinkedHashMap<>();
        for (String status : Member.MEMBERSHIP_STATUSES) {
            List<Member> list = allMembers.stream().filter(member -> status.equals(member.getMembershipStatus())).toList();
            int pages = Math.max(1, (int) Math.ceil(list.size() / (double) pageSize));
            int shownPage = status.equals(rosterGroup) ? currentPage : 1;
            int first = (shownPage - 1) * pageSize;
            int last = Math.min(first + pageSize, list.size());
            if (!status.equals(rosterGroup)) ledgerMembers.addAll(list.subList(first, last));
            rosterPages.put(status, new RosterPage(status, shownPage, pages, list.size(), first + 1, last));
        }
        Map<String, List<PaymentMonth>> paymentHistoryByMember = new LinkedHashMap<>();
        Map<String, MemberAccountSummary> accountSummaries = new LinkedHashMap<>();

        for (Member member : allMembers) {
            accountSummaries.put(member.getId(), accountSummary(member));
        }
        for (Member member : ledgerMembers) {
            Map<String, List<PaymentRecord>> paymentsByMonth = new LinkedHashMap<>();
            if (member.getPaymentHistory() != null) {
                for (PaymentRecord payment : member.getPaymentHistory()) {
                    paymentsByMonth.computeIfAbsent(payment.monthLabel(), month -> new ArrayList<>()).add(payment);
                }
            }

            List<PaymentMonth> monthlyHistory = new ArrayList<>();
            paymentsByMonth.forEach((month, payments) -> {
                double total = payments.stream().mapToDouble(PaymentRecord::getAmount).sum();
                monthlyHistory.add(new PaymentMonth(month, payments, total));
            });
            paymentHistoryByMember.put(member.getId(), monthlyHistory);
        }

        double monthlyInflow = allMembers.stream().mapToDouble(Member::getPaidThisMonth).sum();
        double totalOwed = allMembers.stream().mapToDouble(Member::getTotalOwed).sum();
        double totalPastDue = allMembers.stream().mapToDouble(Member::getPastDueBalance).sum();

        double totalBilledPool = monthlyInflow + totalOwed;
        double collectionRate = totalBilledPool > 0 ? (monthlyInflow / totalBilledPool) * 100.0 : 100.0;

        long paidCount = allMembers.stream().filter(m -> "Paid in Full".equalsIgnoreCase(m.getStatus())).count();
        long planCount = allMembers.stream().filter(m -> "Payment Plan".equalsIgnoreCase(m.getStatus())).count();
        long delinquentCount = allMembers.stream().filter(m -> "Delinquent".equalsIgnoreCase(m.getStatus())).count();

        model.addAttribute("members", members);
        model.addAttribute("allRosterMembers", allMembers);
        model.addAttribute("chargePresets", store.getChargePresets());
        Map<String, Boolean> socialClearance = new LinkedHashMap<>();
        allMembers.forEach(member -> socialClearance.put(member.getId(), store.isSociallyCleared(member)));
        model.addAttribute("socialClearanceByMember", socialClearance);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalMembers", groupMembers.size());
        model.addAttribute("rosterGroup", rosterGroup);
        model.addAttribute("rosterGroups", groupCounts);
        model.addAttribute("rosterPages", rosterPages);
        model.addAttribute("ledgerMembers", ledgerMembers);
        model.addAttribute("hasPreviousPage", currentPage > 1);
        model.addAttribute("hasNextPage", currentPage < totalPages);
        model.addAttribute("paymentHistoryByMember", paymentHistoryByMember);
        model.addAttribute("accountSummaries", accountSummaries);
        // Rendered reminder for every member who owes, for the Email / Messages / Copy quick actions in the ledger.
        Map<String, String> reminderTexts = new LinkedHashMap<>();
        allMembers.stream().filter(member -> Money.positive(member.getTotalOwed()))
                .forEach(member -> reminderTexts.put(member.getId(), smsService.formatMessage(member)));
        model.addAttribute("reminderTexts", reminderTexts);
        model.addAttribute("today", LocalDate.now().toString());
        model.addAttribute("settings", store.getSettings());
        model.addAttribute("smsLogs", store.getSmsLogs());
        model.addAttribute("smsRemainingToday", store.getSmsRemainingToday());
        model.addAttribute("liveSmsEnabled", smsService.isLiveConfigured());
        List<SmsService.ReminderCandidate> reminderPreview = smsService.reminderPreview();
        model.addAttribute("reminderPreview", reminderPreview);
        model.addAttribute("reminderPreviewTotal", reminderPreview.stream()
                .mapToDouble(SmsService.ReminderCandidate::amount).sum());
        model.addAttribute("reminderDaysBeforeDue", store.getSettings().getReminderDaysBeforeDue());
        model.addAttribute("reminderDaysAfterDue", store.getSettings().getReminderDaysAfterDue());

        AppSettings settings = store.getSettings();
        SpendingAdvisor.IhqProgress ihqProgress = SpendingAdvisor.ihqProgress(allMembers,
                settings.getIhqBillAmount(), settings.getIhqStartDate());
        model.addAttribute("ihq", ihqProgress);
        model.addAttribute("duesSummary", duesSummary(allMembers, ihqProgress, LocalDate.now()));
        model.addAttribute("monthlyInflow", monthlyInflow);
        model.addAttribute("totalOwed", totalOwed);
        model.addAttribute("totalPastDue", totalPastDue);
        model.addAttribute("collectionRate", collectionRate);

        model.addAttribute("paidCount", paidCount);
        model.addAttribute("planCount", planCount);
        model.addAttribute("delinquentCount", delinquentCount);
        List<AttentionItem> attentionItems = dashboardAttention(allMembers, sort);
        model.addAttribute("attentionItems", attentionItems);
        model.addAttribute("attentionCount", attentionItems.size());
        List<StatusRecipientGroup> statusRecipientGroups = List.of("Delinquent", "Payment Plan", "Paid in Full").stream()
                .map(status -> new StatusRecipientGroup(status, allMembers.stream()
                        .filter(member -> status.equals(member.getStatus()) && !member.isMuteReminders()).count()))
                .toList();
        model.addAttribute("statusRecipientGroups", statusRecipientGroups);

        AppSettings dashSettings = store.getSettings();
        List<DashStat> catalog = dashStatCatalog(allMembers, dashSettings);
        Map<String, DashStat> byKey = new LinkedHashMap<>();
        catalog.forEach(s -> byKey.put(s.key(), s));
        List<String> chosen = dashSettings.getDashboardStats();
        List<DashStat> boxes = new ArrayList<>();
        for (int slot = 0; slot < DEFAULT_DASH_STATS.size(); slot++) {
            String key = slot < chosen.size() ? chosen.get(slot) : DEFAULT_DASH_STATS.get(slot);
            boxes.add(byKey.getOrDefault(key, byKey.get(DEFAULT_DASH_STATS.get(slot))));
        }
        Map<String, List<DashStat>> catalogByGroup = new LinkedHashMap<>();
        catalog.forEach(s -> catalogByGroup.computeIfAbsent(s.group(), k -> new ArrayList<>()).add(s));
        model.addAttribute("statBoxes", boxes);
        model.addAttribute("statCatalog", catalogByGroup);
        return "index";
    }

    /**
     * Plain-text roster summary for the Dues page's "Copy summary" button: collected, outstanding, past due,
     * collection rate (same formula as the dashboard box), IHQ progress, and every past-due member.
     */
    String duesSummary(List<Member> members, SpendingAdvisor.IhqProgress ihq, LocalDate today) {
        double collected = Money.cents(members.stream().mapToDouble(Member::getPaidThisMonth).sum());
        double outstanding = Money.cents(members.stream().mapToDouble(Member::getTotalOwed).sum());
        long owing = members.stream().filter(member -> Money.positive(member.getTotalOwed())).count();
        List<Member> pastDue = members.stream().filter(member -> Money.positive(member.getPastDueBalance()))
                .sorted(java.util.Comparator.comparingDouble(Member::getPastDueBalance).reversed()).toList();
        double pastDueTotal = Money.cents(pastDue.stream().mapToDouble(Member::getPastDueBalance).sum());
        double pool = collected + outstanding;
        StringBuilder text = new StringBuilder("DuesDesk dues summary (")
                .append(today.format(DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US))).append(")\n")
                .append("Collected this month: ").append(money(collected)).append('\n')
                .append("Outstanding balance: ").append(money(outstanding)).append(" across ").append(owing)
                .append(owing == 1 ? " member\n" : " members\n")
                .append("Past due: ").append(money(pastDueTotal)).append(" from ").append(pastDue.size())
                .append(pastDue.size() == 1 ? " member\n" : " members\n")
                .append("Collection rate: ").append(percent(pool > 0 ? collected / pool * 100 : 100)).append('\n');
        if (ihq.bill() > 0) {
            text.append("IHQ bill: ").append(money(ihq.paidOff())).append(" of ").append(money(ihq.bill()))
                    .append(" paid (").append(percent(ihq.percent())).append(")\n");
        }
        text.append('\n').append(pastDue.isEmpty() ? "No past-due balances." : "Past-due members:");
        pastDue.forEach(member -> text.append("\n- ").append(member.getName()).append(": ").append(money(member.getPastDueBalance())));
        return text.toString();
    }

    @GetMapping("/numbers")
    public String numbers(@RequestParam(name = "expense", required = false) String expenseInput,
                          @RequestParam(name = "purpose", defaultValue = "") String purpose,
                          @RequestParam(name = "urgency", defaultValue = "routine") String urgency,
                          Model model) {
        Double expense = null;
        String expenseParseError = null;
        if (expenseInput != null && !expenseInput.isBlank()) {
            try {
                expense = Double.valueOf(NumberInputWebConfig.normalize(expenseInput));
            } catch (NumberFormatException e) {
                expenseParseError = "Enter a valid amount; use commas only as thousands separators (for example, 1,000.00).";
            }
        }
        populateNumbers(expense, purpose, urgency, model);
        if (expenseParseError != null) {
            model.addAttribute("expenseInput", expenseInput);
            model.addAttribute("expenseError", expenseParseError);
            model.asMap().remove("advice");
        }
        return "numbers";
    }

    private void populateNumbers(Double expense, String purpose, String urgency, Model model) {
        LocalDate today = LocalDate.now();
        store.processRecurringBills(today);
        FinancialOverviewService.FinancialSnapshot financialSnapshot =
                new FinancialOverviewService(store).snapshot(today);
        model.addAttribute("financialSnapshot", financialSnapshot);
        List<Member> members = store.getMembers();
        double recordedPayments = members.stream()
                .filter(member -> member.getPaymentHistory() != null)
                .flatMap(member -> member.getPaymentHistory().stream())
                .mapToDouble(PaymentRecord::getAmount)
                .sum();
        double outstanding = members.stream().mapToDouble(Member::getTotalOwed).sum();
        double pastDue = members.stream().mapToDouble(Member::getPastDueBalance).sum();
        double recordedCollectionRate = recordedPayments + outstanding > 0
                ? recordedPayments / (recordedPayments + outstanding) * 100
                : 0;
        model.addAttribute("metricGroups", metricGroups(members));
        model.addAttribute("expensePurpose", purpose);
        model.addAttribute("expenseUrgency", urgency);
        if (expense != null) {
            AppSettings settings = store.getSettings();
            double ihqRemaining = settings == null ? 0 : SpendingAdvisor.ihqProgress(members,
                    settings.getIhqBillAmount(), settings.getIhqStartDate()).remaining();
            model.addAttribute("expense", expense);
            if (Double.isFinite(expense) && expense > 0) {
                if (purpose.isBlank() || purpose.length() > 160) {
                    model.addAttribute("expenseError", "Describe the purchase in 1 to 160 characters.");
                } else if (!Set.of("routine", "soon", "urgent", "emergency").contains(urgency)) {
                    model.addAttribute("expenseError", "Choose a valid urgency level.");
                } else {
                    model.addAttribute("advice", SpendingAdvisor.evaluate(expense,
                            SpendingAdvisor.averageMonthlyIncome(members), recordedCollectionRate, pastDue,
                            ihqRemaining, financialSnapshot.spendableCash(), purpose.trim(), urgency));
                }
            } else {
                model.addAttribute("expenseError", "Enter an expense amount greater than zero.");
            }
        }
    }

    public String numbers(Model model) {
        return numbers((Double) null, "", "routine", model);
    }

    public String numbers(Double expense, Model model) {
        return numbers(expense, "", "routine", model);
    }

    public String numbers(Double expense, String purpose, String urgency, Model model) {
        populateNumbers(expense, purpose, urgency, model);
        return "numbers";
    }

    @GetMapping("/numbers/history")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> metricHistory(@RequestParam String metric,
                                                                   @RequestParam String start,
                                                                   @RequestParam String end) {
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(start);
            endDate = LocalDate.parse(end);
        } catch (DateTimeParseException e) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", "Choose valid dates."));
        }
        if (startDate.isAfter(endDate)) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(Map.of("error", "The start date must be on or before the end date."));
        }
        if (!Set.of("collection-velocity-run-rate", "net-collection-rate",
                "delinquency-default-rate", "average-dues-per-member-adpm").contains(metric)) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(Map.of("error", "This stat does not have historical data available."));
        }

        // The graph is worked out from the ledger's dated payments and charges, so it covers all the history the ledger
        // holds (imported data included). Daily snapshots saved by older versions only fill in when the ledger has no
        // dated entries to calculate from.
        List<MetricHistoryPoint> points = MetricHistoryCalculator.history(metric, store.getMembers(),
                        store.getArchivedMembers(), startDate, endDate, LocalDate.now()).stream()
                .map(point -> new MetricHistoryPoint(point.date(), point.value()))
                .toList();
        if (points.isEmpty()) {
            points = store.getMetricHistory().stream()
                    .filter(snapshot -> snapshot.date() != null
                            && snapshot.date().compareTo(startDate.toString()) >= 0
                            && snapshot.date().compareTo(endDate.toString()) <= 0)
                    .filter(snapshot -> snapshot.values().containsKey(metric))
                    .map(snapshot -> new MetricHistoryPoint(snapshot.date(), snapshot.values().get(metric)))
                    .sorted(java.util.Comparator.comparing(MetricHistoryPoint::date))
                    .toList();
        }
        return org.springframework.http.ResponseEntity.ok(new MetricHistoryResponse(metric, points));
    }

    @PostMapping("/ihq-bill")
    public synchronized String updateIhqBill(@RequestParam double amount,
                                             @RequestParam(defaultValue = "") String startDate,
                                             RedirectAttributes redirect) {
        if (!Double.isFinite(amount) || amount < 0) {
            redirect.addFlashAttribute("toast", "Enter an IHQ bill amount of zero or more.");
            return "redirect:/";
        }
        if (!startDate.isBlank()) {
            try {
                LocalDate.parse(startDate);
            } catch (DateTimeParseException e) {
                redirect.addFlashAttribute("toast", "Choose a valid semester start date.");
                return "redirect:/";
            }
        }
        synchronized (store) {
            store.getSettings().setIhqBillAmount(amount);
            store.getSettings().setIhqStartDate(startDate);
            store.save();
        }
        redirect.addFlashAttribute("toast", "IHQ semester bill updated.");
        return "redirect:/";
    }

    /**
     * The Cash Flow page. {@code period} picks a preset (see {@link FlowReportService#PRESETS}); with
     * {@code period=custom}, {@code from}/{@code to} give the range. The totals, table and export links follow it;
     * the pending-reconciliation queue always shows everything that still needs review.
     */
    @GetMapping("/numbers/flow")
    public String flow(@RequestParam(required = false) String period,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       Model model) {
        // Automatic bills that have come due are recorded first, so they show up in this timeline.
        store.processRecurringBills(LocalDate.now());
        FlowReportService.Period selected = FlowReportService.resolvePeriod(period, from, to, LocalDate.now());
        List<FlowReportService.FlowRow> allRows = flowReportService.allRows();
        List<FlowReportService.FlowRow> rows = flowReportService.filter(allRows, selected);
        FlowReportService.Totals totals = FlowReportService.totals(rows);
        model.addAttribute("rows", rows);
        model.addAttribute("period", selected);
        model.addAttribute("periodPresets", FlowReportService.PRESETS);
        model.addAttribute("hasAnyTransactions", !allRows.isEmpty());
        model.addAttribute("flowSummary", FlowReportService.summary(selected, rows));
        List<Member> roster = store.getMembers();
        List<ReconciliationRow> reconciliationRows = store.getFlowEntries().stream()
                .filter(entry -> "PENDING".equals(entry.getReconciliationStatus()) && entry.getAmount() > 0)
                .map(entry -> reconciliationRow(entry, roster))
                .toList();
        model.addAttribute("reconciliationRows", reconciliationRows);
        model.addAttribute("roster", roster);
        Map<String, String> recentPaymentsByMember = new LinkedHashMap<>();
        roster.forEach(member -> recentPaymentsByMember.put(member.getId(), recentPaymentSummary(member)));
        model.addAttribute("recentPaymentsByMember", recentPaymentsByMember);
        model.addAttribute("totalInflow", totals.inflow());
        model.addAttribute("totalOutflow", totals.outflow());
        model.addAttribute("net", totals.net());
        return "flow";
    }

    /** The period's table as CSV; {@code kind} and {@code q} carry the page's quick filter and search (optional). */
    @GetMapping("/numbers/flow/export.csv")
    public org.springframework.http.ResponseEntity<byte[]> exportFlowCsv(@RequestParam(required = false) String period,
                                                                       @RequestParam(required = false) String from,
                                                                       @RequestParam(required = false) String to,
                                                                       @RequestParam(required = false) String kind,
                                                                       @RequestParam(required = false) String q) {
        FlowReportService.Period selected = FlowReportService.resolvePeriod(period, from, to, LocalDate.now());
        return documentResponse(flowReportService.csv(selected, kind, q), "text/csv; charset=UTF-8",
                FlowReportService.fileName(selected, "csv", LocalDate.now()));
    }

    @GetMapping("/numbers/flow/export.pdf")
    public org.springframework.http.ResponseEntity<byte[]> exportFlowPdf(@RequestParam(required = false) String period,
                                                                       @RequestParam(required = false) String from,
                                                                       @RequestParam(required = false) String to,
                                                                       @RequestParam(required = false) String kind,
                                                                       @RequestParam(required = false) String q) throws Exception {
        FlowReportService.Period selected = FlowReportService.resolvePeriod(period, from, to, LocalDate.now());
        return documentResponse(flowReportService.pdf(selected, kind, q), "application/pdf",
                FlowReportService.fileName(selected, "pdf", LocalDate.now()));
    }

    /** PDF of a table exactly as the full-screen view shows it (posted by the page: headers plus the visible rows). */
    @PostMapping(value = "/table-export.pdf", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public org.springframework.http.ResponseEntity<?> exportTablePdf(@RequestBody TableExportService.TableData table) throws Exception {
        try {
            return documentResponse(tableExportService.pdf(table), "application/pdf",
                    TableExportService.fileName(table.title(), LocalDate.now()));
        } catch (IllegalArgumentException e) {
            return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public record ReconciliationRow(FlowEntry entry, Member suggestedMember, String matchConfidence,
                                    String recentPaymentSummary) {}

    private ReconciliationRow reconciliationRow(FlowEntry entry, List<Member> members) {
        Optional<Member> remembered = store.findMemberByCounterpartyAlias(entry.getCounterparty());
        if (remembered.isPresent()) {
            return new ReconciliationRow(entry, remembered.get(), "Saved payer alias — confirm match",
                    recentPaymentSummary(remembered.get()));
        }
        String counterparty = normalizeName(entry.getCounterparty());
        if (counterparty.isBlank()) return new ReconciliationRow(entry, null, "No match suggested", "No member selected");
        List<Member> exact = members.stream()
                .filter(member -> normalizeName(member.getName()).equals(counterparty))
                .toList();
        if (exact.size() == 1) return new ReconciliationRow(entry, exact.getFirst(), "Exact name match",
                recentPaymentSummary(exact.getFirst()));
        List<Member> fuzzy = members.stream().filter(member -> {
            String name = normalizeName(member.getName());
            return name.length() >= 4 && (counterparty.contains(name) || name.contains(counterparty));
        }).toList();
        if (fuzzy.size() == 1) return new ReconciliationRow(entry, fuzzy.getFirst(), "Possible name match — review",
                recentPaymentSummary(fuzzy.getFirst()));
        return new ReconciliationRow(entry, null, "No unique match — select manually", "No member selected");
    }

    private String recentPaymentSummary(Member member) {
        if (member.getPaymentHistory() == null || member.getPaymentHistory().isEmpty()) return "No payments recorded";
        PaymentRecord recent = member.getPaymentHistory().stream()
                .max(java.util.Comparator.comparing(payment -> payment.getDate() == null ? "" : payment.getDate()))
                .orElseThrow();
        return (recent.getDate() == null ? "Date unavailable" : recent.getDate())
                + " · " + money(recent.getAmount());
    }

    public record MemberAccountSummary(String nextDueDate, double nextDueAmount, String nextDueStatus,
                                      int installmentsPaid, int installmentCount,
                                      double installmentPaidAmount, double installmentTotalAmount) {}

    /** One roster (Members, Inactive or Alumni) as the ledger pages it. */
    public record RosterPage(String group, int currentPage, int totalPages, int total, int firstShown, int lastShown) {
        public String getLabel() { return Member.MEMBER.equals(group) ? "Members" : group; }
        public String getParam() { return group.toLowerCase(java.util.Locale.ROOT); }
        public boolean isHasPrevious() { return currentPage > 1; }
        public boolean isHasNext() { return currentPage < totalPages; }
        /** "42 members on the roster", "1 inactive member on the roster", "5 alumni on the roster". */
        public String getSummary() {
            String noun = Member.ALUMNI.equals(group) ? "alumni"
                    : Member.INACTIVE.equals(group) ? (total == 1 ? "inactive member" : "inactive members")
                    : (total == 1 ? "member" : "members");
            return total + " " + noun + " on the roster";
        }
    }

    public record AttentionItem(String title, String detail, String href, String status,
                                String memberId, int urgency, LocalDate dueDate, double amount) {}
    public record StatusRecipientGroup(String status, long recipientCount) {}

    private MemberAccountSummary accountSummary(Member member) {
        LocalDate today = LocalDate.now();
        LocalDate nextDue = null;
        double dueAmount = 0;
        if (member.getCharges() != null) {
            for (ChargeRecord charge : member.getCharges()) {
                LocalDate date = parseDate(charge.getDueDate());
                if (date != null && charge.getRemainingAmount() > 0.005
                        && (nextDue == null || date.isBefore(nextDue))) {
                    nextDue = date;
                    dueAmount = charge.getRemainingAmount();
                }
            }
        }
        int installmentsPaid = 0;
        int installmentCount = 0;
        double installmentPaidAmount = 0;
        double installmentTotalAmount = 0;
        if (member.getInstallments() != null) {
            for (PaymentInstallment installment : member.getInstallments()) {
                installmentCount++;
                if (installment.isPaid()) installmentsPaid++;
                installmentPaidAmount += installment.getPaidAmount();
                installmentTotalAmount += installment.getAmount();
                LocalDate date = parseDate(installment.getDueDate());
                if (date != null && installment.getRemainingAmount() > 0.005
                        && (nextDue == null || date.isBefore(nextDue))) {
                    nextDue = date;
                    dueAmount = installment.getRemainingAmount();
                }
            }
        }
        String status = nextDue == null ? "No scheduled due date"
                : nextDue.isBefore(today) ? "Past due"
                : nextDue.isEqual(today) ? "Due today" : "Upcoming";
        return new MemberAccountSummary(nextDue == null ? null : nextDue.toString(), dueAmount,
                status, installmentsPaid, installmentCount, installmentPaidAmount, installmentTotalAmount);
    }

    private List<AttentionItem> dashboardAttention(List<Member> members, String sort) {
        List<AttentionItem> items = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Member member : members) {
            String href = memberPageHref(members, member, sort);
            LocalDate dueDate = null;
            if (member.getPastDueBalance() > 0.005) {
                items.add(new AttentionItem(member.getName() + " has a past-due balance",
                        money(member.getPastDueBalance()) + " past due", href, "Past due",
                        member.getId(), 0, null, member.getPastDueBalance()));
            } else {
                MemberAccountSummary summary = accountSummary(member);
                dueDate = parseDate(summary.nextDueDate());
                if (dueDate != null && !dueDate.isAfter(today.plusDays(7))) {
                    boolean overdue = dueDate.isBefore(today);
                    int urgency = overdue ? 1 : dueDate.isEqual(today) ? 2 : 3;
                    String title = overdue
                            ? member.getName() + " has an overdue scheduled payment"
                            : member.getName() + " has an upcoming payment";
                    items.add(new AttentionItem(title,
                            money(summary.nextDueAmount()) + " due " + summary.nextDueDate(),
                            href, summary.nextDueStatus(), member.getId(), urgency, dueDate, summary.nextDueAmount()));
                }
            }
        }
        long pendingReconciliation = store.getFlowEntries().stream()
                .filter(entry -> "PENDING".equals(entry.getReconciliationStatus()) && entry.getAmount() > 0).count();
        if (pendingReconciliation > 0) {
            items.add(new AttentionItem("Transactions need review", pendingReconciliation + " pending reconciliation",
                    "/numbers/flow", "Needs review", null, 4, null, pendingReconciliation));
        }
        List<RecurringBill.Reminder> billReminders = BillCalendar.reminders(store.getRecurringBills(), today);
        if (!billReminders.isEmpty()) {
            long overdue = billReminders.stream().filter(RecurringBill.Reminder::isOverdue).count();
            double billTotal = billReminders.stream().mapToDouble(item -> item.bill().getAmount()).sum();
            items.add(new AttentionItem(overdue > 0 ? "Bills are overdue" : "Bills are coming due",
                    billReminders.size() + (billReminders.size() == 1 ? " bill" : " bills") + " · " + money(billTotal)
                            + (overdue > 0 ? " · " + overdue + " overdue" : ""),
                    "/calendar#billReminders", overdue > 0 ? "Overdue" : "Due soon", null, overdue > 0 ? 1 : 3,
                    billReminders.getFirst().dueDate(), billTotal));
        }
        if (reminderSequence != null) {
            // Accounts the reminder sequence flagged come first: they are waiting on an officer decision.
            for (Member flagged : reminderSequence.flaggedMembers()) {
                items.add(new AttentionItem(flagged.getName() + " is flagged for officer review",
                        flagged.getOfficerReviewReason() == null ? "Flagged by the reminder sequence" : flagged.getOfficerReviewReason(),
                        memberPageHref(members, flagged, sort), "Officer review", null, 0, null, flagged.getPastDueBalance()));
            }
            int steps = reminderSequence.preview(today).size();
            if (steps > 0) {
                items.add(new AttentionItem("Reminder steps due today", steps + (steps == 1 ? " member is" : " members are")
                        + " due for the next reminder step", "/#smsReminders", "Needs review", null, 5, null, steps));
            }
        } else {
            int reminders = smsService.reminderPreview().size();
            if (reminders > 0) {
                items.add(new AttentionItem("Reminders ready to review", reminders + " eligible member"
                        + (reminders == 1 ? "" : "s"), "/#smsReminders", "Needs review",
                        null, 5, null, reminders));
            }
        }
        Map<String, String> snoozes = store.getSettings().getAttentionSnoozes();
        return items.stream()
                .filter(item -> item.memberId() == null || !isSnoozed(item.memberId(), snoozes, today))
                .sorted(java.util.Comparator.comparingInt(AttentionItem::urgency)
                        .thenComparing(AttentionItem::dueDate,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(AttentionItem::amount, java.util.Comparator.reverseOrder()))
                .limit(8)
                .toList();
    }

    private boolean isSnoozed(String memberId, Map<String, String> snoozes, LocalDate today) {
        LocalDate until = parseDate(snoozes.get(memberId));
        return until != null && until.isAfter(today);
    }

    private String memberPageHref(List<Member> allMembers, Member member, String sort) {
        // The member is on the roster for their status (Members, Inactive or Alumni), on the page that holds them.
        List<Member> members = allMembers.stream()
                .filter(item -> member.getMembershipStatus().equals(item.getMembershipStatus())).toList();
        int index = java.util.stream.IntStream.range(0, members.size())
                .filter(i -> java.util.Objects.equals(members.get(i).getId(), member.getId()))
                .findFirst().orElse(0);
        int page = (index / 25) + 1;
        String group = Member.MEMBER.equals(member.getMembershipStatus()) ? ""
                : "&group=" + member.getMembershipStatus().toLowerCase(java.util.Locale.ROOT);
        return "/?page=" + page + group
                + (sort == null || sort.isBlank() ? "" : "&sort=" + sort)
                + "#member-" + member.getId();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    @PostMapping("/attention/{memberId}/snooze")
    public synchronized String snoozeAttention(@PathVariable String memberId, @RequestParam int days,
                                               RedirectAttributes redirect) {
        if (!Set.of(1, 7, 30).contains(days) || store.findMemberById(memberId).isEmpty()) {
            redirect.addFlashAttribute("toast", "That account could not be snoozed.");
            return "redirect:/";
        }
        synchronized (store) {
            Map<String, String> snoozes = new LinkedHashMap<>(store.getSettings().getAttentionSnoozes());
            snoozes.put(memberId, LocalDate.now().plusDays(days).toString());
            store.getSettings().setAttentionSnoozes(snoozes);
            store.save();
        }
        redirect.addFlashAttribute("toast", "Account attention hidden for " + days + " days.");
        return "redirect:/";
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @PostMapping("/reconciliation/{id}/confirm")
    public synchronized String confirmReconciliation(@PathVariable String id, @RequestParam String memberId,
                                                      @RequestParam(defaultValue = "false") boolean rememberAlias,
                                                      RedirectAttributes redirect) {
        String payer = store.getFlowEntries().stream().filter(entry -> entry.getId().equals(id))
                .map(FlowEntry::getCounterparty).findFirst().orElse("");
        boolean reconciled = store.confirmReconciliation(id, memberId, rememberAlias);
        String memberName = store.findMemberById(memberId).map(Member::getName).orElse("the member");
        redirect.addFlashAttribute("toast", !reconciled
                ? "Transaction could not be reconciled. It may already be processed or the selected member may not exist."
                : rememberAlias && payer != null && !payer.isBlank() && !normalizeName(payer).equals(normalizeName(memberName))
                    ? "Transaction matched and payment recorded. \"" + payer.trim() + "\" will be suggested for " + memberName + " from now on."
                    : "Transaction matched and payment recorded.");
        return "redirect:/numbers/flow";
    }

    @PostMapping("/reconciliation/{id}/ignore")
    public synchronized String ignoreReconciliation(@PathVariable String id, RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.ignoreReconciliation(id)
                ? "Transaction moved out of the pending reconciliation queue."
                : "Transaction could not be dismissed.");
        return "redirect:/numbers/flow";
    }

    @PostMapping("/export-data")
    @ResponseBody
    public org.springframework.http.ResponseEntity<byte[]> exportData(@RequestParam String password) throws Exception {
        byte[] body;
        try {
            body = backupEncryptionService.encrypt(store.exportData(), password.toCharArray());
        } catch (IllegalArgumentException e) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body(e.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + encryptedExportFileName() + "\"")
                .header("Cache-Control", "no-store")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    public static String exportFileName() {
        return "DuesDesk-backup-" + LocalDate.now() + ".json";
    }

    public static String encryptedExportFileName() {
        return "DuesDesk-backup-" + LocalDate.now() + ".ddbk";
    }

    @PostMapping("/import-data")
    public synchronized String importData(@RequestParam("file") MultipartFile file,
                                          @RequestParam(name = "password", required = false) String password,
                                          RedirectAttributes redirect) {
        if (file.isEmpty()) {
            redirect.addFlashAttribute("toast", "Please choose a backup file to import.");
            return "redirect:/upload-docs";
        }
        try {
            byte[] fileBytes = file.getBytes();
            byte[] backupBytes;
            if (backupEncryptionService.isEncrypted(fileBytes)) {
                if (password == null || password.isBlank()) {
                    throw new IllegalArgumentException("Enter the password used to encrypt this backup.");
                }
                backupBytes = backupEncryptionService.decrypt(fileBytes, password.toCharArray());
            } else {
                backupBytes = fileBytes;
            }
            store.importData(backupBytes);
            redirect.addFlashAttribute("toast", "Backup imported. All data was replaced with the contents of that file.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", "Import failed: " + e.getMessage());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class).error("Backup import failed", e);
            redirect.addFlashAttribute("toast", "Import failed. The file could not be read; your data was not changed.");
        }
        return "redirect:/upload-docs";
    }

    @GetMapping("/upload-docs")
    public String uploadDocs() {
        return "upload";
    }

    @PostMapping("/numbers/flow/upload-venmo")
    public synchronized String uploadVenmo(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        if (file.isEmpty()) {
            redirect.addFlashAttribute("toast", "Please select a Venmo CSV file to upload.");
            return "redirect:/upload-docs";
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            redirect.addFlashAttribute("toast", "CSV uploads are limited to 5 MB.");
            return "redirect:/upload-docs";
        }
        try {
            int[] r = store.importVenmoCsv(file);
            redirect.addFlashAttribute("toast", "Imported " + r[0] + " Venmo transactions"
                    + (r[1] > 0 ? " (" + r[1] + " already imported)" : "")
                    + (r[2] > 0 ? ", " + r[2] + " skipped (transfers/incomplete)" : "") + ".");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", "Venmo import failed: " + e.getMessage());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class).error("Venmo import failed", e);
            redirect.addFlashAttribute("toast", "Venmo import failed. Check the file and application log, then try again.");
        }
        return "redirect:/upload-docs";
    }

    @PostMapping("/numbers/flow/upload-bank")
    public synchronized String uploadBank(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        if (file.isEmpty()) {
            redirect.addFlashAttribute("toast", "Please select a bank CSV file to upload.");
            return "redirect:/upload-docs";
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            redirect.addFlashAttribute("toast", "CSV uploads are limited to 5 MB.");
            return "redirect:/upload-docs";
        }
        try {
            int[] result = store.importBankCsv(file);
            redirect.addFlashAttribute("toast", "Imported " + result[0] + " bank transactions"
                    + (result[1] > 0 ? " (" + result[1] + " duplicates ignored)" : "")
                    + ". Review incoming transactions in Cash Flow before matching them.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", "Bank CSV import failed: " + e.getMessage());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class).error("Bank CSV import failed", e);
            redirect.addFlashAttribute("toast", "Bank CSV import failed. Check the format and application log.");
        }
        return "redirect:/upload-docs";
    }

    @GetMapping("/calendar")
    public String calendar(@RequestParam(required = false) String month,
                           @RequestParam(required = false) String date,
                           Model model) {
        YearMonth selectedMonth;
        try {
            selectedMonth = month == null ? YearMonth.now() : YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            selectedMonth = YearMonth.now();
        }
        LocalDate selectedDate;
        try {
            selectedDate = date == null
                    ? (selectedMonth.equals(YearMonth.now()) ? LocalDate.now() : selectedMonth.atDay(1))
                    : LocalDate.parse(date);
            if (!YearMonth.from(selectedDate).equals(selectedMonth)) selectedDate = selectedMonth.atDay(1);
        } catch (java.time.format.DateTimeParseException e) {
            selectedDate = selectedMonth.atDay(1);
        }

        // Automatic bills that have come due are recorded as outflows before anything is drawn.
        store.processRecurringBills(LocalDate.now());
        List<RecurringBill> bills = store.getRecurringBills();
        Map<String, List<Map<String, Object>>> billsDue = BillCalendar.dueByDay(bills, selectedMonth, LocalDate.now());

        Map<LocalDate, List<CalendarPayment>> paymentsByDate = new LinkedHashMap<>();
        List<Member> paymentHistoryMembers = new ArrayList<>(store.getMembers());
        paymentHistoryMembers.addAll(store.getArchivedMembers()); // history survives a semester rollover
        for (Member member : paymentHistoryMembers) {
            if (member.getPaymentHistory() == null) continue;
            for (PaymentRecord payment : member.getPaymentHistory()) {
                if (payment.getDate() == null || payment.getDate().length() < 10) continue;
                try {
                    LocalDate paymentDate = LocalDate.parse(payment.getDate().substring(0, 10));
                    if (YearMonth.from(paymentDate).equals(selectedMonth)) {
                        paymentsByDate.computeIfAbsent(paymentDate, ignored -> new ArrayList<>())
                                .add(new CalendarPayment(member.getName(), payment));
                    }
                } catch (DateTimeParseException ignored) {
                    // Older imports may have no usable day-level date.
                }
            }
        }

        for (FlowEntry entry : store.getFlowEntries()) {
            if ("RECONCILED".equals(entry.getReconciliationStatus())) continue;
            if (entry.getDate() == null || entry.getDate().length() < 10) continue;
            try {
                LocalDate entryDate = LocalDate.parse(entry.getDate().substring(0, 10));
                if (YearMonth.from(entryDate).equals(selectedMonth)) {
                    boolean categorized = entry.getCategory() != null && !entry.getCategory().isBlank();
                    boolean hasParty = entry.getCounterparty() != null && !entry.getCounterparty().isBlank();
                    paymentsByDate.computeIfAbsent(entryDate, ignored -> new ArrayList<>())
                            .add(new CalendarPayment(
                                    hasParty ? entry.getCounterparty() : (categorized ? entry.getCategory() : entry.getSource()),
                                    new PaymentRecord(entryDate.toString(), entry.getAmount(), entry.getDescription()),
                                    categorized ? entry.getCategory()
                                            : (entry.getSource() == null || entry.getSource().isBlank() ? "Imported transaction" : entry.getSource())));
                }
            } catch (DateTimeParseException ignored) {
                // Skip rows without a usable date.
            }
        }

        List<CalendarDay> days = new ArrayList<>();
        int leadingBlanks = selectedMonth.atDay(1).getDayOfWeek().getValue() % 7;
        for (int i = 0; i < leadingBlanks; i++) days.add(new CalendarDay(null, 0, 0));
        for (int day = 1; day <= selectedMonth.lengthOfMonth(); day++) {
            LocalDate currentDate = selectedMonth.atDay(day);
            List<CalendarPayment> payments = paymentsByDate.getOrDefault(currentDate, List.of());
            List<CalendarPayment> in = payments.stream().filter(item -> item.getPayment().getAmount() >= 0).toList();
            List<CalendarPayment> out = payments.stream().filter(item -> item.getPayment().getAmount() < 0).toList();
            days.add(new CalendarDay(currentDate.toString(), in.size(),
                    in.stream().mapToDouble(item -> item.getPayment().getAmount()).sum(),
                    out.size(), -out.stream().mapToDouble(item -> item.getPayment().getAmount()).sum())
                    .withBillCount(billsDue.getOrDefault(currentDate.toString(), List.of()).size()));
        }
        while (days.size() % 7 != 0) days.add(new CalendarDay(null, 0, 0));

        // Every day's transactions for the hover preview and full-day view (rendered client-side).
        Map<String, List<Map<String, Object>>> dayDetails = new LinkedHashMap<>();
        paymentsByDate.forEach((day, entries) -> dayDetails.put(day.toString(), entries.stream()
                .map(item -> Map.<String, Object>of(
                        "name", item.getMemberName() == null ? "" : item.getMemberName(),
                        "description", item.getPayment().getDescription() == null ? "" : item.getPayment().getDescription(),
                        "amount", item.getPayment().getAmount(),
                        "source", item.getSource() == null ? "" : item.getSource()))
                .toList()));
        model.addAttribute("dayDetails", dayDetails);
        model.addAttribute("billDetails", billsDue);
        model.addAttribute("billEditData", BillCalendar.editData(bills));
        model.addAttribute("billRows", BillCalendar.rows(bills, LocalDate.now()));
        model.addAttribute("billReminders", BillCalendar.reminders(bills, LocalDate.now()));
        model.addAttribute("billFrequencies", RecurringBill.FREQUENCY_ORDER.stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, RecurringBill.FREQUENCIES::get, (a, b) -> a, LinkedHashMap::new)));
        model.addAttribute("billSelectedDay", billsDue.getOrDefault(selectedDate.toString(), List.of()));

        List<CalendarPayment> selectedPayments = paymentsByDate.getOrDefault(selectedDate, List.of());
        model.addAttribute("month", selectedMonth.toString());
        model.addAttribute("monthLabel", selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));
        model.addAttribute("previousMonth", selectedMonth.minusMonths(1));
        model.addAttribute("nextMonth", selectedMonth.plusMonths(1));
        model.addAttribute("days", days);
        model.addAttribute("selectedDate", selectedDate.toString());
        model.addAttribute("selectedPayments", selectedPayments);
        model.addAttribute("selectedTotal", selectedPayments.stream()
                .mapToDouble(item -> item.getPayment().getAmount()).filter(a -> a >= 0).sum());
        model.addAttribute("selectedOutflowTotal", -selectedPayments.stream()
                .mapToDouble(item -> item.getPayment().getAmount()).filter(a -> a < 0).sum());
        model.addAttribute("members", store.getMembers());
        model.addAttribute("today", LocalDate.now().toString());
        model.addAttribute("monthPaymentCount", paymentsByDate.values().stream()
                .flatMap(List::stream).filter(item -> item.getPayment().getAmount() >= 0).mapToInt(item -> 1).sum());
        model.addAttribute("monthOutflowCount", paymentsByDate.values().stream()
                .flatMap(List::stream).filter(item -> item.getPayment().getAmount() < 0).mapToInt(item -> 1).sum());
        model.addAttribute("paymentRequestId", UUID.randomUUID().toString());
        model.addAttribute("inflowCategories", TransactionCategories.INFLOW);
        model.addAttribute("outflowCategories", TransactionCategories.OUTFLOW);
        model.addAttribute("memberDuesCategory", TransactionCategories.MEMBER_DUES);
        return "calendar";
    }

    private List<MetricGroup> metricGroups(List<Member> members) {
        double recordedPayments = members.stream()
                .filter(member -> member.getPaymentHistory() != null)
                .flatMap(member -> member.getPaymentHistory().stream())
                .mapToDouble(PaymentRecord::getAmount)
                .sum();
        double outstanding = members.stream().mapToDouble(Member::getTotalOwed).sum();
        double pastDue = members.stream().mapToDouble(Member::getPastDueBalance).sum();
        long activeMembers = members.size();
        double recordedCollectionRate = recordedPayments + outstanding > 0
                ? recordedPayments / (recordedPayments + outstanding) * 100
                : 0;
        double delinquencyRate = recordedPayments + outstanding > 0
                ? pastDue / (recordedPayments + outstanding) * 100
                : 0;
        double averageDues = activeMembers > 0 ? recordedPayments / activeMembers : 0;
        double monthlyInflow = members.stream().mapToDouble(Member::getPaidThisMonth).sum();
        double collectionVelocity = monthlyInflow / LocalDate.now().getDayOfMonth();

        return List.of(
                new MetricGroup("Cash Flow & Expected Return", List.of(
                        metric("Gross Expected Weekly/Monthly Return", "—", "Total scheduled installments due in a selected week or month; installment schedules are not tracked yet."),
                        metric("Risk-Adjusted Expected Return", "—", "Gross expected return × historical on-time rate + expected late recoveries; due schedules and recovery forecasts are not tracked yet."),
                        metric("Collection Velocity (Run Rate)", money(collectionVelocity) + " / day", "Current-month collected amount divided by elapsed calendar days."),
                        metric("Billing Cycle Burn-Down Rate", "—", "Remaining unpaid term balance ÷ weeks left in term; term dates are not tracked yet."),
                        metric("Monthly / Term Recurring Revenue (MRR / TRR)", "—", "Contracted recurring plan revenue; plan schedules are not tracked yet."),
                        metric("Cumulative Realization Curve (% by Week n)", "—", "Share of term dues collected by each week; term-level history is not tracked yet.")
                )),
                new MetricGroup("Collection Health & Delinquency", List.of(
                        metric("Net Collection Rate", percent(recordedCollectionRate), "Recorded payment history ÷ (recorded payment history + current outstanding balance); target 92%–98%. Historical payment records may be incomplete."),
                        metric("On-Time Payment Rate", "—", "Invoices paid on or before their due date; invoice due dates are not tracked yet."),
                        metric("Delinquency / Default Rate", percent(delinquencyRate), money(pastDue) + " past due out of recorded collections plus current outstanding; historical payment records may be incomplete."),
                        metric("AR Aging Buckets", "—", "Current, 1–30, 31–60, and 61–90+ days past due; charge due dates are needed to age balances."),
                        metric("Days Sales Outstanding (DSO)", "—", "Average days from invoice issue to payment; invoice dates are not tracked yet."),
                        metric("Payment Plan Compliance Rate", "—", "Plan members with no missed or bounced installments; installment schedules are not tracked yet.")
                )),
                new MetricGroup("Member Value & Roster Economics", List.of(
                        metric("Average Dues Per Member (ADPM)", money(averageDues), "Recorded dues collected ÷ current roster members (" + activeMembers + ")."),
                        metric("Net Contribution Margin Per Member", "—", "ADPM less fixed national and insurance per-capita fees; fee inputs are not tracked yet."),
                        metric("Breakeven Headcount", "—", "Fixed expenses ÷ net contribution margin per member; expense inputs are not tracked yet."),
                        metric("Member Lifetime Value (LTV)", "—", "Average dues per term × average active terms; term and exit history are not tracked yet."),
                        metric("Financial Churn Rate", "—", "Members and billed dollars lost term over term; roster term history is not tracked yet.")
                )),
                new MetricGroup("Operational Leakage & Recovery", List.of(
                        metric("Payment Processing Fee Drag", "—", "Merchant fees ÷ gross dues; processor fee data is not tracked yet."),
                        metric("Discount & Waiver Leakage", "—", "Sticker-price dues less net billed dues; dues and waiver detail are not tracked yet."),
                        metric("Reminder / Dunning Recovery Rate", "—", "Overdue balances paid within 48 hours of a reminder; reminder-linked collections are not tracked yet."),
                        metric("Late Fee Realization Rate", "—", "Late fees collected ÷ late fees assessed; late fee detail is not tracked yet."),
                        metric("Bad Debt Write-Off Ratio", "—", "Dues written off as uncollectible ÷ billed dues; write-offs are not tracked yet.")
                ))
        );
    }

    /** One value that can be shown in a dashboard box. */
    public record DashStat(String key, String group, String name, String value, String sub,
                           Double progress, String icon, String tone) {}

    private static final List<String> DEFAULT_DASH_STATS = List.of("inflow", "balance", "pastdue", "rate", "ihq");

    private static String slug(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private List<DashStat> dashStatCatalog(List<Member> all, AppSettings settings) {
        double inflow = all.stream().mapToDouble(Member::getPaidThisMonth).sum();
        double owed = all.stream().mapToDouble(Member::getTotalOwed).sum();
        double pastDue = all.stream().mapToDouble(Member::getPastDueBalance).sum();
        double pool = inflow + owed;
        double rate = pool > 0 ? inflow / pool * 100.0 : 100.0;
        long paid = all.stream().filter(m -> "Paid in Full".equalsIgnoreCase(m.getStatus())).count();
        long plan = all.stream().filter(m -> "Payment Plan".equalsIgnoreCase(m.getStatus())).count();
        long late = all.stream().filter(m -> "Delinquent".equalsIgnoreCase(m.getStatus())).count();
        SpendingAdvisor.IhqProgress ihq = SpendingAdvisor.ihqProgress(all, settings.getIhqBillAmount(), settings.getIhqStartDate());

        List<DashStat> catalog = new ArrayList<>();
        String g = "Dues Overview";
        catalog.add(new DashStat("inflow", g, "Monthly Cash Inflow", money(inflow), "Collected payments this month", null, "bi-arrow-down-left-circle", "emerald"));
        catalog.add(new DashStat("balance", g, "Total Semester Balance", money(owed), "Outstanding across active plans", null, "bi-wallet2", "blue"));
        catalog.add(new DashStat("pastdue", g, "Actively Past Due", money(pastDue), late + " delinquent members", null, "bi-exclamation-triangle", "rose"));
        catalog.add(new DashStat("rate", g, "Collection Rate", percent(rate), paid + " Paid · " + plan + " Plan · " + late + " Late",
                Math.max(0, Math.min(100, rate)), "bi-pie-chart", "slate"));
        catalog.add(new DashStat("ihq", g, "% Of IHQ Bill Paid",
                ihq.bill() > 0 ? percent(ihq.percent()) : "—",
                ihq.bill() > 0 ? money(ihq.paidOff()) + " of " + money(ihq.bill()) : "Set the semester bill to start tracking",
                ihq.bill() > 0 ? ihq.percent() : null, "bi-pencil", "blue"));
        catalog.add(new DashStat("member-count", g, "Members on Roster", Long.toString(all.size()),
                "Current roster size", null, "bi-people", "slate"));
        // Inactive and Alumni dues are tracked apart from active Members: for each, the collection rate (the same
        // formula as the overall rate, over that group only) and the dues still unpaid.
        for (String roster : List.of(Member.INACTIVE, Member.ALUMNI)) {
            List<Member> people = all.stream().filter(m -> roster.equals(m.getMembershipStatus())).toList();
            double groupInflow = people.stream().mapToDouble(Member::getPaidThisMonth).sum();
            double groupOwed = people.stream().mapToDouble(Member::getTotalOwed).sum();
            double groupPool = groupInflow + groupOwed;
            double groupRate = groupPool > 0 ? groupInflow / groupPool * 100.0 : 100.0;
            long owing = people.stream().filter(m -> Money.positive(m.getTotalOwed())).count();
            String key = roster.toLowerCase(java.util.Locale.ROOT);
            String noun = Member.ALUMNI.equals(roster) ? "alumni" : "inactive members";
            String groupName = "Inactive & Alumni Dues";
            catalog.add(new DashStat(key + "-rate", groupName, roster + " Collection Rate", percent(groupRate),
                    people.isEmpty() ? "No " + noun + " on the roster" : money(groupInflow) + " collected this month · " + people.size() + " " + noun,
                    people.isEmpty() ? null : Math.max(0, Math.min(100, groupRate)), "bi-pie-chart", "slate"));
            catalog.add(new DashStat(key + "-unpaid", groupName, roster + " Dues Unpaid", money(groupOwed),
                    people.isEmpty() ? "No " + noun + " on the roster" : owing + " of " + people.size() + " " + noun + " still owe",
                    null, "bi-wallet2", Money.positive(groupOwed) ? "rose" : "emerald"));
        }
        for (MetricGroup group : metricGroups(all)) {
            for (Metric m : group.getMetrics()) {
                boolean tracked = !"—".equals(m.getValue());
                catalog.add(new DashStat(slug(m.getName()), group.getName(), m.getName(), m.getValue(),
                        tracked ? m.getDescription() : "Not tracked yet", null, "bi-graph-up", "slate"));
            }
        }
        return catalog;
    }

    @PostMapping("/dashboard-stats")
    public synchronized String updateDashboardStat(@RequestParam int slot, @RequestParam String stat,
                                                   RedirectAttributes redirect) {
        AppSettings settings = store.getSettings();
        boolean known = dashStatCatalog(store.getMembers(), settings).stream().anyMatch(s -> s.key().equals(stat));
        if (slot < 0 || slot >= DEFAULT_DASH_STATS.size() || !known) {
            redirect.addFlashAttribute("toast", "That stat could not be selected.");
            return "redirect:/";
        }
        synchronized (store) {
            List<String> keys = new ArrayList<>(settings.getDashboardStats());
            while (keys.size() < DEFAULT_DASH_STATS.size()) keys.add(DEFAULT_DASH_STATS.get(keys.size()));
            keys.set(slot, stat);
            settings.setDashboardStats(keys);
            store.save();
        }
        return "redirect:/";
    }

    private Metric metric(String name, String value, String description) {
        return new Metric(slug(name), name, value, description);
    }

    private String money(double amount) {
        return String.format(java.util.Locale.US, "$%,.2f", amount);
    }

    private String percent(double amount) {
        return String.format(java.util.Locale.US, "%.1f%%", amount);
    }

    public static class Metric {
        private final String key;
        private final String name;
        private final String value;
        private final String description;

        public Metric(String key, String name, String value, String description) {
            this.key = key;
            this.name = name;
            this.value = value;
            this.description = description;
        }
        public String getKey() { return key; }
        public String getName() { return name; }
        public String getValue() { return value; }
        public String getDescription() { return description; }
    }

    public record MetricHistoryPoint(String date, double value) {}
    public record MetricHistoryResponse(String metric, List<MetricHistoryPoint> points) {}

    public static class MetricGroup {
        private final String name;
        private final List<Metric> metrics;

        public MetricGroup(String name, List<Metric> metrics) {
            this.name = name;
            this.metrics = metrics;
        }
        public String getName() { return name; }
        public List<Metric> getMetrics() { return metrics; }
    }

    public static class CalendarDay {
        private final String date;
        private final int paymentCount;
        private final double total;

        private final int outflowCount;
        private final double outflowTotal;

        public CalendarDay(String date, int paymentCount, double total) {
            this(date, paymentCount, total, 0, 0);
        }

        public CalendarDay(String date, int paymentCount, double total, int outflowCount, double outflowTotal) {
            this.date = date;
            this.paymentCount = paymentCount;
            this.total = total;
            this.outflowCount = outflowCount;
            this.outflowTotal = outflowTotal;
        }
        /** Recurring bills still waiting to be paid on this day. */
        private int billCount;
        public int getBillCount() { return billCount; }
        public CalendarDay withBillCount(int billCount) { this.billCount = billCount; return this; }
        public int getOutflowCount() { return outflowCount; }
        public double getOutflowTotal() { return outflowTotal; }
        public String getDate() { return date; }
        public int getPaymentCount() { return paymentCount; }
        public double getTotal() { return total; }
    }

    public static class CalendarPayment {
        private final String memberName;
        private final PaymentRecord payment;
        private final String source;

        public CalendarPayment(String memberName, PaymentRecord payment) {
            this(memberName, payment, "Dues payment");
        }
        public CalendarPayment(String memberName, PaymentRecord payment, String source) {
            this.memberName = memberName;
            this.payment = payment;
            this.source = source;
        }
        public String getMemberName() { return memberName; }
        public PaymentRecord getPayment() { return payment; }
        public String getSource() { return source; }
    }

    public static class PaymentMonth {
        private final String month;
        private final List<PaymentRecord> payments;
        private final double total;

        public PaymentMonth(String month, List<PaymentRecord> payments, double total) {
            this.month = month;
            this.payments = payments;
            this.total = total;
        }

        public String getMonth() { return month; }
        public List<PaymentRecord> getPayments() { return payments; }
        public double getTotal() { return total; }
    }

    @PostMapping("/send-text/{id}")
    public synchronized String sendSingleText(@PathVariable String id, RedirectAttributes redirect) {
        store.findMemberById(id).ifPresent(member -> {
            boolean sent = smsService.sendReminderToMember(member);
            redirect.addFlashAttribute("toast", sent
                    ? "Sent reminder text to " + member.getName() + "!"
                    : "Reminder was not sent. Check the daily SMS cap and dispatch log.");
        });
        return "redirect:/";
    }

    @PostMapping("/send-bulk")
    public synchronized String sendBulkText(RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", "Review the current recipient preview before dispatching reminders.");
        return "redirect:/#smsReminders";
    }

    @PostMapping("/send-status-text")
    public synchronized String sendStatusText(@RequestParam String status, @RequestParam String message,
                                              RedirectAttributes redirect) {
        if (!Set.of("Delinquent", "Payment Plan", "Paid in Full").contains(status)
                || message == null || message.isBlank() || message.length() > 1600) {
            redirect.addFlashAttribute("toast", "Choose a valid member status and enter a message of up to 1,600 characters.");
            return "redirect:/#statusMessaging";
        }
        SmsService.BulkMessageResult result = smsService.sendMessageToMembersByStatus(status, message.trim());
        if (result.eligible() == 0) {
            redirect.addFlashAttribute("toast", "No unmuted members currently have the selected status.");
            return "redirect:/#statusMessaging";
        }
        String summary = "Sent " + result.sent() + " of " + result.eligible() + " " + status
                + " member messages.";
        if (result.failed() > 0) summary += " " + result.failed() + " dispatches failed.";
        if (store.getSmsRemainingToday() == 0 && result.sent() < result.eligible()) {
            summary += " Daily SMS allowance reached.";
        }
        redirect.addFlashAttribute("toast", summary);
        return "redirect:/#statusMessaging";
    }

    @PostMapping("/toggle-mute/{id}")
    public synchronized String toggleMute(@PathVariable String id, RedirectAttributes redirect) {
        synchronized (store) {
            store.findMemberById(id).ifPresent(member -> {
                member.setMuteReminders(!member.isMuteReminders());
                store.save();
                String state = member.isMuteReminders() ? "Muted" : "Unmuted";
                redirect.addFlashAttribute("toast", state + " auto-reminders for " + member.getName());
            });
        }
        return "redirect:/";
    }

    @GetMapping("/settings")
    public String settingsPage(Model model) {
        model.addAttribute("settings", store.getSettings());
        model.addAttribute("smsLogs", store.getSmsLogs());
        model.addAttribute("hasTwilioCredentials", store.hasTwilioCredentials());
        model.addAttribute("twilioEnabled", store.isTwilioEnabled());
        model.addAttribute("twilioLiveConfigured", smsService.isLiveConfigured());
        model.addAttribute("credentialStoreName", cloudBackupService == null ? AppPaths.credentialStoreName() : cloudBackupService.credentialStoreName());
        model.addAttribute("cloudFolders", AppPaths.detectCloudFolders());
        model.addAttribute("isWindows", AppPaths.currentOs() == AppPaths.Os.WINDOWS);
        model.addAttribute("paymentServices", PaymentLinks.SERVICES);
        return "settings";
    }

    @PostMapping("/settings/appearance")
    @ResponseBody
    public org.springframework.http.ResponseEntity<Void> updateAppearance(
            @RequestParam String appearanceMode) {
        if (!Set.of("light", "dark", "system").contains(appearanceMode)) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
        synchronized (store) {
            store.getSettings().setAppearanceMode(appearanceMode);
            store.save();
        }
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/settings/twilio")
    public synchronized String updateTwilioSettings(
            @RequestParam(required = false, defaultValue = "") String accountSid,
            @RequestParam(required = false, defaultValue = "") String authToken,
            @RequestParam(required = false, defaultValue = "") String fromNumber,
            @RequestParam(defaultValue = "false") boolean enabled,
            RedirectAttributes redirect) {
        String sid = accountSid.trim();
        String token = authToken.trim();
        String number = fromNumber.trim();
        Store.TwilioCredentials existing = store.getTwilioCredentials();

        if (sid.isEmpty() && token.isEmpty() && number.isEmpty() && existing != null) {
            store.saveTwilioCredentials(new Store.TwilioCredentials(
                    existing.accountSid(), existing.authToken(), existing.fromNumber(), enabled));
        } else if (!sid.matches("AC[0-9a-fA-F]{32}") || token.isEmpty()
                || !number.matches("\\+[1-9][0-9]{7,14}")) {
            redirect.addFlashAttribute("toast",
                    "Enter a valid Twilio Account SID, Auth Token, and E.164 sender number.");
            return "redirect:/settings";
        } else {
            store.saveTwilioCredentials(new Store.TwilioCredentials(sid, token, number, enabled));
        }

        redirect.addFlashAttribute("toast", enabled
                ? "Twilio connection settings saved. Live SMS is enabled."
                : "Twilio credentials saved and disabled.");
        return "redirect:/settings";
    }

    @PostMapping("/settings/twilio/disconnect")
    public synchronized String disconnectTwilio(RedirectAttributes redirect) {
        store.clearTwilioCredentials();
        redirect.addFlashAttribute("toast", "Saved Twilio credentials removed.");
        return "redirect:/settings";
    }

    @PostMapping("/settings")
    public synchronized String updateSettings(@RequestParam String smsTemplate,
                                 @RequestParam(defaultValue = "false") boolean autoSendEnabled,
                                 @RequestParam(required = false) String frequency,
                                 @RequestParam(required = false) String dayOfWeek,
                                 @RequestParam String sendTime,
                                 @RequestParam(defaultValue = "3") int reminderDaysBeforeDue,
                                 @RequestParam(required = false) Integer reminderDaysAfterDue,
                                 @RequestParam(required = false) Boolean sequenceFriendlyEnabled,
                                 @RequestParam(required = false) Boolean sequenceFirmEnabled,
                                 @RequestParam(required = false) Integer sequenceFirmDays,
                                 @RequestParam(required = false) String sequenceFirmTemplate,
                                 @RequestParam(required = false) Boolean sequenceNoticeEnabled,
                                 @RequestParam(required = false) Integer sequenceNoticeDays,
                                 @RequestParam(required = false) String sequenceNoticeTemplate,
                                 @RequestParam(required = false) Boolean sequenceReviewEnabled,
                                 @RequestParam(required = false) Integer sequenceReviewDays,
                                 @RequestParam(defaultValue = "false") boolean sequenceForm,
                                 RedirectAttributes redirect) {
        int afterDue = reminderDaysAfterDue == null ? store.getSettings().getReminderDaysAfterDue() : reminderDaysAfterDue;
        if (smsTemplate.length() > 1600 || reminderDaysBeforeDue < 0 || reminderDaysBeforeDue > 60
                || afterDue < 0 || afterDue > 60) {
            redirect.addFlashAttribute("toast", "Check the SMS template and choose reminder offsets between 0 and 60 days.");
            return "redirect:/#smsReminders";
        }
        AppSettings current = store.getSettings();
        int firmDays = sequenceFirmDays == null ? current.getSequenceFirmDays() : sequenceFirmDays;
        int noticeDays = sequenceNoticeDays == null ? current.getSequenceNoticeDays() : sequenceNoticeDays;
        int reviewDays = sequenceReviewDays == null ? current.getSequenceReviewDays() : sequenceReviewDays;
        if (firmDays < 1 || reviewDays > 180 || firmDays >= noticeDays || noticeDays >= reviewDays) {
            redirect.addFlashAttribute("toast", "Space the reminder steps out: the firm reminder, then the notice, then officer review, each later than the one before (1 to 180 days after the due date).");
            return "redirect:/#smsReminders";
        }
        if ((sequenceFirmTemplate != null && sequenceFirmTemplate.length() > 1600)
                || (sequenceNoticeTemplate != null && sequenceNoticeTemplate.length() > 1600)) {
            redirect.addFlashAttribute("toast", "Keep each reminder message under 1,600 characters.");
            return "redirect:/#smsReminders";
        }
        synchronized (store) {
            AppSettings s = store.getSettings();
            s.setSmsTemplate(smsTemplate);
            s.setAutoSendEnabled(autoSendEnabled);
            if (frequency != null && !frequency.isBlank()) s.setFrequency(frequency);
            if (dayOfWeek != null && !dayOfWeek.isBlank()) s.setDayOfWeek(dayOfWeek);
            s.setSendTime(sendTime);
            s.setReminderDaysBeforeDue(reminderDaysBeforeDue);
            s.setReminderDaysAfterDue(afterDue);
            // Unticked checkboxes send nothing, so the sequence form says it is the sequence form.
            if (sequenceForm || sequenceFriendlyEnabled != null) s.setSequenceFriendlyEnabled(Boolean.TRUE.equals(sequenceFriendlyEnabled));
            if (sequenceForm || sequenceFirmEnabled != null) s.setSequenceFirmEnabled(Boolean.TRUE.equals(sequenceFirmEnabled));
            if (sequenceForm || sequenceNoticeEnabled != null) s.setSequenceNoticeEnabled(Boolean.TRUE.equals(sequenceNoticeEnabled));
            if (sequenceForm || sequenceReviewEnabled != null) s.setSequenceReviewEnabled(Boolean.TRUE.equals(sequenceReviewEnabled));
            s.setSequenceFirmDays(firmDays);
            s.setSequenceNoticeDays(noticeDays);
            s.setSequenceReviewDays(reviewDays);
            if (sequenceFirmTemplate != null && !sequenceFirmTemplate.isBlank()) s.setSequenceFirmTemplate(sequenceFirmTemplate);
            if (sequenceNoticeTemplate != null && !sequenceNoticeTemplate.isBlank()) s.setSequenceNoticeTemplate(sequenceNoticeTemplate);
            store.save();
        }
        redirect.addFlashAttribute("toast", "Reminder sequence settings saved.");
        return "redirect:/#smsReminders";
    }

    @PostMapping("/settings/treasury")
    public synchronized String updateTreasurySettings(
            @RequestParam(defaultValue = "false") boolean cloudBackupEnabled,
            @RequestParam(defaultValue = "") String cloudBackupFolder,
            @RequestParam(defaultValue = "30") int cloudBackupRetentionDays,
            @RequestParam(defaultValue = "false") boolean lateFeesEnabled,
            @RequestParam(defaultValue = "7") int lateFeeGraceDays,
            @RequestParam(defaultValue = "FIXED") String lateFeeType,
            @RequestParam(defaultValue = "25") double lateFeeAmount,
            @RequestParam(defaultValue = "0") double socialClearanceTolerance,
            @RequestParam(defaultValue = "false") boolean socialClearanceDuesOnly,
            @RequestParam(defaultValue = "0") double openingCashBalance,
            @RequestParam(defaultValue = "") String upcomingBills,
            @RequestParam(required = false) String chapterName,
            RedirectAttributes redirect) {
        if (chapterName != null && chapterName.trim().length() > 120) {
            redirect.addFlashAttribute("toast", "Keep the chapter name under 120 characters.");
            return "redirect:/settings";
        }
        if ((cloudBackupEnabled && cloudBackupFolder.isBlank())
                || cloudBackupFolder.length() > 1000 || cloudBackupRetentionDays < 1
                || cloudBackupRetentionDays > 365 || lateFeeGraceDays < 0 || lateFeeGraceDays > 365
                || !Set.of("FIXED", "PERCENT").contains(lateFeeType)
                || !Double.isFinite(lateFeeAmount) || lateFeeAmount < 0
                || ("PERCENT".equals(lateFeeType) && lateFeeAmount > 100)
                || !Double.isFinite(socialClearanceTolerance) || socialClearanceTolerance < 0
                || socialClearanceTolerance > 100_000 || !Double.isFinite(openingCashBalance)
                || openingCashBalance < 0 || upcomingBills.length() > 4000
                || !validUpcomingBills(upcomingBills)) {
            redirect.addFlashAttribute("toast", "Check backup folder, late-fee, clearance, cash, and upcoming bill settings.");
            return "redirect:/settings";
        }
        synchronized (store) {
            AppSettings settings = store.getSettings();
            settings.setCloudBackupEnabled(cloudBackupEnabled);
            settings.setCloudBackupFolder(cloudBackupFolder.trim());
            settings.setCloudBackupRetentionDays(cloudBackupRetentionDays);
            settings.setLateFeesEnabled(lateFeesEnabled);
            settings.setLateFeeGraceDays(lateFeeGraceDays);
            settings.setLateFeeType(lateFeeType);
            settings.setLateFeeAmount(lateFeeAmount);
            settings.setSocialClearanceTolerance(socialClearanceTolerance);
            settings.setSocialClearanceDuesOnly(socialClearanceDuesOnly);
            settings.setOpeningCashBalance(openingCashBalance);
            settings.setUpcomingBills(upcomingBills.trim());
            if (chapterName != null) settings.setChapterName(chapterName.trim());
            store.save();
        }
        redirect.addFlashAttribute("toast", "Treasury settings saved.");
        return "redirect:/settings";
    }

    private boolean validUpcomingBills(String bills) {
        for (String row : bills.lines().map(String::trim).filter(line -> !line.isBlank()).toList()) {
            String[] fields = row.split("\\|", -1);
            if (fields.length != 3 || fields[0].isBlank()) return false;
            try {
                LocalDate.parse(fields[1].trim());
                double amount = Double.parseDouble(fields[2].trim());
                if (!Double.isFinite(amount) || amount < 0) return false;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    @GetMapping("/finance-tools")
    public String financeTools(Model model) {
        // Semester rollover preview: who can be archived ($0 balance), how many members have charges to fold into a
        // carryover, expired clearance vouches, and chair budgets with activity this term.
        LocalDate today = LocalDate.now();
        List<Member> members = new ArrayList<>(store.getMembers());
        members.sort(java.util.Comparator.comparing(Member::getName, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("rolloverArchivable", members.stream().filter(member -> !Money.positive(member.getTotalOwed())).toList());
        model.addAttribute("rolloverChargeMembers", members.stream().filter(member -> member.getCharges().stream()
                .filter(charge -> charge.getRemainingAmount() >= Money.HALF_CENT).count() > 0).count());
        model.addAttribute("rolloverExpiredVouches", members.stream().filter(member -> {
            String until = member.getClearedUntilDate();
            if (until == null || until.isBlank()) return false;
            try {
                return LocalDate.parse(until).isBefore(today);
            } catch (DateTimeParseException e) {
                return true;
            }
        }).count());
        model.addAttribute("rolloverBudgets", store.getChairBudgets().stream()
                .filter(budget -> !budget.getReimbursements().isEmpty() || !budget.getRaised().isEmpty()).count());
        model.addAttribute("archivedMemberCount", store.getArchivedMembers().size());
        model.addAttribute("settings", store.getSettings());
        return "finance-tools";
    }

    /** Starts a new semester (see {@link Store#startNewSemester}); a copy of the data is saved first. */
    @PostMapping("/finance/semester-rollover")
    public synchronized String semesterRollover(@RequestParam(defaultValue = "") String termName,
                                                @RequestParam(name = "archiveMemberIds", required = false) List<String> archiveMemberIds,
                                                @RequestParam(defaultValue = "false") boolean consolidateCharges,
                                                @RequestParam(defaultValue = "false") boolean clearExpiredVouches,
                                                @RequestParam(defaultValue = "false") boolean resetChairBudgets,
                                                @RequestParam(defaultValue = "false") boolean confirmed,
                                                RedirectAttributes redirect) {
        if (!confirmed) {
            redirect.addFlashAttribute("toast", "Tick the confirmation box to start the new semester.");
            return "redirect:/finance-tools#rollover";
        }
        try {
            Store.RolloverResult result = store.startNewSemester(new Store.RolloverRequest(termName,
                    archiveMemberIds == null ? List.of() : archiveMemberIds, consolidateCharges, clearExpiredVouches, resetChairBudgets), LocalDate.now());
            List<String> done = new ArrayList<>();
            if (result.archived() > 0) done.add(result.archived() + (result.archived() == 1 ? " member archived" : " members archived"));
            if (result.consolidatedMembers() > 0) done.add(result.consolidatedMembers() + " carryover " + (result.consolidatedMembers() == 1 ? "charge" : "charges")
                    + " (" + money(result.consolidatedTotal()) + ")");
            if (result.clearedVouches() > 0) done.add(result.clearedVouches() + " expired " + (result.clearedVouches() == 1 ? "vouch" : "vouches") + " cleared");
            if (result.budgetsReset() > 0) done.add(result.budgetsReset() + " chair " + (result.budgetsReset() == 1 ? "budget" : "budgets") + " reset");
            if (result.skippedWithBalance() > 0) done.add(result.skippedWithBalance() + " skipped (still owe money)");
            redirect.addFlashAttribute("toast", (termName.isBlank() ? "New semester started" : termName.trim() + " started")
                    + (done.isEmpty() ? "." : ": " + String.join(", ", done) + ".")
                    + " A copy of the previous data was saved as " + result.backupFile().getFileName() + ".");
            // The "New Term Initialized" card on the next page shows where the chapter now stands.
            FinanceDocumentService.Position position = financeDocumentService.position();
            Map<String, Object> reveal = new LinkedHashMap<>();
            reveal.put("termName", termName.isBlank() ? "New semester" : termName.trim());
            reveal.put("date", LocalDate.now().toString());
            reveal.put("startingReserves", position.cashOnHand());
            reveal.put("carriedReceivables", position.receivables());
            reveal.put("activeMembers", position.activeMembers());
            reveal.put("rosterSize", position.rosterSize());
            reveal.put("archived", result.archived());
            reveal.put("skippedWithBalance", result.skippedWithBalance());
            reveal.put("consolidatedMembers", result.consolidatedMembers());
            reveal.put("consolidatedTotal", result.consolidatedTotal());
            reveal.put("clearedVouches", result.clearedVouches());
            reveal.put("budgetsReset", result.budgetsReset());
            reveal.put("backupFile", result.backupFile().getFileName().toString());
            redirect.addFlashAttribute("rolloverReveal", reveal);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class).error("Semester rollover failed", e);
            redirect.addFlashAttribute("toast", "The rollover was not applied: " + e.getMessage() + ". Nothing was changed.");
        }
        return "redirect:/finance-tools#rollover";
    }

    @GetMapping("/chair-budgeting")
    public String chairBudgeting(Model model) {
        List<ChairBudget> budgets = new ArrayList<>(store.getChairBudgets());
        budgets.sort(java.util.Comparator.comparing(ChairBudget::getChairName, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("budgets", budgets);
        // Only needed to name the member on charges that were credited to dues before that option was removed.
        Map<String, String> memberNames = new LinkedHashMap<>();
        store.getMembers().forEach(member -> memberNames.put(member.getId(), member.getName()));
        model.addAttribute("memberNamesById", memberNames);
        // For the "Chair holder" pickers when adding or editing a chair.
        List<Member> rosterMembers = new ArrayList<>(store.getMembers());
        rosterMembers.sort(java.util.Comparator.comparing(Member::getName, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("rosterMembers", rosterMembers);
        // Each budget's figures, charges and money raised for the hover preview and full-screen budget view
        // (rendered client-side).
        List<Map<String, Object>> budgetDetails = new ArrayList<>();
        for (ChairBudget budget : budgets) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", budget.getId());
            detail.put("name", budget.getChairName());
            detail.put("cap", budget.getBudgetCap());
            detail.put("spent", budget.getSpent());
            detail.put("remaining", budget.getRemaining());
            detail.put("raiseGoal", budget.getRaiseGoal());
            detail.put("raisedTotal", budget.getRaisedTotal());
            detail.put("charges", budget.getReimbursements().stream().map(item -> {
                Map<String, Object> charge = new LinkedHashMap<>();
                charge.put("date", item.getDate() == null ? "" : item.getDate());
                charge.put("description", item.getDescription() == null ? "" : item.getDescription());
                charge.put("amount", item.getAmount());
                charge.put("creditedTo", item.getCreditedMemberId() == null ? ""
                        : memberNames.getOrDefault(item.getCreditedMemberId(), "Removed member"));
                charge.put("receipt", item.getReceiptFileName() == null ? "" : item.getReceiptFileName());
                return charge;
            }).toList());
            detail.put("raised", budget.getRaised().stream().map(item -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("date", item.getDate() == null ? "" : item.getDate());
                entry.put("description", item.getDescription() == null ? "" : item.getDescription());
                entry.put("amount", item.getAmount());
                return entry;
            }).toList());
            detail.put("eventRaised", budget.getEventRaised());
            detail.put("eventSpent", budget.getEventSpent());
            detail.put("events", budget.getLinkedEvents().stream().map(event -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", event.id());
                entry.put("name", event.name());
                entry.put("date", event.eventDate());
                entry.put("completed", event.completed());
                entry.put("revenue", event.revenue());
                entry.put("expenses", event.expenses());
                return entry;
            }).toList());
            budgetDetails.add(detail);
        }
        model.addAttribute("budgetDetails", budgetDetails);
        model.addAttribute("today", LocalDate.now().toString());
        return "chair-budgeting";
    }
    @PostMapping("/members/{id}/social-clearance")
    public synchronized String setSocialClearance(@PathVariable String id,
                                                  @RequestParam String clearedUntilDate,
                                                  RedirectAttributes redirect) {
        LocalDate until;
        try {
            until = LocalDate.parse(clearedUntilDate);
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid social-clearance expiration date.");
            return "redirect:/";
        }
        store.findMemberById(id).ifPresentOrElse(member -> {
            member.setClearedUntilDate(until.toString());
            store.save();
            redirect.addFlashAttribute("toast", "Manual social clearance saved through " + until + ".");
        }, () -> redirect.addFlashAttribute("toast", "Member not found."));
        return "redirect:/";
    }

    @GetMapping("/finance/social-clearance.pdf")
    public org.springframework.http.ResponseEntity<byte[]> socialClearancePdf() throws Exception {
        return documentResponse(financeDocumentService.socialClearancePdf(), "application/pdf",
                "DuesDesk-social-clearance-" + LocalDate.now() + ".pdf");
    }

    @GetMapping("/finance/social-clearance.csv")
    public org.springframework.http.ResponseEntity<byte[]> socialClearanceCsv() {
        return documentResponse(financeDocumentService.socialClearanceCsv(), "text/csv",
                "DuesDesk-social-clearance-" + LocalDate.now() + ".csv");
    }

    @GetMapping("/finance/meeting-packet.pdf")
    public org.springframework.http.ResponseEntity<byte[]> meetingPacket() throws Exception {
        return documentResponse(financeDocumentService.meetingPacketPdf(), "application/pdf",
                "DuesDesk-meeting-packet-" + LocalDate.now() + ".pdf");
    }

    /** The treasurer's term in numbers, for the performance card shown with the officer handoff package. */
    @GetMapping("/finance/term-performance")
    @ResponseBody
    public org.springframework.http.ResponseEntity<FinanceDocumentService.TermPerformance> termPerformance() {
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(financeDocumentService.termPerformance());
    }

    @PostMapping("/finance/handoff-package")
    public org.springframework.http.ResponseEntity<byte[]> handoffPackage(@RequestParam String password) throws Exception {
        if (password == null || password.length() < 8) {
            return org.springframework.http.ResponseEntity.badRequest().body(
                    "Use a backup password with at least 8 characters.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] data = financeDocumentService.handoffPackage(password.toCharArray());
        return documentResponse(data, "application/zip", "DuesDesk-officer-handoff-" + LocalDate.now() + ".zip");
    }

    @PostMapping("/settings/cloud-backup/run")
    public synchronized String runCloudBackup(RedirectAttributes redirect) {
        try {
            java.nio.file.Path target = cloudBackupService.createBackup();
            redirect.addFlashAttribute("toast", "Encrypted snapshot saved: " + target.getFileName());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class).error("Manual cloud backup failed", e);
            store.getSettings().setCloudBackupStatus("Last backup failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            store.save();
            redirect.addFlashAttribute("toast", "Backup failed: " + e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/cloud-backup/recovery-password")
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> cloudBackupRecoveryPassword() {
        if (!store.getSettings().isCloudBackupEnabled()) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .body("Enable automatic backups before retrieving the recovery password.");
        }
        char[] password = null;
        try {
            password = cloudBackupService.getRecoveryPassword();
            return org.springframework.http.ResponseEntity.ok()
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .body(new String(password));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class)
                    .error("Unable to retrieve automatic-backup recovery password", e);
            return org.springframework.http.ResponseEntity.internalServerError()
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .body("The recovery password could not be retrieved from the " + AppPaths.credentialStoreName() + ".");
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
        }
    }

    @PostMapping("/finance/chair-budgets")
    public synchronized String saveChairBudget(@RequestParam String chairName,
                                               @RequestParam double budgetCap,
                                               @RequestParam(defaultValue = "0") double raiseGoal,
                                               @RequestParam(defaultValue = "") String chairMemberId,
                                               RedirectAttributes redirect) {
        try {
            ChairBudget saved = store.saveChairBudget(chairName, budgetCap, raiseGoal, chairMemberId);
            String holder = saved.getChairMemberId() == null ? "" : store.findMemberById(saved.getChairMemberId())
                    .map(member -> ", held by " + member.getName()).orElse("");
            redirect.addFlashAttribute("toast", "Chair budget saved" + holder + ".");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
        }
        return "redirect:/chair-budgeting";
    }

    @PostMapping("/finance/chair-budgets/{id}/edit")
    public synchronized String editChairBudget(@PathVariable String id,
                                               @RequestParam String chairName,
                                               @RequestParam double budgetCap,
                                               @RequestParam(defaultValue = "0") double raiseGoal,
                                               @RequestParam(defaultValue = "") String accentColor,
                                               @RequestParam(defaultValue = "") String chairMemberId,
                                               RedirectAttributes redirect) {
        try {
            store.updateChairBudget(id, chairName, budgetCap, raiseGoal, accentColor, chairMemberId);
            redirect.addFlashAttribute("toast", "Chair position updated.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
        }
        return "redirect:/chair-budgeting";
    }

    @PostMapping("/finance/chair-budgets/{id}/delete")
    public synchronized String deleteChairBudget(@PathVariable String id, RedirectAttributes redirect) {
        try {
            if (store.deleteChairBudget(id)) {
                redirect.addFlashAttribute("toast", "Chair position deleted.");
            } else {
                redirect.addFlashAttribute("toast", "That chair position no longer exists.");
            }
        } catch (IllegalStateException e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class).error("Unable to delete chair position", e);
            redirect.addFlashAttribute("toast", "Chair position could not be deleted. Try again.");
        }
        return "redirect:/chair-budgeting";
    }

    @PostMapping("/finance/reimbursements")
    public synchronized String addReimbursement(@RequestParam String budgetId,
                                                @RequestParam String date,
                                                @RequestParam String description,
                                                @RequestParam double amount,
                                                @RequestParam(required = false) MultipartFile receipt,
                                                RedirectAttributes redirect) {
        String receiptName = null;
        boolean saved = false;
        try {
            LocalDate.parse(date);
            if (description == null || description.isBlank() || description.length() > 200
                    || !Double.isFinite(amount) || amount <= 0) {
                throw new IllegalArgumentException("Enter a description and a valid amount.");
            }
            receiptName = receipt == null || receipt.isEmpty() ? null : store.saveReceipt(receipt);
            ReimbursementRecord record = new ReimbursementRecord(UUID.randomUUID().toString(), date,
                    description.trim(), amount, receiptName, null);
            if (!store.addReimbursement(budgetId, record)) {
                throw new IllegalArgumentException("Charge was not saved. Verify its chair budget.");
            }
            saved = true;
            String chair = store.getChairBudgets().stream().filter(item -> item.getId().equals(budgetId))
                    .map(ChairBudget::getChairName).findFirst().orElse("chair");
            redirect.addFlashAttribute("toast", "Saved " + String.format("$%,.2f", amount) + " to the " + chair + " budget.");
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid charge date.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class).error("Unable to record chair charge", e);
            redirect.addFlashAttribute("toast", "Receipt or charge could not be saved. Check the application log.");
        } finally {
            if (!saved && receiptName != null) {
                try {
                    store.deleteReceipt(receiptName);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(DashboardController.class)
                            .error("Unable to remove an unlinked chair charge receipt {}", receiptName, e);
                }
            }
        }
        return "redirect:/chair-budgeting";
    }

    /**
     * Records money a chair raised toward its raise goal. {@code view} is "panel" when the entry came from the
     * full-screen budget view, which is then reopened; otherwise the person returns to the chair list.
     */
    @PostMapping("/finance/chair-budgets/{id}/raised")
    public synchronized String addRaised(@PathVariable String id,
                                         @RequestParam String date,
                                         @RequestParam String description,
                                         @RequestParam double amount,
                                         @RequestParam(defaultValue = "") String view,
                                         RedirectAttributes redirect) {
        String back = "panel".equals(view) ? "redirect:/chair-budgeting#budget-" + id : "redirect:/chair-budgeting";
        try {
            LocalDate.parse(date);
            if (!store.addRaised(id, new RaisedRecord(UUID.randomUUID().toString(), date, description == null ? "" : description.trim(), amount))) {
                throw new IllegalArgumentException("Enter a description and an amount greater than zero for the money raised.");
            }
            String chair = store.getChairBudgets().stream().filter(item -> item.getId().equals(id))
                    .map(ChairBudget::getChairName).findFirst().orElse("chair");
            redirect.addFlashAttribute("toast", "Recorded " + String.format("$%,.2f", amount) + " raised for " + chair + ".");
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid date for the money raised.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
        }
        return back;
    }

    /** Saves the individual charges listed in a chair's full-screen budget view (rows of date, description, amount). */
    @PostMapping("/finance/chair-budgets/{id}/charges")
    public synchronized String addChairCharges(@PathVariable String id,
                                               @RequestParam org.springframework.util.MultiValueMap<String, String> form,
                                               RedirectAttributes redirect) {
        // Read the repeated fields from the raw map: binding one value to List<String> would split it on commas.
        ChairBudget budget = store.getChairBudgets().stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        if (budget == null) {
            redirect.addFlashAttribute("toast", "That chair budget no longer exists.");
            return "redirect:/chair-budgeting";
        }
        String back = "redirect:/chair-budgeting#budget-" + budget.getId();
        List<String> dates = form.getOrDefault("chargeDate", List.of());
        List<String> descriptions = form.getOrDefault("chargeDescription", List.of());
        List<String> amounts = form.getOrDefault("chargeAmount", List.of());
        int rows = Math.max(dates.size(), Math.max(descriptions.size(), amounts.size()));
        List<ReimbursementRecord> charges = new ArrayList<>();
        double total = 0;
        for (int i = 0; i < rows; i++) {
            String date = i < dates.size() ? dates.get(i).trim() : "";
            String description = i < descriptions.size() ? descriptions.get(i).trim() : "";
            String amountText = i < amounts.size() ? amounts.get(i).trim() : "";
            if (description.isEmpty() && amountText.isEmpty()) continue; // untouched extra row
            double amount;
            try {
                LocalDate.parse(date);
                amount = Double.parseDouble(amountText);
            } catch (DateTimeParseException | NumberFormatException e) {
                amount = -1;
            }
            if (description.isEmpty() || description.length() > 200 || !Double.isFinite(amount) || amount <= 0) {
                redirect.addFlashAttribute("toast", "Charge " + (i + 1) + ": enter a date, a description and an amount greater than zero. Nothing was saved.");
                return back;
            }
            charges.add(new ReimbursementRecord(UUID.randomUUID().toString(), date, description, amount, null, null));
            total += amount;
        }
        if (charges.isEmpty()) {
            redirect.addFlashAttribute("toast", "Add at least one charge before saving.");
            return back;
        }
        int added = store.addChairCharges(budget.getId(), charges);
        redirect.addFlashAttribute("toast", added > 0
                ? "Saved " + added + (added == 1 ? " charge" : " charges") + " (" + String.format("$%,.2f", total)
                    + ") to the " + budget.getChairName() + " budget."
                : "Charges were not saved. Refresh the page and try again.");
        return back;
    }

    @GetMapping("/finance/receipts/{fileName}")
    public org.springframework.http.ResponseEntity<byte[]> getReceipt(@PathVariable String fileName) throws Exception {
        String type = fileName.endsWith(".png") ? "image/png" : "image/jpeg";
        return documentResponse(store.readReceipt(fileName), type, fileName);
    }

    @PostMapping("/settings/reminders/send")
    public synchronized String sendReminderPreview(RedirectAttributes redirect) {
        int sent = smsService.sendReminderPreview();
        redirect.addFlashAttribute("toast", "Reminder preview dispatch complete: sent " + sent
                + " messages. Remaining daily SMS allowance: " + store.getSmsRemainingToday() + ".");
        return "redirect:/#smsReminders";
    }

    /** Printable payment plan agreement (promissory note) for a member with an installment schedule. */
    @GetMapping("/members/{id}/agreement.pdf")
    public org.springframework.http.ResponseEntity<?> memberAgreementPdf(@PathVariable String id) throws Exception {
        Member member = store.findMemberById(id).orElse(null);
        if (member == null) return org.springframework.http.ResponseEntity.notFound().build();
        if (!PaymentPlanAgreementService.hasPlan(member)) {
            return org.springframework.http.ResponseEntity.badRequest().body("This member has no installment plan to put in an agreement.");
        }
        return documentResponse(new PaymentPlanAgreementService().agreementPdf(member, store.getSettings(), LocalDate.now()),
                "application/pdf", safeFileName(member.getName()) + "-payment-plan-agreement.pdf");
    }

    /** Forgets one of a member's remembered payer names. */
    @PostMapping("/members/{id}/payer-aliases/remove")
    public synchronized String removePayerAlias(@PathVariable String id, @RequestParam String alias, RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.removePayerAlias(id, alias)
                ? "\"" + alias.trim() + "\" will no longer be suggested for this member." : "That payer name was already removed.");
        return "redirect:/";
    }

    @GetMapping("/members/{id}/statement.pdf")
    public org.springframework.http.ResponseEntity<byte[]> memberStatementPdf(@PathVariable String id) throws Exception {
        Member member = store.findMemberById(id).orElse(null);
        if (member == null) return org.springframework.http.ResponseEntity.notFound().build();
        return documentResponse(documentService.statementPdf(member), "application/pdf", safeFileName(member.getName()) + "-statement.pdf");
    }

    @GetMapping("/members/{id}/statement.csv")
    public org.springframework.http.ResponseEntity<byte[]> memberStatementCsv(@PathVariable String id) {
        Member member = store.findMemberById(id).orElse(null);
        if (member == null) return org.springframework.http.ResponseEntity.notFound().build();
        return documentResponse(documentService.statementCsv(member), "text/csv", safeFileName(member.getName()) + "-statement.csv");
    }

    @GetMapping("/members/{id}/receipts/{paymentKey}.pdf")
    public org.springframework.http.ResponseEntity<byte[]> paymentReceipt(@PathVariable String id,
                                                                          @PathVariable String paymentKey) throws Exception {
        Member member = store.findMemberById(id).orElse(null);
        if (member == null || member.getPaymentHistory() == null) return org.springframework.http.ResponseEntity.notFound().build();
        PaymentRecord payment = member.getPaymentHistory().stream()
                .filter(item -> paymentKey.equals(item.getIdempotencyKey())).findFirst().orElse(null);
        if (payment == null) return org.springframework.http.ResponseEntity.notFound().build();
        return documentResponse(documentService.receiptPdf(member, payment), "application/pdf",
                safeFileName(member.getName()) + "-receipt.pdf");
    }

    private org.springframework.http.ResponseEntity<byte[]> documentResponse(byte[] data, String contentType, String fileName) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Cache-Control", "no-store")
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .body(data);
    }

    private String safeFileName(String name) {
        String safe = name == null ? "member" : name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return safe.replaceAll("^-|-$", "");
    }

    @PostMapping("/upload-csv")
    public synchronized String uploadCsv(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        if (file.isEmpty()) {
            redirect.addFlashAttribute("toast", "Please select a valid CSV file to upload.");
            return "redirect:/upload-docs";
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            redirect.addFlashAttribute("toast", "CSV uploads are limited to 5 MB.");
            return "redirect:/upload-docs";
        }
        try {
            int count = store.importCsv(file);
            redirect.addFlashAttribute("toast", "Successfully imported/updated " + count + " members from GreekBill CSV!");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", "CSV import failed: " + e.getMessage());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DashboardController.class).error("CSV import failed", e);
            redirect.addFlashAttribute("toast", "CSV import failed. Check the file format and application log, then try again.");
        }
        return "redirect:/upload-docs";
    }

    @PostMapping("/add-member")
    public synchronized String addMember(@RequestParam String name,
                            @RequestParam String phoneNumber,
                            @RequestParam double totalOwed,
                            @RequestParam double pastDueBalance,
                            @RequestParam double paidThisMonth,
                            @RequestParam(defaultValue = "") String email,
                            RedirectAttributes redirect) {
        if (name.isBlank() || name.length() > 120 || phoneNumber.isBlank() || phoneNumber.length() > 40
                || !Double.isFinite(totalOwed)
                || !Double.isFinite(pastDueBalance) || !Double.isFinite(paidThisMonth)
                || totalOwed < 0 || pastDueBalance < 0 || pastDueBalance > totalOwed || paidThisMonth < 0) {
            redirect.addFlashAttribute("toast", "Enter valid member details and non-negative balances (past due cannot exceed total owed).");
            return "redirect:/";
        }
        if (!validEmail(email)) {
            redirect.addFlashAttribute("toast", "Enter a valid email address, or leave it blank.");
            return "redirect:/";
        }
        String status = store.determineStatus(totalOwed, pastDueBalance);
        Member added = new Member(
                UUID.randomUUID().toString(),
                name.trim(), phoneNumber.trim(), totalOwed, pastDueBalance, paidThisMonth, status, "Never", false);
        added.setEmail(email.trim());
        store.addMember(added);
        redirect.addFlashAttribute("toast", "Added " + name + " to roster.");
        return "redirect:/";
    }

    @PostMapping("/edit-member/{id}")
    public synchronized String editMember(@PathVariable String id,
                             @RequestParam String name,
                             @RequestParam String phoneNumber,
                             @RequestParam double totalOwed,
                             @RequestParam double pastDueBalance,
                             @RequestParam double paidThisMonth,
                             @RequestParam(required = false) String email,
                             @RequestParam(required = false) String venmoDisplayName,
                             @RequestParam(required = false) String duesCode,
                             @RequestParam(required = false) String membershipStatus,
                             @RequestParam(required = false) String returnGroup,
                             RedirectAttributes redirect) {
        String back = "redirect:/" + (returnGroup == null || Member.MEMBER.equals(rosterGroup(returnGroup)) ? ""
                : "?group=" + rosterGroup(returnGroup).toLowerCase(java.util.Locale.ROOT));
        String code = duesCode == null ? null : duesCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (venmoDisplayName != null && venmoDisplayName.trim().length() > 120) {
            redirect.addFlashAttribute("toast", "The Venmo name can be at most 120 characters.");
            return back;
        }
        if (code != null && !code.isEmpty() && !code.matches("[A-Z0-9][A-Z0-9-]{2,31}")) {
            redirect.addFlashAttribute("toast", "Dues codes use 3-32 letters, numbers and dashes, e.g. DUES-JSMITH.");
            return back;
        }
        if (code != null && store.duesCodeTaken(code, id)) {
            redirect.addFlashAttribute("toast", "Another member already uses the dues code " + code + ".");
            return back;
        }
        if (name.isBlank() || name.length() > 120 || phoneNumber.isBlank() || phoneNumber.length() > 40
                || !Double.isFinite(totalOwed) || !Double.isFinite(pastDueBalance)
                || !Double.isFinite(paidThisMonth) || totalOwed < 0 || pastDueBalance < 0 || paidThisMonth < 0
                || pastDueBalance > totalOwed) {
            redirect.addFlashAttribute("toast", "Enter a name and valid non-negative balances (past due cannot exceed total owed).");
            return back;
        }
        if (email != null && !validEmail(email)) {
            redirect.addFlashAttribute("toast", "Enter a valid email address, or leave it blank.");
            return back;
        }
        synchronized (store) {
            store.findMemberById(id).ifPresentOrElse(member -> {
                if (email != null) member.setEmail(email.trim());
                if (venmoDisplayName != null) member.setVenmoDisplayName(venmoDisplayName.trim());
                // Every member keeps a dues code, so a blank field leaves the current one in place.
                if (code != null && !code.isEmpty()) member.setDuesCode(code);
                member.setName(name.trim());
                member.setPhoneNumber(phoneNumber.trim());
                member.setTotalOwed(totalOwed);
                member.setPastDueBalance(pastDueBalance);
                member.setPaidThisMonth(paidThisMonth);
                member.setStatus(store.determineStatus(totalOwed, pastDueBalance));
                String previousRoster = member.getMembershipStatus();
                if (membershipStatus != null) member.setMembershipStatus(membershipStatus);
                store.save();
                redirect.addFlashAttribute("toast", previousRoster.equals(member.getMembershipStatus())
                        ? "Updated profile for " + name + "."
                        : "Updated profile for " + name + " and moved them to " + (Member.MEMBER.equals(member.getMembershipStatus())
                            ? "Members" : member.getMembershipStatus()) + ".");
            }, () -> redirect.addFlashAttribute("toast", "Member not found."));
        }
        return back;
    }

    /** Blank, or a plausible single email address (used to pre-fill "Open in Email" reminders). */
    static boolean validEmail(String email) {
        String value = email == null ? "" : email.trim();
        return value.isEmpty() || (value.length() <= 200 && value.matches("[^@\\s,;<>]+@[^@\\s,;<>]+\\.[^@\\s,;<>]+"));
    }

    /** Saves the chapter's payment handles (one row per service) used for {{venmo_link}}-style reminder links. */
    @PostMapping("/settings/payment-links")
    public synchronized String updatePaymentLinks(@RequestParam(name = "service", required = false) List<String> services,
                                                  @RequestParam(name = "handle", required = false) List<String> handles,
                                                  RedirectAttributes redirect) {
        List<PaymentLinks.Handle> saved = new ArrayList<>();
        int rows = Math.max(services == null ? 0 : services.size(), handles == null ? 0 : handles.size());
        for (int i = 0; i < rows; i++) {
            String service = services != null && i < services.size() ? services.get(i) : "";
            String handle = handles != null && i < handles.size() ? handles.get(i).trim() : "";
            if (handle.isEmpty()) continue;
            if (!PaymentLinks.SERVICES.containsKey(service) || handle.length() > 100 || handle.matches(".*[\\s<>\"].*") && !PaymentLinks.ZELLE.equals(service)) {
                redirect.addFlashAttribute("toast", "Check payment handle " + (i + 1) + ": pick a service and enter the account name without spaces.");
                return "redirect:/settings#paymentLinks";
            }
            saved.add(new PaymentLinks.Handle(service, handle));
        }
        if (saved.size() > 8) {
            redirect.addFlashAttribute("toast", "Add up to 8 payment handles.");
            return "redirect:/settings#paymentLinks";
        }
        synchronized (store) {
            store.getSettings().setChapterCashServiceHandles(saved);
            store.save();
        }
        redirect.addFlashAttribute("toast", saved.isEmpty() ? "Payment handles cleared." : "Saved " + saved.size()
                + (saved.size() == 1 ? " payment handle." : " payment handles.") + " Use {{venmo_link}} or {{pay_links}} in reminder templates.");
        return "redirect:/settings#paymentLinks";
    }

    public synchronized String addCharge(String id, String description, double amount, String dueDate, boolean pastDue,
                                         RedirectAttributes redirect) {
        return addCharge(id, description, amount, dueDate, pastDue, "", redirect);
    }

    /** Applies one charge from the Charges page, where the member is chosen in the form rather than the address. */
    @PostMapping("/charges/apply")
    public synchronized String applyCharge(@RequestParam(defaultValue = "") String memberId,
                                           @RequestParam String description,
                                           @RequestParam double amount,
                                           @RequestParam(defaultValue = "") String dueDate,
                                           @RequestParam(defaultValue = "false") boolean pastDue,
                                           @RequestParam(defaultValue = "") String back,
                                           RedirectAttributes redirect) {
        if (memberId.isBlank()) {
            redirect.addFlashAttribute("toast", "Choose the member to charge.");
            return "redirect:" + ActivityController.safeBack(back, "/charges");
        }
        return addCharge(memberId, description, amount, dueDate, pastDue, back, redirect);
    }

    @PostMapping("/members/{id}/charges")
    public synchronized String addCharge(@PathVariable String id,
                            @RequestParam String description,
                            @RequestParam double amount,
                            @RequestParam(defaultValue = "") String dueDate,
                            @RequestParam(defaultValue = "false") boolean pastDue,
                            @RequestParam(defaultValue = "") String back,
                            RedirectAttributes redirect) {
        String done = "redirect:" + ActivityController.safeBack(back, "/charges");
        if (description.isBlank() || description.length() > 200 || !Double.isFinite(amount) || amount <= 0) {
            redirect.addFlashAttribute("toast", "Enter a charge description and an amount greater than zero.");
            return done;
        }
        LocalDate chargeDueDate = LocalDate.now();
        if (!dueDate.isBlank()) {
            try {
                chargeDueDate = LocalDate.parse(dueDate);
            } catch (DateTimeParseException e) {
                redirect.addFlashAttribute("toast", "Choose a valid due date.");
                return done;
            }
        }
        boolean isPastDue = pastDue || chargeDueDate.isBefore(LocalDate.now());
        LocalDate selectedDueDate = chargeDueDate;
        synchronized (store) {
            store.findMemberById(id).ifPresentOrElse(member -> {
                if (member.getCharges() == null) member.setCharges(new ArrayList<>());
                double pastDueAmount = isPastDue ? amount : 0;
                ChargeRecord charge = new ChargeRecord(UUID.randomUUID().toString(),
                        LocalDate.now().toString(), description.trim(), amount, pastDueAmount);
                charge.setDueDate(selectedDueDate.toString());
                member.getCharges().add(charge);
                member.setTotalOwed(member.getTotalOwed() + amount);
                member.setPastDueBalance(member.getPastDueBalance() + pastDueAmount);
                member.setStatus(store.determineStatus(member.getTotalOwed(), member.getPastDueBalance()));
                store.save();
                redirect.addFlashAttribute("toast", "Added charge to " + member.getName() + "'s account.");
            }, () -> redirect.addFlashAttribute("toast", "Member not found."));
        }
        return done;
    }

    @PostMapping("/charges/batch")
    public synchronized String addBatchCharges(@RequestParam(required = false) List<String> memberIds,
                                               @RequestParam String description,
                                               @RequestParam double amount,
                                               @RequestParam String dueDate,
                                               @RequestParam(defaultValue = "") String back,
                                               RedirectAttributes redirect) {
        String done = "redirect:" + ActivityController.safeBack(back, "/charges");
        if (description.isBlank() || description.length() > 200 || !Double.isFinite(amount) || amount <= 0) {
            redirect.addFlashAttribute("toast", "Enter a charge description and an amount greater than zero.");
            return done;
        }
        try {
            LocalDate.parse(dueDate);
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid due date for the batch charge.");
            return done;
        }
        int added = store.addBatchCharges(memberIds, description.trim(), amount, dueDate);
        redirect.addFlashAttribute("toast", added > 0
                ? "Added " + description.trim() + " to " + added + " member accounts."
                : "No charges were added. Select valid roster members and try again.");
        return done;
    }

    @PostMapping("/members/adjustments")
    public synchronized String addMemberAdjustment(@RequestParam(defaultValue = "") String memberId,
                                                   @RequestParam(defaultValue = ChargePreset.FINE) String type,
                                                   @RequestParam(defaultValue = "") String name,
                                                   @RequestParam(defaultValue = "0") double amount,
                                                   @RequestParam(defaultValue = "") String dueDate,
                                                   @RequestParam(defaultValue = "false") boolean savePreset,
                                                   @RequestParam(defaultValue = "") String back,
                                                   RedirectAttributes redirect) {
        String adjustmentType = ChargePreset.DISCOUNT.equals(type) ? ChargePreset.DISCOUNT : ChargePreset.FINE;
        Store.AdjustmentResult result = store.addMemberAdjustment(memberId, adjustmentType, name, amount, dueDate, LocalDate.now());
        String message = result.message();
        if (result.applied() && savePreset) {
            message += store.saveChargePreset(name, amount, adjustmentType)
                    ? " Saved \"" + name.trim() + "\" for next time."
                    : " The preset wasn't saved (limit is " + Store.MAX_CHARGE_PRESETS + ").";
        }
        redirect.addFlashAttribute("toast", message);
        return "redirect:" + ActivityController.safeBack(back, "/charges");
    }

    @PostMapping("/charge-presets/{presetId}/remove")
    public synchronized String removeChargePreset(@PathVariable String presetId,
                                                  @RequestParam(defaultValue = "") String back, RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.removeChargePreset(presetId) ? "Saved charge removed." : "Saved charge not found.");
        return "redirect:" + ActivityController.safeBack(back, "/charges");
    }

    @PostMapping("/members/{id}/installments")
    public synchronized String addInstallments(@PathVariable String id,
                                               @RequestParam org.springframework.util.MultiValueMap<String, String> form,
                                               RedirectAttributes redirect) {
        // Read the repeated row fields from the raw map: binding a single value to List<String> would split a
        // description like "Fall, part 1" on its comma and make the rows disagree.
        List<String> dueDates = form.getOrDefault("dueDates", List.of());
        List<String> amounts = form.getOrDefault("amounts", List.of());
        List<String> descriptions = form.getOrDefault("descriptions", List.of());
        if (dueDates.isEmpty() || dueDates.size() != amounts.size() || amounts.size() != descriptions.size()
                || dueDates.size() > 60) {
            redirect.addFlashAttribute("toast", "Enter up to 60 complete installment rows.");
            return "redirect:/";
        }
        List<PaymentInstallment> installments = new ArrayList<>();
        for (int i = 0; i < dueDates.size(); i++) {
            double amount;
            try {
                amount = Double.parseDouble(amounts.get(i).trim());
            } catch (NumberFormatException e) {
                redirect.addFlashAttribute("toast", "Enter a valid amount for every installment row.");
                return "redirect:/";
            }
            installments.add(new PaymentInstallment(UUID.randomUUID().toString(),
                    dueDates.get(i).trim(), descriptions.get(i).trim(), amount));
        }
        redirect.addFlashAttribute("toast", store.addInstallments(id, installments)
                ? "Custom payment plan installments saved."
                : "Installments were not saved. Check the dates, amounts, and that the total does not exceed the member's balance.");
        return "redirect:/";
    }

    @PostMapping("/members/{id}/installments/{installmentId}/remove")
    public synchronized String removeInstallment(@PathVariable String id, @PathVariable String installmentId,
                                                 RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.removeInstallment(id, installmentId)
                ? "Installment removed from the payment plan."
                : "Installment was not found.");
        return "redirect:/";
    }

    public synchronized String removeCharge(String id, String chargeId, RedirectAttributes redirect) {
        return removeCharge(id, chargeId, "", redirect);
    }

    @PostMapping("/members/{id}/charges/{chargeId}/remove")
    public synchronized String removeCharge(@PathVariable String id, @PathVariable String chargeId,
                               @RequestParam(defaultValue = "") String back,
                               RedirectAttributes redirect) {
        String done = "redirect:" + ActivityController.safeBack(back, "/charges");
        synchronized (store) {
            store.findMemberById(id).ifPresentOrElse(member -> {
                if (member.getCharges() == null) member.setCharges(new ArrayList<>());
                ChargeRecord charge = member.getCharges().stream()
                        .filter(item -> chargeId.equals(item.getId())).findFirst().orElse(null);
                if (charge == null) {
                    redirect.addFlashAttribute("toast", "Charge not found.");
                    return;
                }
                member.getCharges().remove(charge);
                member.setTotalOwed(Math.max(0, member.getTotalOwed() - charge.getRemainingAmount()));
                member.setPastDueBalance(Math.max(0, member.getPastDueBalance() - charge.getPastDueAmount()));
                member.setStatus(store.determineStatus(member.getTotalOwed(), member.getPastDueBalance()));
                store.save();
                redirect.addFlashAttribute("toast", "Removed charge from " + member.getName() + "'s account.");
            }, () -> redirect.addFlashAttribute("toast", "Member not found."));
        }
        return done;
    }

    @PostMapping("/payments")
    public synchronized String recordPayment(@RequestParam String memberId,
                                @RequestParam String date,
                                @RequestParam double amount,
                                @RequestParam(defaultValue = "Payment received") String description,
                                @RequestParam String idempotencyKey,
                                RedirectAttributes redirect) {
        LocalDate paymentDate;
        try {
            paymentDate = LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid payment date.");
            return "redirect:/calendar";
        }
        if (!Double.isFinite(amount) || amount <= 0 || description.isBlank() || description.length() > 200) {
            redirect.addFlashAttribute("toast", "Enter a payment description and an amount greater than zero.");
            return "redirect:/calendar?month=" + YearMonth.from(paymentDate);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            redirect.addFlashAttribute("toast", "Payment could not be recorded: missing request identifier. Please reload and retry.");
            return "redirect:/calendar?month=" + YearMonth.from(paymentDate);
        }
        boolean recorded = store.recordPayment(memberId, new PaymentRecord(
                paymentDate.toString(), amount, description.trim(), idempotencyKey));
        if (!recorded) redirect.addFlashAttribute("toast", "Payment was not recorded: request already processed or member not found.");
        else redirect.addFlashAttribute("toast", "Payment recorded. Remaining daily SMS allowance: "
                + store.getSmsRemainingToday() + ".");
        return "redirect:/calendar?month=" + YearMonth.from(paymentDate) + "&date=" + paymentDate;
    }

    /**
     * Records money in or out from the calendar under a general category. "Member dues" is recorded as a dues payment
     * on the chosen member (updating their balance); every other category becomes a MANUAL flow entry.
     */
    @PostMapping("/calendar/transactions")
    public synchronized String recordCalendarTransaction(@RequestParam String direction,
                                                         @RequestParam String category,
                                                         @RequestParam String date,
                                                         @RequestParam double amount,
                                                         @RequestParam(defaultValue = "") String description,
                                                         @RequestParam(defaultValue = "") String party,
                                                         @RequestParam(required = false) String memberId,
                                                         @RequestParam String idempotencyKey,
                                                         RedirectAttributes redirect) {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid date for the transaction.");
            return "redirect:/calendar";
        }
        String back = "redirect:/calendar?month=" + YearMonth.from(day);
        boolean inflow = "in".equals(direction);
        if (!inflow && !"out".equals(direction)) {
            redirect.addFlashAttribute("toast", "Choose whether this is money in or money out.");
            return back;
        }
        if (!(inflow ? TransactionCategories.INFLOW : TransactionCategories.OUTFLOW).contains(category)) {
            redirect.addFlashAttribute("toast", "Choose a category for this " + (inflow ? "inflow." : "outflow."));
            return back;
        }
        if (!Double.isFinite(amount) || amount <= 0 || description.length() > 200 || party.length() > 120) {
            redirect.addFlashAttribute("toast", "Enter an amount greater than zero (description up to 200 characters).");
            return back;
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            redirect.addFlashAttribute("toast", "Transaction could not be recorded: missing request identifier. Please reload and retry.");
            return back;
        }
        String amountText = String.format("$%,.2f", amount);
        if (inflow && TransactionCategories.MEMBER_DUES.equals(category)) {
            if (memberId == null || memberId.isBlank()) {
                redirect.addFlashAttribute("toast", "Choose the member who paid these dues.");
                return back;
            }
            boolean recorded = store.recordPayment(memberId, new PaymentRecord(day.toString(), amount,
                    description.isBlank() ? "Payment received" : description.trim(), idempotencyKey));
            redirect.addFlashAttribute("toast", recorded
                    ? "Dues payment of " + amountText + " recorded and the member's balance updated."
                    : "Payment was not recorded: request already processed or member not found.");
        } else {
            FlowEntry entry = new FlowEntry("manual-" + idempotencyKey, day.toString(),
                    description.isBlank() ? category : description.trim(), party.trim(),
                    inflow ? amount : -amount, "Manual entry");
            entry.setCategory(category);
            entry.setReconciliationStatus("MANUAL");
            boolean recorded = store.addManualFlowEntry(entry);
            redirect.addFlashAttribute("toast", recorded
                    ? (inflow ? "Inflow" : "Outflow") + " of " + amountText + " recorded under " + category + "."
                    : "This transaction was already recorded.");
        }
        return back + "&date=" + day;
    }

    @PostMapping("/delete-member/{id}")
    public synchronized String deleteMember(@PathVariable String id, RedirectAttributes redirect) {
        boolean removed = store.removeMember(id);
        redirect.addFlashAttribute("toast", removed ? "Member removed from roster." : "Member not found.");
        return "redirect:/";
    }
}
