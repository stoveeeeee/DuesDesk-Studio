package com.fraternity.dues;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.YearMonth;
import java.util.stream.Collectors;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class Store {
    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private static final List<String> CHAIR_ACCENT_COLORS = List.of(
            "#2563EB", "#4F46E5", "#7C3AED", "#C026D3", "#BE123C", "#C2410C",
            "#A16207", "#047857", "#0F766E", "#0E7490", "#DB2777", "#475569");
    @org.springframework.beans.factory.annotation.Value("${store.file.path}") private String DATA_FILE;
    private final ObjectMapper mapper = new ObjectMapper();

    private DataContainer data = new DataContainer();
    private Map<String, Member> membersById = Map.of();
    private Set<String> paymentKeys = Set.of();
    private TwilioCredentials twilioCredentials;
    @org.springframework.beans.factory.annotation.Value("${sms.daily-cap:100}") private int smsDailyCap = 100;

    public static class DataContainer {
        public List<Member> members = new ArrayList<>();
        public AppSettings settings = new AppSettings();
        public List<SmsLog> smsLogs = new ArrayList<>();
        public List<Resident> residents = new ArrayList<>();
        public List<FlowEntry> flowEntries = new ArrayList<>();
        public List<MetricSnapshot> metricHistory = new ArrayList<>();
        public Map<String, String> counterpartyAliases = new LinkedHashMap<>();
        public List<ChairBudget> chairBudgets = new ArrayList<>();
        public List<PresaleCampaign> presaleCampaigns = new ArrayList<>();
        public List<RecurringBill> recurringBills = new ArrayList<>();
        /** Members moved off the active roster by a semester rollover; their payment history still counts in reports. */
        public List<Member> archivedMembers = new ArrayList<>();
        /** Chair budget terms closed out by a semester rollover. */
        public List<ChairBudgetPeriod> chairBudgetHistory = new ArrayList<>();
        public Map<String, String> receiptFiles = new LinkedHashMap<>();
        public String smsUsageDate = "";
        public int smsUsageCount;
    }

    public record MetricSnapshot(String date, Map<String, Double> values) {}
    public record TwilioCredentials(String accountSid, String authToken, String fromNumber, boolean enabled) {}

    @PostConstruct
    public void init() {
        // No STORE_FILE_PATH configured: use the per-OS app-data folder (or the legacy ~/.fraternity-dues if it has data).
        if (DATA_FILE == null || DATA_FILE.isBlank()) DATA_FILE = AppPaths.defaultDataFile().toString();
        try {
            Files.createDirectories(Path.of(DATA_FILE).toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            log.warn("Unable to create the data folder for {}", DATA_FILE, e);
        }
        File file = new File(DATA_FILE);
        if (file.exists()) {
            try {
                DataContainer loaded = mapper.readValue(file, DataContainer.class);
                if (loaded != null) {
                    this.data = loaded;
                }
            } catch (Exception e) {
                log.error("Unable to read dues data file {}", file, e);
                restoreBackupOrFail(file.toPath(), e);
            }
        } else {
            Path backup = backupPath(file.toPath());
            if (Files.exists(backup)) {
                restoreBackupOrFail(file.toPath(), null);
            } else {
                seedSampleData();
            }
        }

        ensureNotNull();
        normalizeMembers();
        // At startup a damaged receipt is skipped, not fatal: it must never stop the app from opening.
        restoreReceiptFilesFromData(false);
        refreshMemberIndex();
        if (autoLinkResidents() > 0) {
            try {
                save();
            } catch (RuntimeException e) {
                log.warn("Unable to save automatic resident links", e);
            }
        }
        captureMetricSnapshot();
        loadTwilioCredentials();
        // Automatic bills that fell due while the app was closed are recorded now.
        try {
            processRecurringBills(LocalDate.now());
        } catch (RuntimeException e) {
            log.warn("Unable to record due automatic bills at startup", e);
        }
    }

    private void ensureNotNull() {
        if (this.data == null) this.data = new DataContainer();
        if (this.data.members == null) this.data.members = new ArrayList<>();
        if (this.data.settings == null) this.data.settings = new AppSettings();
        if (this.data.smsLogs == null) this.data.smsLogs = new ArrayList<>();
        if (this.data.residents == null) this.data.residents = new ArrayList<>();
        if (this.data.flowEntries == null) this.data.flowEntries = new ArrayList<>();
        if (this.data.metricHistory == null) this.data.metricHistory = new ArrayList<>();
        if (this.data.counterpartyAliases == null) this.data.counterpartyAliases = new LinkedHashMap<>();
        if (this.data.chairBudgets == null) this.data.chairBudgets = new ArrayList<>();
        if (this.data.presaleCampaigns == null) this.data.presaleCampaigns = new ArrayList<>();
        if (this.data.recurringBills == null) this.data.recurringBills = new ArrayList<>();
        if (this.data.archivedMembers == null) this.data.archivedMembers = new ArrayList<>();
        if (this.data.chairBudgetHistory == null) this.data.chairBudgetHistory = new ArrayList<>();
        if (this.data.receiptFiles == null) this.data.receiptFiles = new LinkedHashMap<>();
        for (ChairBudget budget : this.data.chairBudgets) {
            if (budget.getReimbursements() == null) budget.setReimbursements(new ArrayList<>());
            if (normalizeChairAccent(budget.getAccentColor()) == null) {
                budget.setAccentColor(nextChairAccentColor());
            } else {
                budget.setAccentColor(normalizeChairAccent(budget.getAccentColor()));
            }
        }
        if (this.data.smsUsageDate == null) this.data.smsUsageDate = "";
    }

    private void normalizeMembers() {
        for (Member member : data.members) {
            if (member.getPaymentHistory() == null) member.setPaymentHistory(new ArrayList<>());
            if (member.getCharges() == null) member.setCharges(new ArrayList<>());
            if (member.getInstallments() == null) member.setInstallments(new ArrayList<>());
            if (member.getCredits() == null) member.setCredits(new ArrayList<>());
            // Balances were rounded to cents as they loaded; re-derive the status so a save written before
            // rounding existed (e.g. "Payment Plan" on a $0.00 balance) shows the right state.
            member.setStatus(determineStatus(member.getTotalOwed(), member.getPastDueBalance()));
            for (int i = 0; i < member.getPaymentHistory().size(); i++) {
                PaymentRecord payment = member.getPaymentHistory().get(i);
                if (payment.getIdempotencyKey() == null || payment.getIdempotencyKey().isBlank()) {
                    String identity = member.getId() + "|" + i + "|" + payment.getDate() + "|"
                            + payment.getAmount() + "|" + payment.getDescription();
                    payment.setIdempotencyKey("legacy-" + UUID.nameUUIDFromBytes(
                            identity.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
    }

    public synchronized void save() {
        ensureNotNull();
        captureMetricSnapshot();
        Path target = Path.of(DATA_FILE).toAbsolutePath();
        Path temporary = null;
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            mapper.writeValue(temporary.toFile(), data);
            if (Files.exists(target)) {
                Files.copy(target, backupPath(target), StandardCopyOption.REPLACE_EXISTING);
            }
            moveAtomically(temporary, target);
        } catch (Exception e) {
            log.error("Unable to save dues data file {}", target, e);
            throw new IllegalStateException("Could not save dues data. Check the data directory and disk space.", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception e) {
                    log.warn("Unable to remove temporary store file {}", temporary, e);
                }
            }
        }
    }

    private void restoreBackupOrFail(Path target, Exception originalFailure) {
        Path backup = backupPath(target);
        try {
            if (!Files.exists(backup)) {
                throw new IllegalStateException("Dues data is unreadable and no backup is available: " + target,
                        originalFailure);
            }
            DataContainer restored = mapper.readValue(backup.toFile(), DataContainer.class);
            if (restored == null) throw new IllegalStateException("Dues data backup is empty: " + backup);
            data = restored;
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Restored dues data from backup {}", backup);
            save();
        } catch (Exception restoreFailure) {
            if (restoreFailure instanceof IllegalStateException stateFailure) throw stateFailure;
            log.error("Unable to restore dues data backup {}", backup, restoreFailure);
            throw new IllegalStateException("Dues data and its backup are unreadable. No data was overwritten.",
                    restoreFailure);
        }
    }

    private Path backupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }

    public synchronized TwilioCredentials getTwilioCredentials() {
        return twilioCredentials;
    }

    public synchronized boolean hasTwilioCredentials() {
        return twilioCredentials != null;
    }

    public synchronized boolean isTwilioEnabled() {
        return twilioCredentials != null && twilioCredentials.enabled();
    }

    public synchronized void saveTwilioCredentials(TwilioCredentials credentials) {
        Path target = twilioCredentialsPath();
        Path temporary = null;
        try {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "twilio-", ".tmp");
            setOwnerOnlyFilePermissions(temporary);
            mapper.writeValue(temporary.toFile(), credentials);
            moveAtomically(temporary, target);
            setOwnerOnlyFilePermissions(target);
            twilioCredentials = credentials;
        } catch (Exception e) {
            log.error("Unable to save Twilio credentials file {}", target, e);
            throw new IllegalStateException("Could not securely save Twilio settings.", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception e) {
                    log.warn("Unable to remove temporary Twilio settings file {}", temporary, e);
                }
            }
        }
    }

    public synchronized void clearTwilioCredentials() {
        Path target = twilioCredentialsPath();
        try {
            Files.deleteIfExists(target);
            twilioCredentials = null;
        } catch (Exception e) {
            log.error("Unable to remove Twilio credentials file {}", target, e);
            throw new IllegalStateException("Could not remove the saved Twilio settings.", e);
        }
    }

    private void loadTwilioCredentials() {
        Path target = twilioCredentialsPath();
        if (!Files.exists(target)) return;
        try {
            twilioCredentials = mapper.readValue(target.toFile(), TwilioCredentials.class);
            setOwnerOnlyFilePermissions(target);
        } catch (Exception e) {
            log.error("Unable to read Twilio credentials file {}", target, e);
            throw new IllegalStateException("Saved Twilio settings could not be read.", e);
        }
    }

    private Path twilioCredentialsPath() {
        return Path.of(DATA_FILE).toAbsolutePath().resolveSibling(
                Path.of(DATA_FILE).getFileName() + ".twilio.json");
    }

    private void setOwnerOnlyFilePermissions(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            log.debug("Owner-only POSIX permissions are not supported for {}", path);
        }
    }

    private void moveAtomically(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void refreshMemberIndex() {
        Map<String, Member> index = new HashMap<>();
        Set<String> recordedPaymentKeys = new HashSet<>();
        for (Member member : data.members) {
            if (member.getId() != null) index.put(member.getId(), member);
            if (member.getPaymentHistory() != null) {
                for (PaymentRecord payment : member.getPaymentHistory()) {
                    if (payment.getIdempotencyKey() != null) recordedPaymentKeys.add(payment.getIdempotencyKey());
                }
            }
        }
        membersById = Map.copyOf(index);
        paymentKeys = Set.copyOf(recordedPaymentKeys);
    }

    private void seedSampleData() {
        ensureNotNull();
        data.members.clear();
        data.members.add(new Member(UUID.randomUUID().toString(), "Marcus Vance", "555-234-5678", 850.00, 250.00, 150.00, "Delinquent", "Never", false));
        data.members.add(new Member(UUID.randomUUID().toString(), "Tyler Brooks", "555-345-6789", 400.00, 0.00, 450.00, "Payment Plan", "Never", false));
        data.members.add(new Member(UUID.randomUUID().toString(), "Ryan Mitchell", "555-456-7890", 900.00, 450.00, 0.00, "Delinquent", "Never", false));
        data.members.add(new Member(UUID.randomUUID().toString(), "Grant Hayes", "555-567-8901", 0.00, 0.00, 900.00, "Paid in Full", "Never", false));
        data.members.add(new Member(UUID.randomUUID().toString(), "Cole Henderson", "555-678-9012", 600.00, 200.00, 250.00, "Delinquent", "Never", true));
        save();
    }

    public synchronized List<Member> getMembers() {
        ensureNotNull();
        refreshPastDueCharges();
        applyLateFees();
        return new ArrayList<>(data.members);
    }

    private void applyLateFees() {
        AppSettings settings = data.settings;
        if (!settings.isLateFeesEnabled() || settings.getLateFeeGraceDays() < 0
                || !Double.isFinite(settings.getLateFeeAmount()) || settings.getLateFeeAmount() <= 0) return;
        LocalDate today = LocalDate.now();
        boolean changed = false;
        for (Member member : data.members) {
            if (member.getCharges() == null) continue;
            for (ChargeRecord charge : member.getCharges()) {
                if (charge.getId() == null || charge.getId().isBlank()) {
                    charge.setId(UUID.randomUUID().toString());
                    changed = true;
                }
            }
            Set<String> assessedChargeIds = member.getCharges().stream()
                    .map(ChargeRecord::getLateFeeForChargeId).filter(Objects::nonNull).collect(Collectors.toSet());
            List<ChargeRecord> newFees = new ArrayList<>();
            for (ChargeRecord charge : member.getCharges()) {
                if (charge.getLateFeeForChargeId() != null || assessedChargeIds.contains(charge.getId())
                        || charge.getDueDate() == null
                        || charge.getRemainingAmount() <= 0.005) continue;
                LocalDate dueDate;
                try {
                    dueDate = LocalDate.parse(charge.getDueDate());
                } catch (DateTimeParseException e) {
                    log.warn("Skipping late fee for charge {} with invalid due date", charge.getId());
                    continue;
                }
                if (dueDate.plusDays(settings.getLateFeeGraceDays()).isAfter(today)) continue;
                double amount = "PERCENT".equals(settings.getLateFeeType())
                        ? charge.getRemainingAmount() * settings.getLateFeeAmount() / 100.0
                        : settings.getLateFeeAmount();
                amount = Math.round(amount * 100.0) / 100.0;
                if (!Double.isFinite(amount) || amount <= 0) continue;
                ChargeRecord fee = new ChargeRecord(UUID.randomUUID().toString(), today.toString(),
                        "Late fee: " + charge.getDescription(), amount, amount);
                fee.setDueDate(today.toString());
                fee.setLateFeeForChargeId(charge.getId());
                newFees.add(fee);
                member.setTotalOwed(member.getTotalOwed() + amount);
                member.setPastDueBalance(member.getPastDueBalance() + amount);
                changed = true;
            }
            if (!newFees.isEmpty()) {
                member.getCharges().addAll(newFees);
                member.setStatus(determineStatus(member.getTotalOwed(), member.getPastDueBalance()));
            }
        }
        if (changed) save();
    }

    /**
     * The house resident who is this member: the one linked to them, or (only among residents nobody has linked or
     * marked as a non-member yet) one whose trimmed, case-insensitive name matches.
     */
    public synchronized Optional<Resident> residentForMember(Member member) {
        ensureNotNull();
        if (member == null) return Optional.empty();
        Optional<Resident> linked = data.residents.stream()
                .filter(resident -> member.getId().equals(resident.getLinkedMemberId())).findFirst();
        if (linked.isPresent()) return linked;
        String memberName = normalizeName(member.getName());
        if (memberName.isEmpty()) return Optional.empty();
        return data.residents.stream()
                .filter(resident -> !resident.isLinkDecided() && normalizeName(resident.getName()).equals(memberName))
                .findFirst();
    }

    /**
     * Links residents nobody has decided about yet to the one roster member with the same name (trimmed,
     * case-insensitive). Returns how many were linked. Ambiguous names stay unlinked for the treasurer to pick.
     */
    public synchronized int autoLinkResidents() {
        ensureNotNull();
        int linked = 0;
        for (Resident resident : data.residents) {
            if (resident.isLinkDecided()) continue;
            String name = normalizeName(resident.getName());
            if (name.isEmpty()) continue;
            List<Member> matches = data.members.stream().filter(member -> normalizeName(member.getName()).equals(name)).toList();
            if (matches.size() == 1) {
                resident.setLinkedMemberId(matches.getFirst().getId());
                linked++;
            }
        }
        return linked;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    public synchronized boolean isSociallyCleared(Member member) {
        if (member == null) return false;
        AppSettings settings = getSettings();
        if (member.getClearedUntilDate() != null) {
            try {
                if (!LocalDate.parse(member.getClearedUntilDate()).isBefore(LocalDate.now())) return true;
            } catch (DateTimeParseException e) {
                log.warn("Ignoring invalid social clearance date for member {}", member.getId());
            }
        }
        Resident matchedResident = settings.isSocialClearanceDuesOnly() ? null : residentForMember(member).orElse(null);
        double unpaid = member.getTotalOwed() + (matchedResident == null ? 0 : matchedResident.getBalanceDue());
        if (unpaid <= settings.getSocialClearanceTolerance() + 0.005) return true;
        return "Payment Plan".equalsIgnoreCase(member.getStatus())
                && member.getInstallments() != null && !member.getInstallments().isEmpty()
                && member.getInstallments().stream().allMatch(installment -> {
                    try {
                        if (installment.isPaid()) return true;
                        if (installment.getDueDate() == null) return false;
                        LocalDate.parse(installment.getDueDate());
                        return !installment.isOverdue();
                    } catch (DateTimeParseException e) {
                        return false;
                    }
                })
                && (matchedResident == null
                    || matchedResident.getBalanceDue() <= settings.getSocialClearanceTolerance() + 0.005);
    }

    public synchronized List<ChairBudget> getChairBudgets() {
        ensureNotNull();
        refreshChairEventLinks();
        return new ArrayList<>(data.chairBudgets);
    }

    /** Recomputes each chair's linked events (Events page events assigned to it), so its totals include them. */
    private void refreshChairEventLinks() {
        Map<String, List<ChairBudget.EventLink>> byChair = new HashMap<>();
        for (PresaleCampaign campaign : data.presaleCampaigns) {
            if (campaign.getChairBudgetId() == null) continue;
            byChair.computeIfAbsent(campaign.getChairBudgetId(), key -> new ArrayList<>())
                    .add(new ChairBudget.EventLink(campaign.getId(), campaign.getName(), campaign.getEventDate(),
                            campaign.isCompleted(), campaign.getGrossRevenue(), campaign.getExpenseTotal()));
        }
        for (ChairBudget budget : data.chairBudgets) {
            budget.linkEvents(byChair.getOrDefault(budget.getId(), List.of()));
        }
    }

    /** null keeps the current holder, blank means no member; otherwise the id must be on the roster. */
    private String resolveChairMember(String chairMemberId, String current) {
        if (chairMemberId == null) return current;
        String id = chairMemberId.trim();
        if (id.isEmpty()) return null;
        if (!membersById.containsKey(id)) throw new IllegalArgumentException("Choose a member from the roster, or No member.");
        return id;
    }

    private static String validateChairDetails(String chairName, double budgetCap, double raiseGoal) {
        String normalized = chairName == null ? "" : chairName.trim();
        if (normalized.isBlank() || normalized.length() > 80 || !Double.isFinite(budgetCap) || budgetCap < 0) {
            throw new IllegalArgumentException("Enter a chair name and a valid non-negative budget cap.");
        }
        if (!Double.isFinite(raiseGoal) || raiseGoal < 0) {
            throw new IllegalArgumentException("Enter a money raised goal of zero or more.");
        }
        return normalized;
    }

    private static String normalizeChairAccent(String accentColor) {
        String normalized = accentColor == null ? "" : accentColor.trim();
        return normalized.matches("#[0-9a-fA-F]{6}") ? normalized.toUpperCase(Locale.ROOT) : null;
    }

    private String nextChairAccentColor() {
        Set<String> used = new HashSet<>();
        for (ChairBudget budget : data.chairBudgets) {
            String color = normalizeChairAccent(budget.getAccentColor());
            if (color != null) used.add(color);
        }
        for (String color : CHAIR_ACCENT_COLORS) {
            if (!used.contains(color)) return color;
        }
        return CHAIR_ACCENT_COLORS.get(data.chairBudgets.size() % CHAIR_ACCENT_COLORS.size());
    }

    public synchronized ChairBudget saveChairBudget(String chairName, double budgetCap) {
        return saveChairBudget(chairName, budgetCap, 0);
    }

    /**
     * Creates a chair budget, or updates the one with the same name (case-insensitive). {@code raiseGoal} is the
     * optional money-raised goal for chairs that bring money in as well as spend it; 0 means no goal.
     */
    public synchronized ChairBudget saveChairBudget(String chairName, double budgetCap, double raiseGoal) {
        return saveChairBudget(chairName, budgetCap, raiseGoal, null);
    }

    /**
     * Creates a chair budget (or updates the one with the same name) and assigns the member who holds it:
     * {@code chairMemberId} blank means no member, null leaves an existing chair's holder unchanged.
     */
    public synchronized ChairBudget saveChairBudget(String chairName, double budgetCap, double raiseGoal, String chairMemberId) {
        ensureNotNull();
        String normalized = validateChairDetails(chairName, budgetCap, raiseGoal);
        ChairBudget budget = data.chairBudgets.stream()
                .filter(item -> item.getChairName().equalsIgnoreCase(normalized)).findFirst().orElse(null);
        String holder = resolveChairMember(chairMemberId, budget == null ? null : budget.getChairMemberId());
        double previousCap = budget == null ? 0 : budget.getBudgetCap();
        double previousGoal = budget == null ? 0 : budget.getRaiseGoal();
        String previousHolder = budget == null ? null : budget.getChairMemberId();
        boolean created = budget == null;
        if (budget == null) {
            budget = new ChairBudget(UUID.randomUUID().toString(), normalized, budgetCap);
            budget.setRaiseGoal(raiseGoal);
            budget.setAccentColor(nextChairAccentColor());
            budget.setChairMemberId(holder);
            data.chairBudgets.add(budget);
        } else {
            budget.setBudgetCap(budgetCap);
            budget.setRaiseGoal(raiseGoal);
            budget.setChairMemberId(holder);
        }
        try {
            save();
        } catch (IllegalStateException e) {
            if (created) data.chairBudgets.remove(budget);
            else {
                budget.setBudgetCap(previousCap);
                budget.setRaiseGoal(previousGoal);
                budget.setChairMemberId(previousHolder);
            }
            throw e;
        }
        return budget;
    }

    /** Updates a chair by id while preserving its charges, money raised, receipts, and stable accent. */
    public synchronized ChairBudget updateChairBudget(String budgetId, String chairName, double budgetCap,
                                                      double raiseGoal, String accentColor) {
        return updateChairBudget(budgetId, chairName, budgetCap, raiseGoal, accentColor, null);
    }

    /** As above, also changing the member who holds the chair (blank for no member, null to leave it unchanged). */
    public synchronized ChairBudget updateChairBudget(String budgetId, String chairName, double budgetCap,
                                                      double raiseGoal, String accentColor, String chairMemberId) {
        ensureNotNull();
        ChairBudget budget = data.chairBudgets.stream()
                .filter(item -> item.getId().equals(budgetId)).findFirst().orElse(null);
        if (budget == null) throw new IllegalArgumentException("That chair position no longer exists.");
        String normalized = validateChairDetails(chairName, budgetCap, raiseGoal);
        boolean duplicateName = data.chairBudgets.stream().anyMatch(item ->
                !budgetId.equals(item.getId()) && normalized.equalsIgnoreCase(item.getChairName()));
        if (duplicateName) throw new IllegalArgumentException("Another chair position already uses that name.");
        String normalizedAccent = accentColor == null || accentColor.isBlank()
                ? budget.getAccentColor() : normalizeChairAccent(accentColor);
        if (normalizeChairAccent(normalizedAccent) == null) {
            throw new IllegalArgumentException("Choose a valid card color.");
        }

        String previousName = budget.getChairName();
        double previousCap = budget.getBudgetCap();
        double previousGoal = budget.getRaiseGoal();
        String previousAccent = budget.getAccentColor();
        String previousHolder = budget.getChairMemberId();
        String holder = resolveChairMember(chairMemberId, previousHolder);
        budget.setChairName(normalized);
        budget.setBudgetCap(budgetCap);
        budget.setRaiseGoal(raiseGoal);
        budget.setAccentColor(normalizeChairAccent(normalizedAccent));
        budget.setChairMemberId(holder);
        saveOrRollback(() -> {
            budget.setChairName(previousName);
            budget.setBudgetCap(previousCap);
            budget.setRaiseGoal(previousGoal);
            budget.setAccentColor(previousAccent);
            budget.setChairMemberId(previousHolder);
        });
        return budget;
    }

    /** Deletes a chair and its activity, then removes only receipt files not referenced by another chair. */
    public synchronized boolean deleteChairBudget(String budgetId) {
        ensureNotNull();
        ChairBudget budget = data.chairBudgets.stream()
                .filter(item -> item.getId().equals(budgetId)).findFirst().orElse(null);
        if (budget == null) return false;
        int previousIndex = data.chairBudgets.indexOf(budget);
        List<String> linkedReceipts = budget.getReimbursements().stream()
                .map(ReimbursementRecord::getReceiptFileName).filter(Objects::nonNull).toList();
        data.chairBudgets.remove(previousIndex);
        // Events this chair ran stay on the Events page, just no longer assigned to a chair.
        List<PresaleCampaign> unlinkedEvents = data.presaleCampaigns.stream()
                .filter(campaign -> budgetId.equals(campaign.getChairBudgetId())).toList();
        unlinkedEvents.forEach(campaign -> campaign.setChairBudgetId(null));
        Set<String> stillLinked = data.chairBudgets.stream()
                .flatMap(item -> item.getReimbursements().stream())
                .map(ReimbursementRecord::getReceiptFileName).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> receiptsToDelete = linkedReceipts.stream().filter(name -> !stillLinked.contains(name)).toList();
        Map<String, String> removedReceiptData = new LinkedHashMap<>();
        for (String name : receiptsToDelete) {
            if (data.receiptFiles.containsKey(name)) removedReceiptData.put(name, data.receiptFiles.get(name));
            data.receiptFiles.remove(name);
        }
        saveOrRollback(() -> {
            data.chairBudgets.add(previousIndex, budget);
            unlinkedEvents.forEach(campaign -> campaign.setChairBudgetId(budgetId));
            data.receiptFiles.putAll(removedReceiptData);
        });
        for (String name : receiptsToDelete) {
            try {
                deleteReceipt(name);
            } catch (Exception e) {
                log.warn("Unable to remove deleted chair receipt {}; the saved reference was removed", name, e);
            }
        }
        return true;
    }

    /** Records money a chair raised. Returns false if the budget is unknown or the entry is invalid. */
    public synchronized boolean addRaised(String budgetId, RaisedRecord raised) {
        ensureNotNull();
        ChairBudget budget = data.chairBudgets.stream()
                .filter(item -> item.getId().equals(budgetId)).findFirst().orElse(null);
        if (budget == null || raised == null || raised.getDescription() == null || raised.getDescription().isBlank()
                || raised.getDescription().length() > 200 || !Double.isFinite(raised.getAmount())
                || raised.getAmount() <= 0) return false;
        try {
            LocalDate.parse(raised.getDate());
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
        budget.getRaised().add(raised);
        try {
            save();
        } catch (IllegalStateException e) {
            budget.getRaised().remove(raised);
            throw e;
        }
        return true;
    }

    /** Members archived by a semester rollover (no longer on the roster). */
    public synchronized List<Member> getArchivedMembers() {
        ensureNotNull();
        return new ArrayList<>(data.archivedMembers);
    }

    /** Active and archived members, for reports whose history must not shrink after a rollover. */
    public synchronized List<Member> getMembersIncludingArchived() {
        ensureNotNull();
        List<Member> all = new ArrayList<>(data.members);
        all.addAll(data.archivedMembers);
        return all;
    }

    public synchronized List<ChairBudgetPeriod> getChairBudgetHistory() {
        ensureNotNull();
        return new ArrayList<>(data.chairBudgetHistory);
    }

    public static final String CARRYOVER_DESCRIPTION = "Prior Semester Carryover Balance";

    /** What a semester rollover should do. */
    public record RolloverRequest(String termName, List<String> archiveMemberIds, boolean consolidateCharges,
                                  boolean clearExpiredVouches, boolean resetChairBudgets) {}

    /** What a semester rollover did, and where the safety copy of the data was written. */
    public record RolloverResult(int archived, int skippedWithBalance, int consolidatedMembers, double consolidatedTotal,
                                 int clearedVouches, int budgetsReset, Path backupFile) {}

    /**
     * Starts a new semester. A copy of the saved data is written next to the data file first; then, all together or
     * not at all: archives the chosen members (only those with a $0 balance), folds each remaining member's unpaid
     * itemized charges into one "Prior Semester Carryover Balance" charge (same total owed), clears social-clearance
     * vouches that have expired, and optionally closes out chair budget terms. Payment history, cash flow and metric
     * history are all kept.
     */
    public synchronized RolloverResult startNewSemester(RolloverRequest request, LocalDate today) throws java.io.IOException {
        ensureNotNull();
        String term = request.termName() == null ? "" : request.termName().trim();
        if (term.length() > 60) throw new IllegalArgumentException("Keep the term name under 60 characters.");
        Path backup = writePreRolloverBackup(today);
        DataContainer before = mapper.readValue(mapper.writeValueAsBytes(data), DataContainer.class);
        try {
            Set<String> chosen = new HashSet<>(request.archiveMemberIds() == null ? List.of() : request.archiveMemberIds());
            int archived = 0;
            int skipped = 0;
            for (Member member : new ArrayList<>(data.members)) {
                if (!chosen.contains(member.getId())) continue;
                if (Money.positive(member.getTotalOwed())) { skipped++; continue; }
                data.members.remove(member);
                data.archivedMembers.add(member);
                archived++;
            }

            int consolidatedMembers = 0;
            double consolidatedTotal = 0;
            if (request.consolidateCharges()) {
                for (Member member : data.members) {
                    List<ChargeRecord> unpaid = member.getCharges().stream()
                            .filter(charge -> charge.getRemainingAmount() >= Money.HALF_CENT).toList();
                    if (unpaid.isEmpty()) continue;
                    if (unpaid.size() == 1 && unpaid.getFirst().getDescription() != null
                            && unpaid.getFirst().getDescription().startsWith(CARRYOVER_DESCRIPTION)) continue;
                    double remaining = Money.cents(unpaid.stream().mapToDouble(ChargeRecord::getRemainingAmount).sum());
                    double pastDue = Money.cents(unpaid.stream()
                            .mapToDouble(charge -> Math.min(charge.getPastDueAmount(), charge.getRemainingAmount())).sum());
                    ChargeRecord carryover = new ChargeRecord(UUID.randomUUID().toString(), today.toString(),
                            CARRYOVER_DESCRIPTION, remaining, pastDue);
                    member.getCharges().removeAll(unpaid);
                    member.getCharges().add(carryover);
                    consolidatedMembers++;
                    consolidatedTotal += remaining;
                }
            }

            int clearedVouches = 0;
            if (request.clearExpiredVouches()) {
                for (Member member : data.members) {
                    String until = member.getClearedUntilDate();
                    if (until == null || until.isBlank()) continue;
                    try {
                        if (!LocalDate.parse(until).isBefore(today)) continue;
                    } catch (DateTimeParseException e) {
                        // An unreadable vouch date can't be honored either; clear it.
                    }
                    member.setClearedUntilDate(null);
                    clearedVouches++;
                }
            }

            int budgetsReset = 0;
            if (request.resetChairBudgets()) {
                refreshChairEventLinks();
                for (ChairBudget budget : data.chairBudgets) {
                    if (budget.getReimbursements().isEmpty() && budget.getRaised().isEmpty() && budget.getLinkedEvents().isEmpty()) continue;
                    // The closed-out term's totals include the chair's events; those events then stop counting.
                    data.chairBudgetHistory.add(new ChairBudgetPeriod(budget, data.settings.getCurrentTermName(), today.toString()));
                    budget.setReimbursements(new ArrayList<>());
                    budget.setRaised(new ArrayList<>());
                    data.presaleCampaigns.stream().filter(campaign -> budget.getId().equals(campaign.getChairBudgetId()))
                            .forEach(campaign -> campaign.setChairBudgetId(null));
                    budget.linkEvents(List.of());
                    budgetsReset++;
                }
            }

            data.settings.setCurrentTermName(term);
            data.settings.setLastRolloverDate(today.toString());
            refreshMemberIndex();
            save();
            return new RolloverResult(archived, skipped, consolidatedMembers, Money.cents(consolidatedTotal),
                    clearedVouches, budgetsReset, backup);
        } catch (RuntimeException e) {
            data = before;
            refreshMemberIndex();
            throw e;
        }
    }

    /** Copies the current data to "<data file>.pre-rollover-<date-time>.json" (owner-only) before a rollover changes it. */
    private Path writePreRolloverBackup(LocalDate today) throws java.io.IOException {
        Path dataFile = Path.of(DATA_FILE).toAbsolutePath();
        String stamp = today + "-" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
        Path backup = dataFile.resolveSibling(dataFile.getFileName() + ".pre-rollover-" + stamp + ".json");
        Files.createDirectories(backup.getParent());
        Files.write(backup, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
        try {
            setOwnerOnlyFilePermissions(backup);
        } catch (Exception e) {
            log.debug("Owner-only permissions are not supported for {}", backup, e);
        }
        return backup;
    }
    public synchronized List<RecurringBill> getRecurringBills() {
        ensureNotNull();
        return new ArrayList<>(data.recurringBills);
    }

    /**
     * Creates a recurring bill, or updates the one with the same id (its payment history is kept). Throws
     * IllegalArgumentException with a message for the person when a detail is invalid.
     */
    public synchronized RecurringBill saveRecurringBill(RecurringBill details) {
        ensureNotNull();
        String name = details == null || details.getName() == null ? "" : details.getName().trim();
        if (name.isBlank() || name.length() > 100) throw new IllegalArgumentException("Enter a bill name (up to 100 characters).");
        if (!Double.isFinite(details.getAmount()) || details.getAmount() <= 0) throw new IllegalArgumentException("Enter a bill amount greater than zero.");
        if (!RecurringBill.FREQUENCIES.containsKey(details.getFrequency())) throw new IllegalArgumentException("Choose how often the bill repeats.");
        LocalDate start;
        try {
            start = LocalDate.parse(details.getStartDate());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Choose the date the bill is first due.");
        }
        if (!details.getEndDate().isBlank()) {
            try {
                if (LocalDate.parse(details.getEndDate()).isBefore(start)) throw new IllegalArgumentException("The end date can't be before the first due date.");
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Choose a valid end date, or leave it blank.");
            }
        }
        if (!TransactionCategories.OUTFLOW.contains(details.getCategory())) throw new IllegalArgumentException("Choose a category for this bill.");
        if (details.getPayee().length() > 120) throw new IllegalArgumentException("Keep the payee under 120 characters.");
        if (!RecurringBill.AUTOMATIC.equals(details.getPaymentMode()) && !RecurringBill.MANUAL.equals(details.getPaymentMode())) {
            throw new IllegalArgumentException("Choose whether the bill is paid automatically or manually.");
        }
        List<Integer> reminders = details.getReminderDays().stream().filter(java.util.Objects::nonNull)
                .distinct().sorted(Comparator.reverseOrder()).toList();
        if (reminders.size() > RecurringBill.MAX_REMINDERS
                || reminders.stream().anyMatch(days -> days < 0 || days > RecurringBill.MAX_LEAD_DAYS)) {
            throw new IllegalArgumentException("Choose up to " + RecurringBill.MAX_REMINDERS + " reminders, each between 0 and "
                    + RecurringBill.MAX_LEAD_DAYS + " days before the due date.");
        }
        RecurringBill existing = details.getId() == null ? null
                : data.recurringBills.stream().filter(bill -> bill.getId().equals(details.getId())).findFirst().orElse(null);
        if (existing == null) {
            RecurringBill created = new RecurringBill(details.getId() == null ? UUID.randomUUID().toString() : details.getId(),
                    name, details.getAmount(), details.getFrequency(), start.toString(), details.getEndDate(), details.getCategory(),
                    details.getPayee().trim(), details.getPaymentMode(), reminders, LocalDate.now().toString());
            data.recurringBills.add(created);
            saveOrRollback(() -> data.recurringBills.remove(created));
            return created;
        }
        RecurringBill before = new RecurringBill(existing.getId(), existing.getName(), existing.getAmount(), existing.getFrequency(),
                existing.getStartDate(), existing.getEndDate(), existing.getCategory(), existing.getPayee(), existing.getPaymentMode(),
                existing.getReminderDays(), existing.getCreatedDate());
        existing.setName(name);
        existing.setAmount(details.getAmount());
        existing.setFrequency(details.getFrequency());
        existing.setStartDate(start.toString());
        existing.setEndDate(details.getEndDate());
        existing.setCategory(details.getCategory());
        existing.setPayee(details.getPayee().trim());
        existing.setPaymentMode(details.getPaymentMode());
        existing.setReminderDays(new ArrayList<>(reminders));
        saveOrRollback(() -> {
            existing.setName(before.getName());
            existing.setAmount(before.getAmount());
            existing.setFrequency(before.getFrequency());
            existing.setStartDate(before.getStartDate());
            existing.setEndDate(before.getEndDate());
            existing.setCategory(before.getCategory());
            existing.setPayee(before.getPayee());
            existing.setPaymentMode(before.getPaymentMode());
            existing.setReminderDays(before.getReminderDays());
        });
        return existing;
    }

    /** Deletes a bill and its schedule. Payments already recorded stay in Inflow/Outflow. Returns false if it isn't found. */
    public synchronized boolean deleteRecurringBill(String id) {
        ensureNotNull();
        int index = indexOf(data.recurringBills, bill -> bill.getId().equals(id));
        if (index < 0) return false;
        RecurringBill removed = data.recurringBills.remove(index);
        saveOrRollback(() -> data.recurringBills.add(index, removed));
        return true;
    }

    /**
     * Records a payment of one due date of a bill: an outflow in Inflow/Outflow plus a note on the bill that this due
     * date is done. Returns false if the bill or due date is unknown, the date is already settled, or the amount is invalid.
     */
    public synchronized boolean payRecurringBill(String billId, LocalDate due, LocalDate paidOn, double amount) {
        ensureNotNull();
        RecurringBill bill = data.recurringBills.stream().filter(item -> item.getId().equals(billId)).findFirst().orElse(null);
        if (bill == null || !Double.isFinite(amount) || amount <= 0 || !isOccurrence(bill, due) || bill.settlementFor(due) != null) return false;
        return settle(bill, due, paidOn, amount, false);
    }

    /** Marks one due date as skipped (nothing was paid), so it stops showing as due. Returns false when it can't be skipped. */
    public synchronized boolean skipRecurringBill(String billId, LocalDate due) {
        ensureNotNull();
        RecurringBill bill = data.recurringBills.stream().filter(item -> item.getId().equals(billId)).findFirst().orElse(null);
        if (bill == null || !isOccurrence(bill, due) || bill.settlementFor(due) != null) return false;
        RecurringBill.Settlement skipped = new RecurringBill.Settlement(due.toString(), "SKIPPED", null, 0, false, null);
        bill.getSettlements().add(skipped);
        saveOrRollback(() -> bill.getSettlements().remove(skipped));
        return true;
    }

    /**
     * Records every automatic bill that has come due on or before {@code today} and was not recorded yet, including
     * ones that fell due while the app was closed. Returns how many payments were recorded.
     */
    public synchronized int processRecurringBills(LocalDate today) {
        ensureNotNull();
        List<Runnable> undo = new ArrayList<>();
        int recorded = 0;
        for (RecurringBill bill : data.recurringBills) {
            if (!bill.isAutomatic()) continue;
            for (LocalDate due : bill.pendingBetween(LocalDate.parse(bill.getCreatedDate() == null ? bill.getStartDate() : bill.getCreatedDate()), today)) {
                FlowEntry entry = billEntry(bill, due, due, bill.getAmount(), true);
                data.flowEntries.add(entry);
                RecurringBill.Settlement settlement = new RecurringBill.Settlement(due.toString(), "PAID", due.toString(), bill.getAmount(), true, entry.getId());
                bill.getSettlements().add(settlement);
                undo.add(() -> { data.flowEntries.remove(entry); bill.getSettlements().remove(settlement); });
                recorded++;
            }
        }
        if (recorded == 0) return 0;
        try {
            save();
        } catch (IllegalStateException e) {
            undo.forEach(Runnable::run);
            throw e;
        }
        return recorded;
    }

    private static boolean isOccurrence(RecurringBill bill, LocalDate due) {
        return bill.isTracked(due) && !bill.dueDatesBetween(due, due).isEmpty();
    }

    private boolean settle(RecurringBill bill, LocalDate due, LocalDate paidOn, double amount, boolean automatic) {
        FlowEntry entry = billEntry(bill, due, paidOn, amount, automatic);
        if (data.flowEntries.stream().anyMatch(existing -> entry.getId().equals(existing.getId()))) return false;
        data.flowEntries.add(entry);
        RecurringBill.Settlement settlement = new RecurringBill.Settlement(due.toString(), "PAID", paidOn.toString(), amount, automatic, entry.getId());
        bill.getSettlements().add(settlement);
        saveOrRollback(() -> { data.flowEntries.remove(entry); bill.getSettlements().remove(settlement); });
        return true;
    }

    /** The outflow a bill payment leaves in Inflow/Outflow; its id is fixed per bill and due date so it can never be recorded twice. */
    private static FlowEntry billEntry(RecurringBill bill, LocalDate due, LocalDate paidOn, double amount, boolean automatic) {
        FlowEntry entry = new FlowEntry("bill-" + bill.getId() + "-" + due, paidOn.toString(), bill.getName(), bill.getPayee(),
                -Money.cents(amount), automatic ? "Recurring bill (automatic)" : "Recurring bill");
        entry.setCategory(bill.getCategory());
        entry.setReconciliationStatus("MANUAL");
        return entry;
    }
    public synchronized List<PresaleCampaign> getPresaleCampaigns() {
        ensureNotNull();
        return new ArrayList<>(data.presaleCampaigns);
    }

    public synchronized Optional<PresaleCampaign> findPresaleCampaign(String id) {
        ensureNotNull();
        return data.presaleCampaigns.stream().filter(campaign -> campaign.getId().equals(id)).findFirst();
    }

    /**
     * Creates a pre-sale campaign, or updates the details (not the sales or expenses) of the one with the same id.
     * Throws IllegalArgumentException with a message for the person when a detail is invalid.
     */
    public synchronized PresaleCampaign savePresaleCampaign(PresaleCampaign details) {
        ensureNotNull();
        String name = details == null || details.getName() == null ? "" : details.getName().trim();
        if (name.isBlank() || name.length() > 100) throw new IllegalArgumentException("Enter an event name (up to 100 characters).");
        if (!details.getEventDate().isBlank()) {
            try {
                LocalDate.parse(details.getEventDate());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Choose a valid event date, or leave it blank.");
            }
        }
        if (!Double.isFinite(details.getTicketPrice()) || details.getTicketPrice() < 0
                || !Double.isFinite(details.getRevenueGoal()) || details.getRevenueGoal() < 0) {
            throw new IllegalArgumentException("Enter a ticket price and revenue goal of zero or more.");
        }
        if (details.getPrize().length() > 200) throw new IllegalArgumentException("Keep the prize under 200 characters.");
        String chairId = details.getChairBudgetId();
        if (chairId != null && data.chairBudgets.stream().noneMatch(budget -> budget.getId().equals(chairId))) {
            throw new IllegalArgumentException("Choose a chair position from the list, or No chair.");
        }
        PresaleCampaign existing = details.getId() == null ? null
                : data.presaleCampaigns.stream().filter(campaign -> campaign.getId().equals(details.getId())).findFirst().orElse(null);
        if (existing == null) {
            PresaleCampaign created = new PresaleCampaign(details.getId() == null ? UUID.randomUUID().toString() : details.getId(),
                    name, details.getEventDate(), details.getTicketPrice(), details.getRevenueGoal(), details.getPrize().trim());
            created.setChairBudgetId(chairId);
            data.presaleCampaigns.add(created);
            saveOrRollback(() -> data.presaleCampaigns.remove(created));
            return created;
        }
        PresaleCampaign before = new PresaleCampaign(existing.getId(), existing.getName(), existing.getEventDate(),
                existing.getTicketPrice(), existing.getRevenueGoal(), existing.getPrize());
        String previousChair = existing.getChairBudgetId();
        existing.setName(name);
        existing.setEventDate(details.getEventDate());
        existing.setTicketPrice(details.getTicketPrice());
        existing.setRevenueGoal(details.getRevenueGoal());
        existing.setPrize(details.getPrize().trim());
        existing.setChairBudgetId(chairId);
        saveOrRollback(() -> {
            existing.setName(before.getName());
            existing.setEventDate(before.getEventDate());
            existing.setTicketPrice(before.getTicketPrice());
            existing.setRevenueGoal(before.getRevenueGoal());
            existing.setPrize(before.getPrize());
            existing.setChairBudgetId(previousChair);
        });
        return existing;
    }

    /**
     * Logs a sale of a ticket, wristband, merchandise or custom item. Tickets and wristbands must say whether the guest
     * is male or female; a custom item must be named. Returns false if the campaign is unknown or the sale is invalid.
     */
    public synchronized boolean addPresaleSale(String campaignId, PresaleSale sale) {
        return sale != null && addPresaleSales(campaignId, List.of(sale));
    }

    /**
     * Logs several items sold together (e.g. two tickets and a hoodie) as one save: if any line is invalid, none of
     * them are added. Returns false if the campaign is unknown, the list is empty or any sale is invalid.
     */
    public synchronized boolean addPresaleSales(String campaignId, List<PresaleSale> sales) {
        ensureNotNull();
        PresaleCampaign campaign = findPresaleCampaign(campaignId).orElse(null);
        if (campaign == null || sales == null || sales.isEmpty() || sales.size() > 50
                || !sales.stream().allMatch(this::validPresaleSale)) return false;
        // Male/female only means something for tickets and wristbands.
        sales.stream().filter(sale -> !sale.isAdmission()).forEach(sale -> sale.setGuestType(""));
        int before = campaign.getSales().size();
        campaign.getSales().addAll(sales);
        saveOrRollback(() -> campaign.getSales().subList(before, campaign.getSales().size()).clear());
        return true;
    }

    private boolean validPresaleSale(PresaleSale sale) {
        return sale != null && validDate(sale.getDate()) && sale.getQuantity() >= 1 && sale.getQuantity() <= 10_000
                && Double.isFinite(sale.getUnitPrice()) && sale.getUnitPrice() >= 0 && sale.getBuyerName().length() <= 120
                && sale.getDescription().length() <= 120
                && (PresaleSale.TICKET.equals(sale.getItem()) || PresaleSale.WRISTBAND.equals(sale.getItem())
                        || PresaleSale.MERCH.equals(sale.getItem()) || PresaleSale.OTHER.equals(sale.getItem()))
                && (!sale.isAdmission() || PresaleSale.MALE.equals(sale.getGuestType()) || PresaleSale.FEMALE.equals(sale.getGuestType()))
                && !(PresaleSale.OTHER.equals(sale.getItem()) && sale.getDescription().isBlank())
                && (sale.getSellerMemberId() == null || membersById.containsKey(sale.getSellerMemberId()));
    }

    /** Marks an event complete (it is tucked away under "Completed events" but stays viewable) or reopens it. */
    public synchronized boolean setPresaleCompleted(String campaignId, boolean completed, LocalDate today) {
        ensureNotNull();
        PresaleCampaign campaign = findPresaleCampaign(campaignId).orElse(null);
        if (campaign == null) return false;
        boolean wasCompleted = campaign.isCompleted();
        String wasDate = campaign.getCompletedDate();
        campaign.setCompleted(completed);
        campaign.setCompletedDate(completed ? today.toString() : "");
        saveOrRollback(() -> {
            campaign.setCompleted(wasCompleted);
            campaign.setCompletedDate(wasDate);
        });
        return true;
    }

    /** Logs an event expense. Returns false if the campaign is unknown or the expense is invalid. */
    public synchronized boolean addPresaleExpense(String campaignId, PresaleExpense expense) {
        ensureNotNull();
        PresaleCampaign campaign = findPresaleCampaign(campaignId).orElse(null);
        if (campaign == null || expense == null || !validDate(expense.getDate()) || expense.getDescription() == null
                || expense.getDescription().isBlank() || expense.getDescription().length() > 200
                || !Double.isFinite(expense.getAmount()) || expense.getAmount() <= 0) return false;
        campaign.getExpenses().add(expense);
        saveOrRollback(() -> campaign.getExpenses().remove(expense));
        return true;
    }

    /** Removes a sale logged by mistake. Returns false if it isn't found. */
    public synchronized boolean removePresaleSale(String campaignId, String saleId) {
        ensureNotNull();
        PresaleCampaign campaign = findPresaleCampaign(campaignId).orElse(null);
        if (campaign == null) return false;
        int index = indexOf(campaign.getSales(), sale -> sale.getId().equals(saleId));
        if (index < 0) return false;
        PresaleSale removed = campaign.getSales().remove(index);
        saveOrRollback(() -> campaign.getSales().add(index, removed));
        return true;
    }

    /** Removes an expense logged by mistake. Returns false if it isn't found. */
    public synchronized boolean removePresaleExpense(String campaignId, String expenseId) {
        ensureNotNull();
        PresaleCampaign campaign = findPresaleCampaign(campaignId).orElse(null);
        if (campaign == null) return false;
        int index = indexOf(campaign.getExpenses(), expense -> expense.getId().equals(expenseId));
        if (index < 0) return false;
        PresaleExpense removed = campaign.getExpenses().remove(index);
        saveOrRollback(() -> campaign.getExpenses().add(index, removed));
        return true;
    }

    private static <T> int indexOf(List<T> items, java.util.function.Predicate<T> match) {
        for (int i = 0; i < items.size(); i++) if (match.test(items.get(i))) return i;
        return -1;
    }

    private static boolean validDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }

    /** Saves; if the save fails, undoes the in-memory change so memory and disk stay in step, then rethrows. */
    private void saveOrRollback(Runnable rollback) {
        try {
            save();
        } catch (IllegalStateException e) {
            rollback.run();
            throw e;
        }
    }
    public synchronized String saveReceipt(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024) {
            throw new IllegalArgumentException("Receipt photos must be non-empty and no larger than 5 MB.");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length > 5L * 1024 * 1024) {
            throw new IllegalArgumentException("Receipt photos must be non-empty and no larger than 5 MB.");
        }
        String extension;
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (contentType.equals("image/png") && bytes.length >= 8 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            extension = ".png";
        } else if (contentType.equals("image/jpeg") && bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            extension = ".jpg";
        } else {
            throw new IllegalArgumentException("Receipts must be valid PNG or JPEG images.");
        }
        java.awt.image.BufferedImage decoded;
        try (var imageInput = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new IllegalArgumentException("Receipt image data could not be decoded.");
            var reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 25_000_000L) {
                    throw new IllegalArgumentException("Receipt images must be 25 megapixels or smaller.");
                }
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        // Resize anything wider than 1600px and re-encode as JPEG so receipts don't bloat the saved data and handoff ZIP.
        ReceiptCompressor.Result compressed = ReceiptCompressor.compress(bytes, decoded, extension);
        bytes = compressed.bytes();
        extension = compressed.extension();
        Path directory = receiptDirectory();
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            log.debug("Owner-only directory permissions are not supported for {}", directory);
        }
        Path target = directory.resolve(UUID.randomUUID() + extension);
        Files.write(target, bytes);
        setOwnerOnlyFilePermissions(target);
        return target.getFileName().toString();
    }

    public synchronized void deleteReceipt(String fileName) throws Exception {
        if (fileName == null || !fileName.matches("[a-f0-9-]{36}\\.(png|jpg)")) return;
        Files.deleteIfExists(receiptDirectory().resolve(fileName).normalize());
    }

    /**
     * Writes receipt images embedded in the saved data back to the receipts folder. With {@code strict} (backup
     * imports) an unreadable receipt rejects the whole import; otherwise (startup) it is logged and skipped.
     */
    private void restoreReceiptFilesFromData(boolean strict) {
        ensureNotNull();
        for (Map.Entry<String, String> receipt : data.receiptFiles.entrySet()) {
            String fileName = receipt.getKey();
            if (fileName == null || !fileName.matches("[a-f0-9-]{36}\\.(png|jpg)")) {
                log.warn("Skipping invalid saved receipt reference");
                continue;
            }
            try {
                byte[] content = java.util.Base64.getDecoder().decode(receipt.getValue());
                if (content.length > 5L * 1024 * 1024 || !validReceiptImage(fileName, content)) {
                    throw new IllegalArgumentException("Invalid saved receipt image.");
                }
                Path directory = receiptDirectory();
                Files.createDirectories(directory);
                try {
                    Files.setPosixFilePermissions(directory,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException ignored) {
                    log.debug("Owner-only directory permissions are not supported for {}", directory);
                }
                Path target = directory.resolve(fileName);
                if (!Files.exists(target)) {
                    Files.write(target, content);
                    setOwnerOnlyFilePermissions(target);
                }
            } catch (Exception e) {
                log.error("Unable to restore saved receipt {}", fileName, e);
                if (strict) throw new IllegalStateException("A saved reimbursement receipt could not be restored.", e);
            }
        }
    }

    private boolean validReceiptImage(String fileName, byte[] bytes) throws Exception {
        boolean png = fileName.endsWith(".png") && bytes.length >= 8 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        boolean jpeg = fileName.endsWith(".jpg") && bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        if (!png && !jpeg) return false;
        try (var input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return false;
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 25_000_000L) return false;
                return reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        }
    }

    public synchronized byte[] readReceipt(String fileName) throws Exception {
        if (fileName == null || !fileName.matches("[a-f0-9-]{36}\\.(png|jpg)")) {
            throw new IllegalArgumentException("Invalid receipt reference.");
        }
        Path path = receiptDirectory().resolve(fileName).normalize();
        if (!path.getParent().equals(receiptDirectory().toAbsolutePath().normalize())
                || !Files.isRegularFile(path)) throw new java.nio.file.NoSuchFileException(fileName);
        return Files.readAllBytes(path);
    }

    private Path receiptDirectory() {
        return Path.of(DATA_FILE).toAbsolutePath().resolveSibling(Path.of(DATA_FILE).getFileName() + ".receipts");
    }

    /**
     * Adds several individual charges (no receipt, no dues credit) to a chair budget in one save.
     * Returns how many were added, or 0 if the budget is unknown or any charge is invalid (nothing is saved).
     */
    public synchronized int addChairCharges(String budgetId, List<ReimbursementRecord> charges) {
        ensureNotNull();
        ChairBudget budget = data.chairBudgets.stream()
                .filter(item -> item.getId().equals(budgetId)).findFirst().orElse(null);
        if (budget == null || charges == null || charges.isEmpty()) return 0;
        for (ReimbursementRecord charge : charges) {
            if (charge == null || charge.getDescription() == null || charge.getDescription().isBlank()
                    || charge.getDescription().length() > 200 || !Double.isFinite(charge.getAmount())
                    || charge.getAmount() <= 0 || charge.getCreditedMemberId() != null || charge.getReceiptFileName() != null) return 0;
            try {
                LocalDate.parse(charge.getDate());
            } catch (DateTimeParseException | NullPointerException e) {
                return 0;
            }
        }
        budget.getReimbursements().addAll(charges);
        try {
            save();
        } catch (IllegalStateException e) {
            budget.getReimbursements().removeAll(charges);
            throw e;
        }
        return charges.size();
    }

    /** Adds one charge (optionally with a receipt photo) to a chair's budget. Returns false if it is invalid. */
    public synchronized boolean addReimbursement(String budgetId, ReimbursementRecord reimbursement) {
        ensureNotNull();
        ChairBudget budget = data.chairBudgets.stream()
                .filter(item -> budgetId.equals(item.getId())).findFirst().orElse(null);
        if (budget == null || reimbursement == null || reimbursement.getDescription() == null
                || reimbursement.getDescription().isBlank() || reimbursement.getDescription().length() > 200
                || !Double.isFinite(reimbursement.getAmount()) || reimbursement.getAmount() <= 0
                // Charges no longer credit a member's dues.
                || (reimbursement.getCreditedMemberId() != null && !reimbursement.getCreditedMemberId().isBlank())) return false;
        if (reimbursement.getReceiptFileName() != null) {
            try {
                data.receiptFiles.put(reimbursement.getReceiptFileName(),
                        java.util.Base64.getEncoder().encodeToString(readReceipt(reimbursement.getReceiptFileName())));
            } catch (Exception e) {
                throw new IllegalStateException("The attached receipt could not be added to the saved data.", e);
            }
        }
        budget.getReimbursements().add(reimbursement);
        try {
            save();
            return true;
        } catch (IllegalStateException e) {
            budget.getReimbursements().remove(reimbursement);
            if (reimbursement.getReceiptFileName() != null) data.receiptFiles.remove(reimbursement.getReceiptFileName());
            throw e;
        }
    }
    private void refreshPastDueCharges() {
        LocalDate today = LocalDate.now();
        boolean changed = false;
        for (Member member : data.members) {
            if (member.getCharges() == null) continue;
            boolean memberChanged = false;
            for (ChargeRecord charge : member.getCharges()) {
                if (charge.getDueDate() == null || charge.getDueDate().isBlank()) continue;
                try {
                    if (LocalDate.parse(charge.getDueDate()).isBefore(today)) {
                        double remaining = charge.getRemainingAmount();
                        double additionalPastDue = Math.max(0, remaining - charge.getPastDueAmount());
                        if (additionalPastDue > 0.005) {
                            charge.setPastDueAmount(remaining);
                            member.setPastDueBalance(member.getPastDueBalance() + additionalPastDue);
                            memberChanged = true;
                        }
                    }
                } catch (DateTimeParseException e) {
                    log.warn("Skipping invalid due date on charge {} for member {}", charge.getId(), member.getId());
                }
            }
            if (memberChanged) {
                member.setStatus(determineStatus(member.getTotalOwed(), member.getPastDueBalance()));
                changed = true;
            }
        }
        if (changed) save();
    }

    public synchronized AppSettings getSettings() {
        ensureNotNull();
        return data.settings;
    }

    public synchronized List<SmsLog> getSmsLogs() {
        ensureNotNull();
        return data.smsLogs;
    }

    public synchronized Optional<Member> findMemberById(String id) {
        return Optional.ofNullable(membersById.get(id));
    }

    public synchronized void addMember(Member member) {
        ensureNotNull();
        data.members.add(member);
        refreshMemberIndex();
        save();
    }

    public synchronized boolean removeMember(String id) {
        ensureNotNull();
        boolean removed = data.members.removeIf(member -> id.equals(member.getId()));
        if (removed) {
            // A chair held by the removed member goes back to having no member assigned.
            data.chairBudgets.stream().filter(budget -> id.equals(budget.getChairMemberId())).forEach(budget -> budget.setChairMemberId(null));
            refreshMemberIndex();
            save();
        }
        return removed;
    }

    public synchronized List<Resident> getResidents() {
        ensureNotNull();
        return new ArrayList<>(data.residents);
    }

    public synchronized Optional<Resident> findResidentById(String id) {
        ensureNotNull();
        return data.residents.stream().filter(resident -> resident.getId().equals(id)).findFirst();
    }

    public synchronized void addResident(Resident resident) {
        ensureNotNull();
        data.residents.add(resident);
        // A new resident with no link chosen is matched to a roster member by name, like existing ones.
        if (!resident.isLinkDecided()) autoLinkResidents();
        save();
    }

    public synchronized boolean removeResident(String id) {
        ensureNotNull();
        boolean removed = data.residents.removeIf(resident -> id.equals(resident.getId()));
        if (removed) save();
        return removed;
    }

    /** Adds one itemized charge to each listed resident's rent bill. Returns how many residents were charged (0 if invalid). */
    public synchronized int addResidentCharges(List<String> residentIds, String description, double amount, String dueDate) {
        ensureNotNull();
        if (residentIds == null || residentIds.isEmpty() || description == null || description.isBlank()
                || !Double.isFinite(amount) || amount <= 0) return 0;
        String due = null;
        if (dueDate != null && !dueDate.isBlank()) {
            try {
                due = LocalDate.parse(dueDate).toString();
            } catch (DateTimeParseException e) {
                return 0;
            }
        }
        List<String> ids = residentIds.stream().distinct().toList();
        List<Resident> targets = ids.stream()
                .map(id -> data.residents.stream().filter(resident -> id.equals(resident.getId())).findFirst().orElse(null))
                .filter(Objects::nonNull).toList();
        if (targets.size() != ids.size()) return 0;
        Map<Resident, Double> previousBalances = new LinkedHashMap<>();
        for (Resident resident : targets) {
            previousBalances.put(resident, resident.getBalanceDue());
            ChargeRecord charge = new ChargeRecord(UUID.randomUUID().toString(), LocalDate.now().toString(),
                    description.trim(), amount, 0);
            charge.setDueDate(due);
            resident.getCharges().add(charge);
            resident.setBalanceDue(resident.getBalanceDue() + amount);
        }
        try {
            save();
        } catch (IllegalStateException e) {
            previousBalances.forEach((resident, balance) -> {
                resident.getCharges().removeLast();
                resident.setBalanceDue(balance);
            });
            throw e;
        }
        return targets.size();
    }

    /** Removes a charge and takes its amount back off the balance (never below zero). */
    public synchronized boolean removeResidentCharge(String residentId, String chargeId) {
        ensureNotNull();
        Resident resident = findResidentById(residentId).orElse(null);
        if (resident == null || chargeId == null) return false;
        List<ChargeRecord> previousCharges = new ArrayList<>(resident.getCharges());
        ChargeRecord charge = previousCharges.stream().filter(item -> chargeId.equals(item.getId())).findFirst().orElse(null);
        if (charge == null) return false;
        double previousBalance = resident.getBalanceDue();
        resident.getCharges().remove(charge);
        resident.setBalanceDue(Math.max(0, previousBalance - charge.getAmount()));
        try {
            save();
        } catch (IllegalStateException e) {
            resident.setCharges(previousCharges);
            resident.setBalanceDue(previousBalance);
            throw e;
        }
        return true;
    }

    public synchronized boolean reserveSmsSlot() {
        ensureNotNull();
        String today = java.time.LocalDate.now().toString();
        String previousDate = data.smsUsageDate;
        int previousCount = data.smsUsageCount;
        if (!today.equals(data.smsUsageDate)) {
            data.smsUsageDate = today;
            data.smsUsageCount = 0;
        }
        if (data.smsUsageCount >= Math.max(0, smsDailyCap)) return false;
        data.smsUsageCount++;
        try {
            save();
        } catch (IllegalStateException e) {
            data.smsUsageDate = previousDate;
            data.smsUsageCount = previousCount;
            throw e;
        }
        return true;
    }

    public synchronized int getSmsRemainingToday() {
        ensureNotNull();
        int used = java.time.LocalDate.now().toString().equals(data.smsUsageDate) ? data.smsUsageCount : 0;
        return Math.max(0, smsDailyCap - used);
    }

    public synchronized void addSmsLog(SmsLog log) {
        ensureNotNull();
        data.smsLogs.add(0, log);
        if (data.smsLogs.size() > 50) {
            data.smsLogs.remove(data.smsLogs.size() - 1);
        }
        save();
    }

    public synchronized int importCsv(MultipartFile file) throws Exception {
        ensureNotNull();
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        List<GreekBillCsvParser.Row> rows = GreekBillCsvParser.parse(text);
        Map<String, Member> membersByName = data.members.stream()
                .collect(Collectors.toMap(member -> member.getName().toLowerCase(Locale.ROOT),
                        member -> member, (first, ignored) -> first));
        for (GreekBillCsvParser.Row row : rows) {
            String name = row.name();
            String phone = row.phone() == null ? "" : row.phone();
            double totalOwed = row.totalOwed();
            Member existing = membersByName.get(name.toLowerCase(Locale.ROOT));

            if (existing != null) {
                double previousTotalOwed = existing.getTotalOwed();
                double pastDue = row.pastDue() != null ? row.pastDue() : existing.getPastDueBalance();
                if (!phone.isBlank()) existing.setPhoneNumber(phone);
                existing.setTotalOwed(totalOwed);
                existing.setPastDueBalance(pastDue);
                existing.setStatus(determineStatus(totalOwed, pastDue));

                if (row.paidThisMonth() != null) {
                    existing.setPaidThisMonth(row.paidThisMonth());
                } else if (previousTotalOwed > totalOwed) {
                    existing.setPaidThisMonth(existing.getPaidThisMonth() + (previousTotalOwed - totalOwed));
                }
            } else {
                double pastDue = row.pastDue() != null ? row.pastDue() : 0.0;
                double paidMonth = row.paidThisMonth() != null ? row.paidThisMonth() : 0.0;
                Member created = new Member(UUID.randomUUID().toString(), name, phone, totalOwed, pastDue,
                        paidMonth, determineStatus(totalOwed, pastDue), "Never", false);
                data.members.add(created);
                membersByName.put(name.toLowerCase(Locale.ROOT), created);
            }
        }
        refreshMemberIndex();
        save();
        return rows.size();
    }

    /** The full saved state as pretty-printed JSON, for moving to another computer. */
    public synchronized byte[] exportData() throws Exception {
        ensureNotNull();
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
    }

    /** Replaces all current data with a file produced by {@link #exportData()}. */
    public synchronized void importData(byte[] json) throws Exception {
        DataContainer imported;
        try {
            imported = mapper.readValue(json, DataContainer.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("That file is not a DuesDesk backup.", e);
        }
        if (imported == null) throw new IllegalArgumentException("That backup file is empty.");
        DataContainer previous = data;
        data = imported;
        try {
            ensureNotNull();
            normalizeMembers();
            restoreReceiptFilesFromData(true);
            refreshMemberIndex();
            autoLinkResidents();
            save();
        } catch (RuntimeException e) {
            data = previous;
            refreshMemberIndex();
            throw e;
        }
    }

    public synchronized List<FlowEntry> getFlowEntries() {
        ensureNotNull();
        return new ArrayList<>(data.flowEntries);
    }

    /** Saves a hand-recorded inflow/outflow. Returns false if an entry with the same id (a resubmitted form) exists. */
    public synchronized boolean addManualFlowEntry(FlowEntry entry) {
        ensureNotNull();
        if (entry == null || entry.getId() == null || entry.getId().isBlank()) return false;
        if (data.flowEntries.stream().anyMatch(existing -> entry.getId().equals(existing.getId()))) return false;
        data.flowEntries.add(entry);
        try {
            save();
        } catch (IllegalStateException e) {
            data.flowEntries.remove(entry);
            throw e;
        }
        return true;
    }

    public synchronized int[] importBankCsv(MultipartFile file) throws Exception {
        ensureNotNull();
        List<FlowEntry> parsed = BankCsvParser.parse(new String(file.getBytes(), StandardCharsets.UTF_8));
        Set<String> existing = data.flowEntries.stream().map(FlowEntry::getId).collect(Collectors.toSet());
        // Bank rows already saved, counted by content, so rows imported under an older ID scheme are still
        // recognized. Each saved row can only absorb one incoming row.
        Map<String, Integer> unmatched = new HashMap<>();
        for (FlowEntry saved : data.flowEntries) {
            if (saved.getId() != null && saved.getId().startsWith("bank-")) {
                unmatched.merge(BankCsvParser.contentKey(saved), 1, Integer::sum);
            }
        }
        List<FlowEntry> added = new ArrayList<>();
        int duplicates = 0;
        for (FlowEntry entry : parsed) {
            String key = BankCsvParser.contentKey(entry);
            if (existing.contains(entry.getId()) || unmatched.getOrDefault(key, 0) > 0) {
                unmatched.computeIfPresent(key, (ignored, count) -> count > 1 ? count - 1 : null);
                duplicates++;
            } else {
                existing.add(entry.getId());
                added.add(entry);
            }
        }
        if (!added.isEmpty()) {
            // Bank deposits always wait for the treasurer: a remembered payer alias only suggests (and badges) the member
            // on the Cash Flow page; nothing touches a balance until the match is confirmed.
            data.flowEntries.addAll(added);
            try {
                save();
            } catch (IllegalStateException e) {
                data.flowEntries.removeAll(added);
                throw e;
            }
        }
        return new int[] {added.size(), duplicates};
    }

    public synchronized boolean confirmReconciliation(String flowEntryId, String memberId) {
        return confirmReconciliation(flowEntryId, memberId, true);
    }

    /**
     * Applies a pending imported deposit to a member's balance. With {@code rememberAlias}, the payer name on the
     * transaction is saved as one of the member's {@link Member#getRecognizedPayerAliases() payer aliases}, so future
     * imports from that payer are suggested for this member.
     */
    public synchronized boolean confirmReconciliation(String flowEntryId, String memberId, boolean rememberAlias) {
        ensureNotNull();
        FlowEntry entry = data.flowEntries.stream()
                .filter(item -> flowEntryId.equals(item.getId())).findFirst().orElse(null);
        Member member = membersById.get(memberId);
        if (entry == null || member == null || !"PENDING".equals(entry.getReconciliationStatus())
                || !Double.isFinite(entry.getAmount()) || entry.getAmount() <= 0
                || entry.getDate() == null || entry.getDate().length() < 10) return false;
        LocalDate paymentDate;
        try {
            paymentDate = LocalDate.parse(entry.getDate().substring(0, 10));
        } catch (DateTimeParseException e) {
            return false;
        }
        if (member.getPaymentHistory() == null) member.setPaymentHistory(new ArrayList<>());
        String paymentKey = "flow-reconciliation:" + entry.getId();
        if (paymentKeys.contains(paymentKey)) return false;

        double previousOwed = member.getTotalOwed();
        double previousPastDue = member.getPastDueBalance();
        double previousPaidMonth = member.getPaidThisMonth();
        String previousStatus = member.getStatus();
        List<PaymentInstallment> installments = new ArrayList<>(member.getInstallments());
        List<Double> previousInstallmentPaid = member.getInstallments().stream()
                .map(PaymentInstallment::getPaidAmount).toList();
        if (member.getCharges() == null) member.setCharges(new ArrayList<>());
        List<ChargeRecord> charges = new ArrayList<>(member.getCharges());
        List<Double> previousChargePaid = charges.stream().map(ChargeRecord::getPaidAmount).toList();
        List<Double> previousChargePastDue = charges.stream().map(ChargeRecord::getPastDueAmount).toList();
        String previousFlowStatus = entry.getReconciliationStatus();
        String previousFlowMember = entry.getReconciledMemberId();
        String previousFlowPayment = entry.getReconciledPaymentKey();
        String aliasKey = rememberAlias ? normalizeAlias(entry.getCounterparty()) : "";
        String previousAliasMemberId = aliasKey.isBlank() ? null : data.counterpartyAliases.get(aliasKey);
        List<String> previousPayerAliases = new ArrayList<>(member.getRecognizedPayerAliases());
        PaymentRecord payment = new PaymentRecord(paymentDate.toString(), entry.getAmount(),
                "Reconciled " + entry.getSource() + ": " + entry.getDescription(), paymentKey);
        member.getPaymentHistory().add(payment);
        applyPaymentBalance(member, payment, paymentDate);
        allocateInstallmentPayment(member, entry.getAmount());
        allocateChargePayment(member, entry.getAmount());
        entry.setReconciliationStatus("RECONCILED");
        entry.setReconciledMemberId(memberId);
        entry.setReconciledPaymentKey(paymentKey);
        addPaymentKey(paymentKey);
        if (!aliasKey.isBlank()) {
            data.counterpartyAliases.put(aliasKey, memberId);
            addPayerAlias(member, entry.getCounterparty());
        }
        try {
            save();
            return true;
        } catch (IllegalStateException e) {
            member.getPaymentHistory().remove(payment);
            member.setTotalOwed(previousOwed);
            member.setPastDueBalance(previousPastDue);
            member.setPaidThisMonth(previousPaidMonth);
            member.setStatus(previousStatus);
            member.setInstallments(installments);
            for (int i = 0; i < installments.size(); i++) {
                installments.get(i).setPaidAmount(previousInstallmentPaid.get(i));
            }
            member.setCharges(charges);
            for (int i = 0; i < charges.size(); i++) {
                charges.get(i).setPaidAmount(previousChargePaid.get(i));
                charges.get(i).setPastDueAmount(previousChargePastDue.get(i));
            }
            entry.setReconciliationStatus(previousFlowStatus);
            entry.setReconciledMemberId(previousFlowMember);
            entry.setReconciledPaymentKey(previousFlowPayment);
            if (!aliasKey.isBlank()) {
                if (previousAliasMemberId == null) data.counterpartyAliases.remove(aliasKey);
                else data.counterpartyAliases.put(aliasKey, previousAliasMemberId);
            }
            member.setRecognizedPayerAliases(previousPayerAliases);
            removePaymentKey(paymentKey);
            throw e;
        }
    }

    public synchronized Optional<Member> findMemberByCounterpartyAlias(String counterparty) {
        ensureNotNull();
        String key = normalizeAlias(counterparty);
        if (key.isBlank()) return Optional.empty();
        String memberId = data.counterpartyAliases.get(key);
        if (memberId != null && findMemberById(memberId).isPresent()) return findMemberById(memberId);
        return data.members.stream()
                .filter(member -> member.getRecognizedPayerAliases().stream().anyMatch(alias -> normalizeAlias(alias).equals(key)))
                .findFirst();
    }

    /** Saves a payer name on a member (skipping duplicates and the member's own name). */
    private void addPayerAlias(Member member, String payerName) {
        String key = normalizeAlias(payerName);
        if (key.isBlank() || key.equals(normalizeAlias(member.getName()))) return;
        boolean known = member.getRecognizedPayerAliases().stream().anyMatch(alias -> normalizeAlias(alias).equals(key));
        if (!known) member.getRecognizedPayerAliases().add(payerName.trim());
    }

    /** Forgets one of a member's payer aliases. Returns false if it wasn't saved. */
    public synchronized boolean removePayerAlias(String memberId, String alias) {
        ensureNotNull();
        Member member = membersById.get(memberId);
        if (member == null) return false;
        String key = normalizeAlias(alias);
        List<String> before = new ArrayList<>(member.getRecognizedPayerAliases());
        boolean removed = member.getRecognizedPayerAliases().removeIf(item -> normalizeAlias(item).equals(key));
        String mapped = data.counterpartyAliases.get(key);
        if (memberId.equals(mapped)) data.counterpartyAliases.remove(key);
        if (!removed && !memberId.equals(mapped)) return false;
        saveOrRollback(() -> {
            member.setRecognizedPayerAliases(before);
            if (memberId.equals(mapped)) data.counterpartyAliases.put(key, mapped);
        });
        return true;
    }

    private String normalizeAlias(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    public synchronized boolean ignoreReconciliation(String flowEntryId) {
        FlowEntry entry = data.flowEntries.stream()
                .filter(item -> flowEntryId.equals(item.getId())).findFirst().orElse(null);
        if (entry == null || !"PENDING".equals(entry.getReconciliationStatus())) return false;
        entry.setReconciliationStatus("IGNORED");
        try {
            save();
            return true;
        } catch (IllegalStateException e) {
            entry.setReconciliationStatus("PENDING");
            throw e;
        }
    }

    public synchronized int addBatchCharges(List<String> memberIds, String description, double amount, String dueDate) {
        ensureNotNull();
        if (memberIds == null || memberIds.isEmpty() || description == null || description.isBlank()
                || !Double.isFinite(amount) || amount <= 0) return 0;
        LocalDate due;
        try {
            due = LocalDate.parse(dueDate);
        } catch (DateTimeParseException | NullPointerException e) {
            return 0;
        }
        List<Member> members = memberIds.stream().distinct().map(membersById::get).filter(Objects::nonNull).toList();
        if (members.isEmpty() || members.size() != memberIds.stream().distinct().count()) return 0;
        Map<Member, MemberBalanceSnapshot> previous = new LinkedHashMap<>();
        for (Member member : members) {
            if (member.getCharges() == null) member.setCharges(new ArrayList<>());
            previous.put(member, new MemberBalanceSnapshot(member.getTotalOwed(), member.getPastDueBalance(),
                    member.getStatus(), new ArrayList<>(member.getCharges())));
            double pastDueAmount = due.isBefore(LocalDate.now()) ? amount : 0;
            member.getCharges().add(new ChargeRecord(UUID.randomUUID().toString(), LocalDate.now().toString(),
                    description.trim(), amount, pastDueAmount));
            member.getCharges().getLast().setDueDate(due.toString());
            member.setTotalOwed(member.getTotalOwed() + amount);
            member.setPastDueBalance(member.getPastDueBalance() + pastDueAmount);
            member.setStatus(determineStatus(member.getTotalOwed(), member.getPastDueBalance()));
        }
        try {
            save();
        } catch (IllegalStateException e) {
            previous.forEach((member, snapshot) -> {
                member.setTotalOwed(snapshot.totalOwed());
                member.setPastDueBalance(snapshot.pastDue());
                member.setStatus(snapshot.status());
                member.setCharges(snapshot.charges());
            });
            throw e;
        }
        return members.size();
    }

    /** What happened to one fine or discount; {@code amount} is what was actually applied (discounts stop at $0). */
    public record AdjustmentResult(boolean applied, double amount, String message) {}

    public static final int MAX_CHARGE_PRESETS = 50;

    /**
     * Adds a named fine (a charge, due {@code dueDate} or today) or discount to one member's account.
     * A discount is recorded as a dues credit rather than a payment, so it never shows up as money received; it
     * lowers the balance, settles the earliest charges and installments first, and is capped at what the member owes.
     */
    public synchronized AdjustmentResult addMemberAdjustment(String memberId, String type, String name, double amount,
                                                             String dueDate, LocalDate today) {
        ensureNotNull();
        Member member = memberId == null ? null : membersById.get(memberId);
        String label = name == null ? "" : name.trim();
        if (member == null) return new AdjustmentResult(false, 0, "Choose a member.");
        if (label.isEmpty() || label.length() > 120) return new AdjustmentResult(false, 0, "Name the charge (up to 120 characters).");
        if (!Double.isFinite(amount) || !Money.positive(amount) || amount > 100_000) {
            return new AdjustmentResult(false, 0, "Enter an amount greater than zero.");
        }
        if (member.getCharges() == null) member.setCharges(new ArrayList<>());
        if (member.getInstallments() == null) member.setInstallments(new ArrayList<>());
        double previousOwed = member.getTotalOwed();
        double previousPastDue = member.getPastDueBalance();
        String previousStatus = member.getStatus();
        List<ChargeRecord> previousCharges = new ArrayList<>(member.getCharges());
        List<Double> previousChargePaid = previousCharges.stream().map(ChargeRecord::getPaidAmount).toList();
        List<Double> previousChargePastDue = previousCharges.stream().map(ChargeRecord::getPastDueAmount).toList();
        List<Double> previousInstallmentPaid = member.getInstallments().stream().map(PaymentInstallment::getPaidAmount).toList();
        List<MemberCredit> previousCredits = new ArrayList<>(member.getCredits());

        double applied;
        if (ChargePreset.DISCOUNT.equals(type)) {
            if (!Money.positive(member.getTotalOwed())) {
                return new AdjustmentResult(false, 0, member.getName() + " has no balance to discount.");
            }
            applied = Money.cents(Math.min(amount, member.getTotalOwed()));
            member.getCredits().add(new MemberCredit(UUID.randomUUID().toString(), today.toString(), label, applied, null));
            member.setTotalOwed(Money.cents(Math.max(0, member.getTotalOwed() - applied)));
            member.setPastDueBalance(Money.cents(Math.max(0, member.getPastDueBalance() - applied)));
            allocateInstallmentPayment(member, applied);
            allocateChargePayment(member, applied);
        } else {
            LocalDate due = today;
            if (dueDate != null && !dueDate.isBlank()) {
                try {
                    due = LocalDate.parse(dueDate.trim());
                } catch (DateTimeParseException e) {
                    return new AdjustmentResult(false, 0, "Choose a valid due date.");
                }
            }
            applied = Money.cents(amount);
            double pastDueAmount = due.isBefore(today) ? applied : 0;
            ChargeRecord charge = new ChargeRecord(UUID.randomUUID().toString(), today.toString(), label, applied, pastDueAmount);
            charge.setDueDate(due.toString());
            member.getCharges().add(charge);
            member.setTotalOwed(Money.cents(member.getTotalOwed() + applied));
            member.setPastDueBalance(Money.cents(member.getPastDueBalance() + pastDueAmount));
        }
        member.setStatus(determineStatus(member.getTotalOwed(), member.getPastDueBalance()));
        try {
            save();
        } catch (IllegalStateException e) {
            member.setTotalOwed(previousOwed);
            member.setPastDueBalance(previousPastDue);
            member.setStatus(previousStatus);
            member.setCharges(previousCharges);
            for (int i = 0; i < previousCharges.size(); i++) {
                previousCharges.get(i).setPaidAmount(previousChargePaid.get(i));
                previousCharges.get(i).setPastDueAmount(previousChargePastDue.get(i));
            }
            for (int i = 0; i < previousInstallmentPaid.size(); i++) {
                member.getInstallments().get(i).setPaidAmount(previousInstallmentPaid.get(i));
            }
            member.setCredits(previousCredits);
            throw e;
        }
        String money = String.format(java.util.Locale.US, "$%,.2f", applied);
        if (ChargePreset.DISCOUNT.equals(type)) {
            return new AdjustmentResult(true, applied, "Took " + money + " off " + member.getName() + "'s balance (" + label + ")."
                    + (applied < Money.cents(amount) ? " The discount was capped at what they owed." : ""));
        }
        return new AdjustmentResult(true, applied, "Added " + label + " (" + money + ") to " + member.getName() + "'s account.");
    }

    public synchronized List<ChargePreset> getChargePresets() {
        ensureNotNull();
        return List.copyOf(data.settings.getSavedChargePresets());
    }

    /** Saves a reusable fine or discount; one with the same name and type is updated instead of duplicated. */
    public synchronized boolean saveChargePreset(String name, double amount, String type) {
        ensureNotNull();
        String label = name == null ? "" : name.trim();
        if (label.isEmpty() || label.length() > 120 || !Double.isFinite(amount) || !Money.positive(amount) || amount > 100_000) return false;
        String presetType = ChargePreset.DISCOUNT.equals(type) ? ChargePreset.DISCOUNT : ChargePreset.FINE;
        List<ChargePreset> presets = data.settings.getSavedChargePresets();
        List<ChargePreset> previous = new ArrayList<>(presets);
        ChargePreset existing = presets.stream()
                .filter(p -> p.getType().equals(presetType) && p.getName().equalsIgnoreCase(label)).findFirst().orElse(null);
        double previousAmount = existing == null ? 0 : existing.getAmount();
        if (existing != null) {
            existing.setAmount(amount);
        } else {
            if (presets.size() >= MAX_CHARGE_PRESETS) return false;
            presets.add(new ChargePreset(UUID.randomUUID().toString(), label, amount, presetType));
        }
        try {
            save();
            return true;
        } catch (IllegalStateException e) {
            if (existing != null) existing.setAmount(previousAmount);
            data.settings.setSavedChargePresets(previous);
            throw e;
        }
    }

    public synchronized boolean removeChargePreset(String presetId) {
        ensureNotNull();
        List<ChargePreset> presets = data.settings.getSavedChargePresets();
        List<ChargePreset> previous = new ArrayList<>(presets);
        if (presetId == null || !presets.removeIf(p -> presetId.equals(p.getId()))) return false;
        try {
            save();
            return true;
        } catch (IllegalStateException e) {
            data.settings.setSavedChargePresets(previous);
            throw e;
        }
    }

    public synchronized boolean addInstallments(String memberId, List<PaymentInstallment> installments) {
        Member member = membersById.get(memberId);
        if (member == null || installments == null || installments.isEmpty()) return false;
        double total = installments.stream().mapToDouble(PaymentInstallment::getAmount).sum();
        double alreadyScheduled = member.getInstallments() == null ? 0
                : member.getInstallments().stream().mapToDouble(PaymentInstallment::getRemainingAmount).sum();
        if (!Double.isFinite(total) || total <= 0
                || total + alreadyScheduled > member.getTotalOwed() + 0.005) return false;
        for (PaymentInstallment installment : installments) {
            if (installment.getDueDate() == null || installment.getDescription() == null
                    || installment.getDescription().isBlank() || !Double.isFinite(installment.getAmount())
                    || installment.getAmount() <= 0) return false;
            try {
                LocalDate.parse(installment.getDueDate());
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        if (member.getInstallments() == null) member.setInstallments(new ArrayList<>());
        List<PaymentInstallment> previous = new ArrayList<>(member.getInstallments());
        member.getInstallments().addAll(installments);
        member.getInstallments().sort(Comparator.comparing(PaymentInstallment::getDueDate));
        try {
            save();
        } catch (IllegalStateException e) {
            member.setInstallments(previous);
            throw e;
        }
        return true;
    }

    public synchronized boolean removeInstallment(String memberId, String installmentId) {
        Member member = membersById.get(memberId);
        if (member == null || member.getInstallments() == null) return false;
        PaymentInstallment installment = member.getInstallments().stream()
                .filter(item -> installmentId.equals(item.getId())).findFirst().orElse(null);
        if (installment == null) return false;
        member.getInstallments().remove(installment);
        try {
            save();
            return true;
        } catch (IllegalStateException e) {
            member.getInstallments().add(installment);
            member.getInstallments().sort(Comparator.comparing(PaymentInstallment::getDueDate));
            throw e;
        }
    }

    private record MemberBalanceSnapshot(double totalOwed, double pastDue, String status, List<ChargeRecord> charges) {}

    private void applyPaymentBalance(Member member, PaymentRecord payment, LocalDate paymentDate) {
        member.setTotalOwed(Math.max(0, member.getTotalOwed() - payment.getAmount()));
        member.setPastDueBalance(Math.max(0, member.getPastDueBalance() - payment.getAmount()));
        if (YearMonth.from(paymentDate).equals(YearMonth.now())) {
            member.setPaidThisMonth(member.getPaidThisMonth() + payment.getAmount());
        }
        member.setStatus(determineStatus(member.getTotalOwed(), member.getPastDueBalance()));
    }

    private void allocateInstallmentPayment(Member member, double amount) {
        if (member.getInstallments() == null) return;
        double remaining = amount;
        for (PaymentInstallment installment : member.getInstallments().stream()
                .sorted(Comparator.comparing(PaymentInstallment::getDueDate)).toList()) {
            double allocated = Math.min(remaining, installment.getRemainingAmount());
            if (allocated > 0) {
                installment.setPaidAmount(installment.getPaidAmount() + allocated);
                remaining -= allocated;
            }
            if (remaining <= 0.005) break;
        }
    }

    private void allocateChargePayment(Member member, double amount) {
        if (member.getCharges() == null) return;
        double remaining = amount;
        List<ChargeRecord> ordered = member.getCharges().stream()
                .sorted(Comparator.comparing(charge -> charge.getDueDate() == null ? charge.getDate() : charge.getDueDate()))
                .toList();
        for (ChargeRecord charge : ordered) {
            double allocated = Math.min(remaining, charge.getRemainingAmount());
            if (allocated > 0) {
                charge.setPaidAmount(charge.getPaidAmount() + allocated);
                charge.setPastDueAmount(Math.max(0, charge.getPastDueAmount() - allocated));
                remaining -= allocated;
            }
            if (remaining <= 0.005) break;
        }
    }

    private void addPaymentKey(String key) {
        Set<String> updated = new HashSet<>(paymentKeys);
        updated.add(key);
        paymentKeys = Set.copyOf(updated);
    }

    private void removePaymentKey(String key) {
        Set<String> updated = new HashSet<>(paymentKeys);
        updated.remove(key);
        paymentKeys = Set.copyOf(updated);
    }

    public synchronized List<MetricSnapshot> getMetricHistory() {
        ensureNotNull();
        return new ArrayList<>(data.metricHistory);
    }

    private void captureMetricSnapshot() {
        LocalDate today = LocalDate.now();
        double recordedPayments = data.members.stream()
                .filter(member -> member.getPaymentHistory() != null)
                .flatMap(member -> member.getPaymentHistory().stream())
                .mapToDouble(PaymentRecord::getAmount)
                .sum();
        double outstanding = data.members.stream().mapToDouble(Member::getTotalOwed).sum();
        double pastDue = data.members.stream().mapToDouble(Member::getPastDueBalance).sum();
        double monthlyInflow = data.members.stream().mapToDouble(Member::getPaidThisMonth).sum();
        double pool = recordedPayments + outstanding;
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("collection-velocity-run-rate", monthlyInflow / today.getDayOfMonth());
        values.put("net-collection-rate", pool > 0 ? recordedPayments / pool * 100 : 0);
        values.put("delinquency-default-rate", pool > 0 ? pastDue / pool * 100 : 0);
        values.put("average-dues-per-member-adpm",
                data.members.isEmpty() ? 0 : recordedPayments / data.members.size());

        int lastIndex = data.metricHistory.size() - 1;
        if (lastIndex >= 0 && today.toString().equals(data.metricHistory.get(lastIndex).date())) {
            data.metricHistory.set(lastIndex, new MetricSnapshot(today.toString(), Map.copyOf(values)));
        } else {
            data.metricHistory.add(new MetricSnapshot(today.toString(), Map.copyOf(values)));
        }
        if (data.metricHistory.size() > 2000) {
            data.metricHistory.subList(0, data.metricHistory.size() - 2000).clear();
        }
    }

    /** Adds new Venmo rows (already-imported Venmo IDs are ignored). Returns {added, duplicates, skipped}. */
    public synchronized int[] importVenmoCsv(MultipartFile file) throws Exception {
        ensureNotNull();
        VenmoCsvParser.Result result = VenmoCsvParser.parse(new String(file.getBytes(), StandardCharsets.UTF_8));
        Set<String> existing = data.flowEntries.stream().map(FlowEntry::getId).collect(Collectors.toSet());
        List<FlowEntry> added = new ArrayList<>();
        int duplicates = 0;
        for (FlowEntry entry : result.entries()) {
            if (existing.add(entry.getId())) added.add(entry);
            else duplicates++;
        }
        if (!added.isEmpty()) {
            data.flowEntries.addAll(added);
            boolean autoReconciled = false;
            try {
                for (FlowEntry entry : added) {
                    if (entry.getAmount() <= 0) continue;
                    Optional<Member> match = findMemberByCounterpartyAlias(entry.getCounterparty());
                    if (match.isPresent() && confirmReconciliation(entry.getId(), match.get().getId())) {
                        autoReconciled = true;
                    }
                }
                if (!autoReconciled) save();
            } catch (IllegalStateException e) {
                if (!autoReconciled) data.flowEntries.removeAll(added);
                throw e;
            }
        }
        return new int[] {added.size(), duplicates, result.skipped()};
    }

    public synchronized boolean recordPayment(String memberId, PaymentRecord payment) {
        ensureNotNull();
        Member member = membersById.get(memberId);
        if (member == null || payment == null || payment.getDate() == null
                || !Double.isFinite(payment.getAmount()) || payment.getAmount() <= 0
                || payment.getIdempotencyKey() == null || payment.getIdempotencyKey().isBlank()) return false;
        java.time.LocalDate paymentDate;
        try {
            paymentDate = java.time.LocalDate.parse(payment.getDate());
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
        if (member.getPaymentHistory() == null) member.setPaymentHistory(new ArrayList<>());
        if (paymentKeys.contains(payment.getIdempotencyKey())) return false;
        double previousTotalOwed = member.getTotalOwed();
        double previousPastDueBalance = member.getPastDueBalance();
        double previousPaidThisMonth = member.getPaidThisMonth();
        String previousStatus = member.getStatus();
        if (member.getInstallments() == null) member.setInstallments(new ArrayList<>());
        List<PaymentInstallment> previousInstallments = new ArrayList<>(member.getInstallments());
        List<Double> previousInstallmentPaid = member.getInstallments().stream()
                .map(PaymentInstallment::getPaidAmount).toList();
        if (member.getCharges() == null) member.setCharges(new ArrayList<>());
        List<ChargeRecord> previousCharges = new ArrayList<>(member.getCharges());
        List<Double> previousChargePaid = previousCharges.stream().map(ChargeRecord::getPaidAmount).toList();
        List<Double> previousChargePastDue = previousCharges.stream().map(ChargeRecord::getPastDueAmount).toList();
        member.getPaymentHistory().add(payment);
        addPaymentKey(payment.getIdempotencyKey());
        applyPaymentBalance(member, payment, paymentDate);
        allocateInstallmentPayment(member, payment.getAmount());
        allocateChargePayment(member, payment.getAmount());
        try {
            save();
        } catch (IllegalStateException e) {
            member.getPaymentHistory().remove(payment);
            member.setTotalOwed(previousTotalOwed);
            member.setPastDueBalance(previousPastDueBalance);
            member.setPaidThisMonth(previousPaidThisMonth);
            member.setStatus(previousStatus);
            member.setInstallments(previousInstallments);
            for (int i = 0; i < previousInstallments.size(); i++) {
                previousInstallments.get(i).setPaidAmount(previousInstallmentPaid.get(i));
            }
            member.setCharges(previousCharges);
            for (int i = 0; i < previousCharges.size(); i++) {
                previousCharges.get(i).setPaidAmount(previousChargePaid.get(i));
                previousCharges.get(i).setPastDueAmount(previousChargePastDue.get(i));
            }
            removePaymentKey(payment.getIdempotencyKey());
            throw e;
        }
        return true;
    }

    public String determineStatus(double totalOwed, double pastDue) {
        if (!Money.positive(totalOwed)) return "Paid in Full";
        if (Money.positive(pastDue)) return "Delinquent";
        return "Payment Plan";
    }

    public synchronized boolean isReadable() {
        try {
            Path target = Path.of(DATA_FILE).toAbsolutePath();
            return Files.exists(target) ? Files.isReadable(target) : data != null && data.members != null;
        } catch (RuntimeException e) {
            log.error("Unable to inspect dues data store health", e);
            return false;
        }
    }
}
