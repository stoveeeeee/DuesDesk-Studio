package com.fraternity.dues;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The activity log page (every change with before and after values, and undo for recent deletions), the
 * "Template for Greekbill charges" export, the reminder sequence actions, and the notification settings.
 */
@Controller
public class ActivityController {
    static final int PAGE_SIZE = 40;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US);

    private final Store store;
    private final GreekBillTemplateService greekBill;
    private final ReminderSequenceService sequence;
    private final NotificationService notifications;

    public ActivityController(Store store, ReminderSequenceService sequence, NotificationService notifications) {
        this.store = store;
        this.greekBill = new GreekBillTemplateService(store);
        this.sequence = sequence;
        this.notifications = notifications;
    }

    /** One action as the page shows it: every entry one save recorded. */
    public record Batch(String id, LocalDateTime at, String source, List<ActivityEntry> entries, boolean undoable) {
        public String getSummary() {
            if (entries.size() == 1) return entries.getFirst().getSummary();
            ActivityEntry first = entries.getFirst();
            boolean uniform = entries.stream().allMatch(entry -> Objects.equals(entry.getEntityType(), first.getEntityType())
                    && Objects.equals(entry.getAction(), first.getAction()));
            if (uniform) {
                String verb = switch (first.getAction()) {
                    case ActivityEntry.ADDED -> "Payment".equals(first.getEntityType()) ? "recorded" : "added";
                    case ActivityEntry.REMOVED -> "removed";
                    case ActivityEntry.ARCHIVED -> "archived";
                    default -> "updated";
                };
                boolean sameItem = entries.stream().allMatch(entry -> Objects.equals(entry.getEntityLabel(), first.getEntityLabel())
                        && Objects.equals(entry.getAmount(), first.getAmount()));
                long people = entries.stream().map(ActivityEntry::getMemberName).filter(Objects::nonNull).distinct().count();
                if (sameItem && first.getParentId() != null && people > 1) {
                    return first.getEntityType() + " " + verb + " for " + people + " members: " + first.getEntityLabel()
                            + (first.getAmount() == null ? "" : " (" + ActivityRecorder.money(first.getAmount()) + " each)");
                }
                return entries.size() + " " + plural(first.getEntityType()) + " " + verb;
            }
            return first.getSummary() + " and " + (entries.size() - 1) + (entries.size() == 2 ? " more change" : " more changes");
        }

        public boolean isUndone() { return entries.stream().allMatch(ActivityEntry::isUndone); }
        public boolean isRemoval() { return entries.stream().anyMatch(ActivityEntry::isRemoval); }
        public String getTime() { return at.format(TIME); }
        public String getDay() { return at.toLocalDate().toString(); }
        public List<String> getCategories() {
            return new ArrayList<>(new LinkedHashSet<>(entries.stream().map(ActivityEntry::getCategory).filter(Objects::nonNull).toList()));
        }
        public String getIcon() {
            ActivityEntry first = entries.getFirst();
            if (first.getSource() != null && first.getSource().startsWith("Undo")) return "bi-arrow-counterclockwise";
            if (isRemoval()) return "bi-trash3";
            return switch (first.getCategory() == null ? "" : first.getCategory()) {
                case "Payments" -> "bi-cash-coin";
                case "Charges" -> "bi-receipt";
                case "Credits & discounts" -> "bi-tag";
                case "Payment plans" -> "bi-calendar-check";
                case "Balances" -> "bi-sliders";
                case "Members" -> "bi-person";
                case "Rent" -> "bi-house-door";
                case "Cash flow" -> "bi-arrow-left-right";
                case "Bills" -> "bi-calendar-event";
                case "Chair budgets" -> "bi-wallet2";
                case "Events" -> "bi-ticket-perforated";
                case "Reminders" -> "bi-bell";
                case "Imports" -> "bi-cloud-arrow-down";
                default -> "bi-gear";
            };
        }
        public String getTone() {
            if (isUndone()) return "muted";
            if (isRemoval()) return "danger";
            ActivityEntry first = entries.getFirst();
            if ("Payments".equals(first.getCategory()) || "Credits & discounts".equals(first.getCategory())) return "success";
            if ("Charges".equals(first.getCategory())) return "accent";
            return "neutral";
        }
        public String getUndoneAt() {
            return entries.stream().map(ActivityEntry::getUndoneAt).filter(Objects::nonNull).findFirst()
                    .map(value -> LocalDateTime.parse(value).format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US))).orElse("");
        }

        private static String plural(String type) {
            String lower = type == null ? "changes" : type.toLowerCase(Locale.ROOT);
            if (lower.endsWith("y")) return lower.substring(0, lower.length() - 1) + "ies";
            return lower.endsWith("s") ? lower : lower + "s";
        }
    }

    public record DayGroup(String date, String label, List<Batch> batches) {}

    public static final List<String> CATEGORIES = List.of("Payments", "Charges", "Credits & discounts", "Balances",
            "Payment plans", "Members", "Reminders", "Rent", "Cash flow", "Bills", "Chair budgets", "Events", "Imports", "Settings");

    @GetMapping("/activity")
    public String activity(@RequestParam(defaultValue = "") String category,
                           @RequestParam(defaultValue = "") String member,
                           @RequestParam(defaultValue = "last-30") String period,
                           @RequestParam(defaultValue = "") String from,
                           @RequestParam(defaultValue = "") String to,
                           @RequestParam(defaultValue = "") String q,
                           @RequestParam(defaultValue = "1") int page,
                           Model model) {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        GreekBillTemplateService.Period range = GreekBillTemplateService.resolvePeriod(period, from, to, today,
                store.getSettings().getLastRolloverDate());
        String query = q.trim().toLowerCase(Locale.ROOT);
        List<ActivityEntry> log = store.getActivityLog();

        // Group the whole log into actions first, so undo sees every entry an action made even when filtered.
        Map<String, List<ActivityEntry>> byBatch = new LinkedHashMap<>();
        for (ActivityEntry entry : log) byBatch.computeIfAbsent(entry.getBatchId(), key -> new ArrayList<>()).add(entry);

        List<Batch> batches = new ArrayList<>();
        int changes = 0;
        int removals = 0;
        int undone = 0;
        Set<String> people = new LinkedHashSet<>();
        for (Map.Entry<String, List<ActivityEntry>> item : byBatch.entrySet()) {
            List<ActivityEntry> all = item.getValue();
            ActivityEntry first = all.getFirst();
            if (!range.contains(first.getTime())) continue;
            List<ActivityEntry> shown = all.stream().filter(entry -> (category.isBlank() || category.equals(entry.getCategory()))
                    && (member.isBlank() || member.equals(entry.getMemberId()))
                    && (query.isEmpty() || matches(entry, query))).toList();
            if (shown.isEmpty()) continue;
            batches.add(new Batch(item.getKey(), first.getTime(), first.getSource(), shown, Store.undoable(all, now)));
            changes += shown.size();
            removals += (int) shown.stream().filter(ActivityEntry::isRemoval).count();
            undone += (int) shown.stream().filter(ActivityEntry::isUndone).count();
            shown.stream().map(ActivityEntry::getMemberName).filter(Objects::nonNull).forEach(people::add);
        }
        batches.sort(Comparator.comparing(Batch::at).reversed());
        int totalPages = Math.max(1, (int) Math.ceil(batches.size() / (double) PAGE_SIZE));
        int currentPage = Math.min(Math.max(1, page), totalPages);
        List<Batch> pageItems = batches.subList((currentPage - 1) * PAGE_SIZE, Math.min(currentPage * PAGE_SIZE, batches.size()));
        Map<String, DayGroup> days = new LinkedHashMap<>();
        for (Batch batch : pageItems) {
            LocalDate day = batch.at().toLocalDate();
            String label = day.equals(today) ? "Today" : day.equals(today.minusDays(1)) ? "Yesterday" : day.format(DAY);
            days.computeIfAbsent(day.toString(), key -> new DayGroup(key, label, new ArrayList<>())).batches().add(batch);
        }

        List<Member> members = new ArrayList<>(store.getMembers());
        members.sort(Comparator.comparing(m -> m.getName() == null ? "" : m.getName(), String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("days", new ArrayList<>(days.values()));
        model.addAttribute("batchCount", batches.size());
        model.addAttribute("changeCount", changes);
        model.addAttribute("removalCount", removals);
        model.addAttribute("undoneCount", undone);
        model.addAttribute("peopleCount", people.size());
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("category", category);
        model.addAttribute("memberFilter", member);
        model.addAttribute("memberName", members.stream().filter(m -> m.getId().equals(member)).map(Member::getName).findFirst().orElse(""));
        model.addAttribute("rosterMembers", members);
        model.addAttribute("period", range);
        model.addAttribute("periods", GreekBillTemplateService.PERIODS);
        model.addAttribute("q", q);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("undoWindowDays", Store.UNDO_WINDOW_DAYS);
        LocalDateTime started = store.getActivityLogStartedAt();
        model.addAttribute("logStarted", started == null ? "" : started.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)));
        model.addAttribute("settings", store.getSettings());
        return "activity";
    }

    private static boolean matches(ActivityEntry entry, String query) {
        return contains(entry.getSummary(), query) || contains(entry.getSource(), query) || contains(entry.getMemberName(), query)
                || contains(entry.getEntityLabel(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    @PostMapping("/activity/{batchId}/undo")
    public String undo(@PathVariable String batchId, @RequestParam(defaultValue = "") String back, RedirectAttributes redirect) {
        Store.UndoResult result = store.undoActivity(batchId);
        redirect.addFlashAttribute("toast", result.message());
        redirect.addFlashAttribute("toastTone", result.undone() ? "success" : "warning");
        return "redirect:" + safeBack(back, "/activity");
    }

    // ------------------------------------------------------------------ Template for Greekbill charges

    @GetMapping("/activity/greekbill-template.pdf")
    public ResponseEntity<byte[]> greekBillTemplate(@RequestParam(defaultValue = "last-30") String period,
                                                    @RequestParam(defaultValue = "") String from,
                                                    @RequestParam(defaultValue = "") String to) throws Exception {
        LocalDate today = LocalDate.now();
        GreekBillTemplateService.Period range = GreekBillTemplateService.resolvePeriod(period, from, to, today,
                store.getSettings().getLastRolloverDate());
        byte[] pdf = greekBill.pdf(greekBill.build(range, today));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + GreekBillTemplateService.fileName(range, today) + "\"")
                .body(pdf);
    }

    // ------------------------------------------------------------------ reminder sequence

    @PostMapping("/reminders/run")
    public String runSequence(RedirectAttributes redirect) {
        ReminderSequenceService.RunResult result = sequence.run(LocalDate.now());
        redirect.addFlashAttribute("toast", result.summary() + " Remaining daily SMS allowance: " + store.getSmsRemainingToday() + ".");
        return "redirect:/#smsReminders";
    }

    @PostMapping("/members/{id}/officer-review/resolve")
    public String resolveReview(@PathVariable String id, @RequestParam(defaultValue = "") String note,
                                @RequestParam(defaultValue = "") String back, RedirectAttributes redirect) {
        if (note.length() > 300) {
            redirect.addFlashAttribute("toast", "Keep the review note under 300 characters.");
            return "redirect:" + safeBack(back, "/");
        }
        boolean resolved = sequence.resolveReview(id, note);
        redirect.addFlashAttribute("toast", resolved ? "Officer review closed and noted on the member's reminder history."
                : "That member is not flagged for officer review.");
        return "redirect:" + safeBack(back, "/");
    }

    @GetMapping("/members/{id}/notices/{stepId}.pdf")
    public ResponseEntity<?> notice(@PathVariable String id, @PathVariable String stepId) throws Exception {
        Member member = store.findMemberById(id).orElse(null);
        if (member == null) return ResponseEntity.notFound().build();
        ReminderStep step = member.getReminderLog().stream().filter(item -> stepId.equals(item.getId())).findFirst().orElse(null);
        if (step == null || !ReminderStep.NOTICE.equals(step.getStage())) return ResponseEntity.notFound().build();
        byte[] pdf = new PastDueNoticeService().noticePdf(member, step, store.getSettings());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + PastDueNoticeService.fileName(member, step) + "\"")
                .body(pdf);
    }

    // ------------------------------------------------------------------ notifications

    public record AlertView(String key, String title, String body, String href, String tone) {}

    /** Current alerts for the page's browser notifications and the header bell. */
    @GetMapping(value = "/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> currentNotifications() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean enabled = store.getSettings().isNotificationsEnabled();
        body.put("enabled", enabled);
        body.put("desktop", notifications.hasDesktopNotifier());
        body.put("alerts", enabled ? notifications.currentAlerts(LocalDate.now()).stream()
                .map(alert -> new AlertView(alert.key(), alert.title(), alert.body(), alert.href(), alert.tone())).toList() : List.of());
        return body;
    }

    @PostMapping("/settings/notifications")
    public String saveNotifications(@RequestParam(defaultValue = "false") boolean notificationsEnabled,
                                    @RequestParam(defaultValue = "false") boolean notifyBills,
                                    @RequestParam(defaultValue = "3") int notifyBillDaysAhead,
                                    @RequestParam(defaultValue = "false") boolean notifyInstallments,
                                    @RequestParam(defaultValue = "false") boolean notifyBackup,
                                    RedirectAttributes redirect) {
        if (notifyBillDaysAhead < 0 || notifyBillDaysAhead > 30) {
            redirect.addFlashAttribute("toast", "Choose between 0 and 30 days of warning before a bill is due.");
            return "redirect:/settings#notifications";
        }
        synchronized (store) {
            AppSettings settings = store.getSettings();
            settings.setNotificationsEnabled(notificationsEnabled);
            settings.setNotifyBills(notifyBills);
            settings.setNotifyBillDaysAhead(notifyBillDaysAhead);
            settings.setNotifyInstallments(notifyInstallments);
            settings.setNotifyBackup(notifyBackup);
            store.save();
        }
        redirect.addFlashAttribute("toast", notificationsEnabled ? "Notification settings saved." : "Desktop notifications turned off.");
        return "redirect:/settings#notifications";
    }

    @PostMapping(value = "/settings/notifications/test", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> testNotification() {
        return Map.of("desktop", notifications.sendTest());
    }

    /** Only same-site paths are followed after a form, never another host. */
    static String safeBack(String back, String fallback) {
        if (back == null || back.isBlank() || !back.startsWith("/") || back.startsWith("//") || back.contains("\\")) return fallback;
        return back;
    }
}
