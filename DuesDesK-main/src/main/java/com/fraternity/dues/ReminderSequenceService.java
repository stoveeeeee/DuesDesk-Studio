package com.fraternity.dues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The automatic reminder sequence. For each member it follows the oldest unpaid dated charge or installment (or an
 * undated past-due balance) and escalates once per step:
 * <ol>
 *     <li>a friendly text a few days before the due date,</li>
 *     <li>a firmer text after the grace period,</li>
 *     <li>a formal past-due notice (a PDF on the member's account, plus a text),</li>
 *     <li>a flag for officer review, also raised early when a clearance vouch is about to run out.</li>
 * </ol>
 * Every step is written to the member's reminder history. A step that was skipped while the app was closed is not
 * sent late: the member gets the highest step that is due, and never a lower one after it.
 */
@Service
public class ReminderSequenceService {
    private static final Logger log = LoggerFactory.getLogger(ReminderSequenceService.class);
    static final int CLEARANCE_WARNING_DAYS = 3;

    private final Store store;
    private final SmsService smsService;

    public ReminderSequenceService(Store store, SmsService smsService) {
        this.store = store;
        this.smsService = smsService;
    }

    /** The unpaid item the sequence follows for one member. */
    public record Anchor(String key, LocalDate dueDate, double amount, String label) {}

    /** One step that is due today. */
    public record Plan(Member member, String stage, Anchor anchor, long daysPastDue, String reason) {
        public String getTitle() { return ReminderStep.title(stage); }
        public int getRank() { return ReminderStep.rank(stage); }
        public String getDueDate() { return anchor.dueDate().toString(); }
        public double getAmount() { return anchor.amount(); }
        public String getAction() {
            return switch (stage) {
                case ReminderStep.FRIENDLY, ReminderStep.FIRM -> member.isMuteReminders() ? "Skipped: reminders muted" : "Text";
                case ReminderStep.NOTICE -> member.isMuteReminders() ? "Notice PDF (no text: muted)" : "Notice PDF and text";
                default -> "Flag for officers";
            };
        }
    }

    public record RunResult(int steps, int texts, int notices, int flagged, int notSent) {
        public String summary() {
            if (steps == 0) return "No reminder steps were due today.";
            List<String> parts = new ArrayList<>();
            if (texts > 0) parts.add(texts + (texts == 1 ? " text" : " texts"));
            if (notices > 0) parts.add(notices + (notices == 1 ? " past-due notice" : " past-due notices"));
            if (flagged > 0) parts.add(flagged + (flagged == 1 ? " officer review flag" : " officer review flags"));
            String done = parts.isEmpty() ? steps + " steps" : String.join(", ", parts);
            return "Reminder sequence ran: " + done + "." + (notSent > 0 ? " " + notSent
                    + (notSent == 1 ? " text was" : " texts were") + " not sent and will be tried again on the next run." : "");
        }
    }

    // ------------------------------------------------------------------ planning

    /** The item the sequence follows: the earliest unpaid dated charge or installment, else an undated past-due balance. */
    static Anchor anchor(Member member, LocalDate today) {
        if (!Money.positive(member.getTotalOwed())) return null;
        Anchor best = null;
        for (ChargeRecord charge : member.getCharges() == null ? List.<ChargeRecord>of() : member.getCharges()) {
            LocalDate due = parse(charge.getDueDate());
            if (due == null || !Money.positive(charge.getRemainingAmount())) continue;
            if (best == null || due.isBefore(best.dueDate())) {
                best = new Anchor("charge:" + charge.getId(), due, charge.getRemainingAmount(),
                        charge.getDescription() == null || charge.getDescription().isBlank() ? "Dues charge" : charge.getDescription());
            }
        }
        for (PaymentInstallment installment : member.getInstallments() == null ? List.<PaymentInstallment>of() : member.getInstallments()) {
            LocalDate due = parse(installment.getDueDate());
            if (due == null || installment.isPaid()) continue;
            if (best == null || due.isBefore(best.dueDate())) {
                best = new Anchor("installment:" + installment.getId(), due, installment.getRemainingAmount(),
                        installment.getDescription() == null || installment.getDescription().isBlank()
                                ? "Payment plan installment" : installment.getDescription());
            }
        }
        if (best != null) return best;
        if (Money.positive(member.getPastDueBalance())) {
            LocalDate since = parse(member.getPastDueSince());
            if (since == null) since = today;
            return new Anchor("pastdue:" + since, since, member.getPastDueBalance(), "Past-due balance");
        }
        return null;
    }

    /** The step due today for one member, or null. */
    Plan plan(Member member, LocalDate today) {
        Anchor anchor = anchor(member, today);
        if (anchor == null) return null;
        AppSettings settings = store.getSettings();
        long days = ChronoUnit.DAYS.between(anchor.dueDate(), today);
        List<ReminderStep> episode = member.getReminderLog().stream()
                .filter(step -> anchor.key().equals(step.getEpisodeKey())).toList();
        ReminderStep last = episode.stream().filter(step -> !ReminderStep.REVIEW.equals(step.getStage()))
                .max(Comparator.comparingInt(ReminderStep::getRank)).orElse(null);
        int sent = last == null ? 0 : last.getRank();
        boolean reviewed = episode.stream().anyMatch(step -> ReminderStep.REVIEW.equals(step.getStage()));

        // The steps run in order and never skip ahead: each later step waits its threshold after the due date, and
        // also the configured gap after the step before it, so a member always hears from the chapter before an
        // account is escalated (even when the sequence is first switched on for long-overdue accounts).
        String stage = null;
        String reason = null;
        if (sent == 0 && settings.isSequenceFriendlyEnabled() && days <= 0
                && days >= -Math.max(0, settings.getReminderDaysBeforeDue()) && !anchor.key().startsWith("pastdue:")) {
            stage = ReminderStep.FRIENDLY;
            reason = days == 0 ? "Due today" : "Due in " + (-days) + (days == -1 ? " day" : " days");
        } else if (sent < 2 && settings.isSequenceFirmEnabled() && days >= settings.getSequenceFirmDays()) {
            stage = ReminderStep.FIRM;
            reason = days + " days past due, past the grace period";
        } else if (sent < 3 && settings.isSequenceNoticeEnabled() && days >= settings.getSequenceNoticeDays()
                && waited(last, today, settings.getSequenceNoticeDays() - (settings.isSequenceFirmEnabled() ? settings.getSequenceFirmDays() : 0))) {
            stage = ReminderStep.NOTICE;
            reason = days + " days past due";
        } else if (!reviewed && !member.isOfficerReviewFlagged() && settings.isSequenceReviewEnabled()
                && days >= settings.getSequenceReviewDays()
                && waited(last, today, settings.getSequenceReviewDays() - previousThreshold(settings))) {
            stage = ReminderStep.REVIEW;
            reason = days + " days past due on " + anchor.label();
        }
        // A clearance vouch about to run out brings officer review forward, whatever step the texts are on.
        if (stage == null && !reviewed && !member.isOfficerReviewFlagged() && settings.isSequenceReviewEnabled()
                && Money.positive(member.getPastDueBalance())) {
            LocalDate vouch = parse(member.getClearedUntilDate());
            if (vouch != null && !vouch.isBefore(today) && !vouch.isAfter(today.plusDays(CLEARANCE_WARNING_DAYS))) {
                stage = ReminderStep.REVIEW;
                reason = "Social clearance vouch ends " + vouch + " with " + ExecutivePdf.money(member.getPastDueBalance()) + " past due";
            }
        }
        if (stage == null) return null;
        return new Plan(member, stage, anchor, Math.max(0, days), reason);
    }

    /** True when at least {@code gap} days have passed since the previous step (or there was none). */
    private static boolean waited(ReminderStep previous, LocalDate today, int gap) {
        if (previous == null) return true;
        LocalDate ran = parse(previous.getDay());
        return ran == null || !today.isBefore(ran.plusDays(Math.max(1, gap)));
    }

    private static int previousThreshold(AppSettings settings) {
        if (settings.isSequenceNoticeEnabled()) return settings.getSequenceNoticeDays();
        if (settings.isSequenceFirmEnabled()) return settings.getSequenceFirmDays();
        return 0;
    }

    /** Every step that would run today, most serious first. */
    public List<Plan> preview(LocalDate today) {
        List<Plan> plans = new ArrayList<>();
        for (Member member : store.getMembers()) {
            Plan plan = plan(member, today);
            if (plan != null) plans.add(plan);
        }
        plans.sort(Comparator.comparingInt(Plan::getRank).reversed()
                .thenComparing(plan -> plan.member().getName() == null ? "" : plan.member().getName(), String.CASE_INSENSITIVE_ORDER));
        return plans;
    }

    /** Members flagged for officer review, longest-flagged first. */
    public List<Member> flaggedMembers() {
        return store.getMembers().stream().filter(Member::isOfficerReviewFlagged)
                .sorted(Comparator.comparing(Member::getOfficerReviewSince)).toList();
    }

    // ------------------------------------------------------------------ running

    /** Runs every step due today and records each on the member's account. */
    public synchronized RunResult run(LocalDate today) {
        return Store.withActivitySource("Automatic reminder sequence", () -> Store.asOneActivity(() -> runSteps(today)));
    }

    private RunResult runSteps(LocalDate today) {
        int steps = 0;
        int texts = 0;
        int notices = 0;
        int flagged = 0;
        int notSent = 0;
        AppSettings settings = store.getSettings();
        boolean changed = closeResolvedReviews(today);
        for (Plan plan : preview(today)) {
            Member member = plan.member();
            String now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
            String outcome;
            String detail = plan.reason();
            switch (plan.stage()) {
                case ReminderStep.FRIENDLY, ReminderStep.FIRM -> {
                    if (member.isMuteReminders()) {
                        outcome = "Not texted: reminders are muted for this member";
                        break;
                    }
                    String template = ReminderStep.FRIENDLY.equals(plan.stage()) ? settings.getSmsTemplate() : settings.getSequenceFirmTemplate();
                    String body = smsService.formatTemplate(template, member, plan.getDueDate(), plan.anchor().label(), plan.getAmount());
                    SmsService.SendOutcome sent = smsService.sendSequenceText(member, body);
                    if (!sent.sent()) {
                        notSent++;
                        continue;
                    }
                    texts++;
                    outcome = sent.outcome();
                    detail = body;
                }
                case ReminderStep.NOTICE -> {
                    notices++;
                    outcome = "Notice ready to print or send";
                    if (!member.isMuteReminders()) {
                        String body = smsService.formatTemplate(settings.getSequenceNoticeTemplate(), member, plan.getDueDate(),
                                plan.anchor().label(), plan.getAmount());
                        SmsService.SendOutcome sent = smsService.sendSequenceText(member, body);
                        if (sent.sent()) texts++;
                        else notSent++;
                        outcome += sent.sent() ? "; " + sent.outcome().toLowerCase(java.util.Locale.ROOT) : "; text not sent";
                    }
                }
                default -> {
                    flagged++;
                    outcome = "Flagged for officer review";
                    synchronized (store) {
                        member.setOfficerReviewSince(today.toString());
                        member.setOfficerReviewReason(plan.reason());
                    }
                }
            }
            synchronized (store) {
                if (plan.anchor().key().startsWith("pastdue:") && (member.getPastDueSince() == null || member.getPastDueSince().isBlank())) {
                    member.setPastDueSince(plan.getDueDate());
                }
                member.getReminderLog().add(new ReminderStep(UUID.randomUUID().toString(), now, plan.stage(), plan.anchor().key(),
                        plan.getDueDate(), plan.getAmount(), member.getTotalOwed(), member.getPastDueBalance(), outcome,
                        ActivityRecorder.clip(detail, 1000)));
            }
            steps++;
            changed = true;
        }
        if (changed) {
            synchronized (store) {
                store.save();
            }
        }
        return new RunResult(steps, texts, notices, flagged, notSent);
    }

    /** Remembers when an undated past-due balance appeared, and clears flags once the account is current again. */
    private boolean closeResolvedReviews(LocalDate today) {
        boolean changed = false;
        synchronized (store) {
            for (Member member : store.getMembers()) {
                if (!Money.positive(member.getPastDueBalance()) && member.getPastDueSince() != null) {
                    member.setPastDueSince(null);
                    changed = true;
                }
                if (Money.positive(member.getPastDueBalance()) && member.getPastDueSince() == null && anchor(member, today) != null
                        && anchor(member, today).key().startsWith("pastdue:")) {
                    member.setPastDueSince(today.toString());
                    changed = true;
                }
                if (member.isOfficerReviewFlagged() && !Money.positive(member.getPastDueBalance())) {
                    resolve(member, "Account brought current");
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Clears an officer review flag and notes why on the member's reminder history. */
    public boolean resolveReview(String memberId, String note) {
        synchronized (store) {
            Member member = store.findMemberById(memberId).orElse(null);
            if (member == null || !member.isOfficerReviewFlagged()) return false;
            resolve(member, note == null || note.isBlank() ? "Reviewed by an officer" : "Reviewed: " + note.trim());
            store.save();
            return true;
        }
    }

    private void resolve(Member member, String detail) {
        String now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        member.getReminderLog().add(new ReminderStep(UUID.randomUUID().toString(), now, ReminderStep.RESOLVED, "review",
                null, 0, member.getTotalOwed(), member.getPastDueBalance(), "Officer review closed",
                ActivityRecorder.clip(detail, 300)));
        member.setOfficerReviewSince(null);
        member.setOfficerReviewReason(null);
    }

    // ------------------------------------------------------------------ schedule

    /** Once a day, at or after the configured time (catching up if the app was closed then), when automatic reminders are on. */
    @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
    public void runOnSchedule() {
        LocalDate today = LocalDate.now();
        String sendTime;
        synchronized (store) {
            AppSettings settings = store.getSettings();
            if (!settings.isAutoSendEnabled() || today.toString().equals(settings.getLastAutoRunDate())) return;
            sendTime = settings.getSendTime();
        }
        LocalTime target;
        try {
            target = LocalTime.parse(sendTime);
        } catch (DateTimeParseException | NullPointerException e) {
            target = LocalTime.of(10, 0);
        }
        if (LocalTime.now().isBefore(target)) return;
        try {
            RunResult result = run(today);
            log.info(result.summary());
        } catch (RuntimeException e) {
            log.error("The automatic reminder sequence failed", e);
        } finally {
            synchronized (store) {
                store.getSettings().setLastAutoRunDate(today.toString());
                try {
                    store.save();
                } catch (RuntimeException e) {
                    log.warn("Unable to record the reminder sequence run date", e);
                }
            }
        }
    }

    static LocalDate parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
