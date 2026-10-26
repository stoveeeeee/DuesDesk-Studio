package com.fraternity.dues;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Settings, setup walkthrough and Unmatched Payments screen for the Venmo email import. */
@Controller
public class VenmoEmailController {
    private static final String SETTINGS = "redirect:/settings#venmoEmail";
    private static final String UNMATCHED = "redirect:/venmo/unmatched";

    private final Store store;
    private final PaymentStore payments;
    private final MailCredentialStore credentials;
    private final VenmoEmailSyncService syncService;

    public VenmoEmailController(Store store, PaymentStore payments, MailCredentialStore credentials,
                                VenmoEmailSyncService syncService) {
        this.store = store;
        this.payments = payments;
        this.credentials = credentials;
        this.syncService = syncService;
    }

    /** One row of the Unmatched Payments screen; {@code detail} says why it is waiting. */
    public record UnmatchedRow(FlowEntry entry, boolean fromEmail, boolean verified, String detail) {}

    @PostMapping("/settings/venmo-email")
    public synchronized String saveSettings(@RequestParam(defaultValue = "imap.gmail.com") String imapHost,
                                            @RequestParam(defaultValue = "") String emailAddress,
                                            @RequestParam(defaultValue = "") String appPassword,
                                            @RequestParam(defaultValue = "") String importFrom,
                                            @RequestParam(defaultValue = "false") boolean enabled,
                                            RedirectAttributes redirect) {
        String host = imapHost.trim().toLowerCase(java.util.Locale.ROOT);
        String address = emailAddress.trim();
        if (!host.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+") || host.length() > 200) {
            redirect.addFlashAttribute("toast", "Enter the IMAP host, e.g. imap.gmail.com.");
            return SETTINGS;
        }
        if (address.isEmpty() || !DashboardController.validEmail(address)) {
            redirect.addFlashAttribute("toast", "Enter the chapter mailbox's email address.");
            return SETTINGS;
        }
        String startDate;
        try {
            startDate = importFrom.isBlank() ? LocalDate.now().toString() : LocalDate.parse(importFrom.trim()).toString();
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Enter a valid date to start importing from.");
            return SETTINGS;
        }
        // Google shows app passwords in groups of four ("abcd efgh ijkl mnop"); the spaces are not part of it.
        char[] password = appPassword.replaceAll("\\s+", "").toCharArray();
        try {
            if (password.length > 256) {
                redirect.addFlashAttribute("toast", "That app password is too long.");
                return SETTINGS;
            }
            if (password.length == 0 && enabled && !credentials.hasPassword()) {
                redirect.addFlashAttribute("toast", "Enter the mailbox app password to turn on the import.");
                return SETTINGS;
            }
            if (password.length > 0) credentials.savePassword(password);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VenmoEmailController.class).error("Could not save the mailbox app password", e);
            redirect.addFlashAttribute("toast", "The app password could not be saved securely on this computer: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return SETTINGS;
        } finally {
            Arrays.fill(password, '\0');
        }
        synchronized (store) {
            AppSettings settings = store.getSettings();
            settings.setVenmoImapHost(host);
            settings.setVenmoEmailAddress(address);
            settings.setVenmoEmailImportFrom(startDate);
            settings.setVenmoEmailEnabled(enabled);
            store.save();
        }
        redirect.addFlashAttribute("toast", enabled
                ? "Venmo email import saved and turned on. Click Test connection, or wait for the next sync."
                : "Venmo email settings saved. The import is off.");
        return SETTINGS;
    }

    @PostMapping("/settings/venmo-email/test")
    public String testConnection(RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", syncService.testConnection());
        return SETTINGS;
    }

    @PostMapping("/settings/venmo-email/sync")
    public String syncNow(@RequestParam(defaultValue = "settings") String returnTo, RedirectAttributes redirect) {
        syncService.sync();
        redirect.addFlashAttribute("toast", syncService.status().lastSyncMessage());
        return "unmatched".equals(returnTo) ? UNMATCHED : SETTINGS;
    }

    @PostMapping("/settings/venmo-email/forget-password")
    public synchronized String forgetPassword(RedirectAttributes redirect) {
        try {
            credentials.clear();
            synchronized (store) {
                store.getSettings().setVenmoEmailEnabled(false);
                store.save();
            }
            redirect.addFlashAttribute("toast", "The saved app password was removed and the Venmo email import is off.");
        } catch (Exception e) {
            redirect.addFlashAttribute("toast", "The saved app password could not be removed.");
        }
        return SETTINGS;
    }

    @GetMapping("/venmo/setup")
    public String setup(Model model) {
        model.addAttribute("mailbox", store.getSettings().getVenmoEmailAddress());
        return "venmo-setup";
    }

    @GetMapping("/venmo/unmatched")
    public String unmatched(Model model) {
        List<Member> members = payments.members().stream()
                .sorted(Comparator.comparing(member -> member.getName() == null ? "" : member.getName().toLowerCase(java.util.Locale.ROOT)))
                .toList();
        List<UnmatchedRow> rows = payments.unmatched().stream().map(entry -> row(entry, members)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("members", members);
        model.addAttribute("venmoEmail", syncService.status());
        return "venmo-unmatched";
    }

    private UnmatchedRow row(FlowEntry entry, List<Member> members) {
        Store.ProcessedEmail email = payments.emailFor(entry.getId()).orElse(null);
        if (email == null) {
            return new UnmatchedRow(entry, false, false, "Imported from a " + (entry.getSource() == null ? "statement" : entry.getSource()) + " file.");
        }
        if (!email.verified()) return new UnmatchedRow(entry, true, false, email.detail());
        String note = entry.getDescription() == null || "Venmo payment".equals(entry.getDescription()) ? "" : entry.getDescription();
        PaymentMatcher.Match match = PaymentMatcher.match(entry.getCounterparty(), note, members, payments.confirmedPayerNames());
        return new UnmatchedRow(entry, true, true, match.matched()
                ? "Now matches " + match.member().getName() + "; assign to confirm."
                : match.reason() + ".");
    }

    @PostMapping("/venmo/unmatched/{id}/assign")
    public synchronized String assign(@PathVariable String id, @RequestParam(defaultValue = "") String memberId,
                                      @RequestParam(defaultValue = "false") boolean rememberName,
                                      RedirectAttributes redirect) {
        String memberName = store.findMemberById(memberId).map(Member::getName).orElse(null);
        if (memberName == null) {
            redirect.addFlashAttribute("toast", "Choose a member to assign the payment to.");
            return UNMATCHED;
        }
        boolean assigned;
        try {
            assigned = payments.assign(id, memberId, rememberName);
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("toast", "The payment could not be saved. Nothing was changed.");
            return UNMATCHED;
        }
        redirect.addFlashAttribute("toast", assigned
                ? "Payment recorded for " + memberName + "." + (rememberName ? " Future payments from this Venmo name will be applied automatically." : "")
                : "That payment could not be assigned. It may already have been handled.");
        return UNMATCHED;
    }

    @PostMapping("/venmo/unmatched/{id}/dismiss")
    public synchronized String dismiss(@PathVariable String id, RedirectAttributes redirect) {
        boolean dismissed;
        try {
            dismissed = payments.dismiss(id);
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("toast", "The change could not be saved. Nothing was changed.");
            return UNMATCHED;
        }
        redirect.addFlashAttribute("toast", dismissed ? "Payment dismissed. It no longer counts toward any member."
                : "That payment could not be dismissed. It may already have been handled.");
        return UNMATCHED;
    }
}
