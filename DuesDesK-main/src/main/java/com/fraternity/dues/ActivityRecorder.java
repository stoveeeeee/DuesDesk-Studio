package com.fraternity.dues;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Works out what a save changed by comparing the saved data with the previous save, and turns the differences into
 * readable activity entries with before and after values. Because it compares saved states rather than hooking each
 * form, every change is recorded: payments, charges, deletions, imports, settings, automatic updates.
 */
final class ActivityRecorder {

    /** How one kind of record is compared and described. */
    record Kind(String type, String category, String key, Function<JsonNode, String> label, Map<String, Kind> children,
                Set<String> ignored, Set<String> money, Function<JsonNode, Double> amount) {
        boolean singleton() { return key == null; }
    }

    private static final Set<String> MEMBER_BALANCE = Set.of("totalOwed", "pastDueBalance", "paidThisMonth");
    private static final Set<String> RESIDENT_BALANCE = Set.of("balanceDue");
    private static final Set<String> BALANCE_DRIVERS = Set.of("charges", "paymentHistory", "credits", "installments");
    private static final Set<String> ALLOCATION_FIELDS = Set.of("paidAmount", "pastDueAmount");
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("totalOwed", "Balance"), Map.entry("pastDueBalance", "Past due"),
            Map.entry("paidThisMonth", "Paid this month"), Map.entry("balanceDue", "Rent balance"),
            Map.entry("phoneNumber", "Phone"), Map.entry("muteReminders", "Reminders muted"),
            Map.entry("clearedUntilDate", "Clearance vouch"), Map.entry("recognizedPayerAliases", "Payer names"),
            Map.entry("venmoDisplayName", "Venmo name"), Map.entry("duesCode", "Dues code"),
            Map.entry("officerReviewSince", "Officer review"), Map.entry("officerReviewReason", "Review reason"),
            Map.entry("pastDueSince", "Past due since"), Map.entry("smsTemplate", "Friendly reminder text"),
            Map.entry("autoSendEnabled", "Automatic reminders"), Map.entry("ihqBillAmount", "IHQ bill"),
            Map.entry("ihqStartDate", "IHQ start date"), Map.entry("reconciliationStatus", "Reconciliation"),
            Map.entry("reconciledMemberId", "Matched member"), Map.entry("chairMemberId", "Chair member"),
            Map.entry("budgetCap", "Budget cap"), Map.entry("membershipStatus", "Roster"), Map.entry("linkedMemberId", "Linked member"));

    static final Map<String, Kind> SECTIONS;

    static {
        Kind charge = new Kind("Charge", "Charges", "id", text("description"), Map.of(), Set.of(),
                Set.of("amount", "pastDueAmount", "paidAmount"), number("amount"));
        Kind payment = new Kind("Payment", "Payments", "idempotencyKey", text("description"), Map.of(), Set.of(),
                Set.of("amount"), number("amount"));
        Kind installment = new Kind("Installment", "Payment plans", "id",
                node -> firstNonBlank(text(node, "description"), "Installment due " + text(node, "dueDate")), Map.of(), Set.of(),
                Set.of("amount", "paidAmount"), number("amount"));
        Kind credit = new Kind("Credit", "Credits & discounts", "id", text("description"), Map.of(), Set.of(),
                Set.of("amount"), number("amount"));
        Kind step = new Kind("Reminder step", "Reminders", "id", node -> ReminderStep.title(text(node, "stage")), Map.of(),
                Set.of(), Set.of("amount", "balance", "pastDue"), number("amount"));
        Map<String, Kind> memberChildren = new LinkedHashMap<>();
        memberChildren.put("charges", charge);
        memberChildren.put("paymentHistory", payment);
        memberChildren.put("installments", installment);
        memberChildren.put("credits", credit);
        memberChildren.put("reminderLog", step);
        Kind member = new Kind("Member", "Members", "id", text("name"), memberChildren,
                Set.of("status", "lastTextedDate"), MEMBER_BALANCE, node -> null);
        Kind archived = new Kind("Archived member", "Members", "id", text("name"), Map.of(),
                Set.of("status", "lastTextedDate", "charges", "paymentHistory", "installments", "credits", "reminderLog"),
                MEMBER_BALANCE, node -> null);
        Kind rentCharge = new Kind("Rent charge", "Rent", "id", text("description"), Map.of(), Set.of(),
                Set.of("amount", "pastDueAmount", "paidAmount"), number("amount"));
        Kind resident = new Kind("Resident", "Rent", "id", text("name"), Map.of("charges", rentCharge),
                Set.of("lastTextedDate"), Set.of("monthlyRent", "balanceDue"), node -> null);
        Kind flow = new Kind("Cash flow entry", "Cash flow", "id",
                node -> firstNonBlank(text(node, "description"), text(node, "counterparty")), Map.of(), Set.of(),
                Set.of("amount"), number("amount"));
        Kind settlement = new Kind("Bill payment", "Bills", "dueDate", node -> "Due " + text(node, "dueDate"), Map.of(),
                Set.of(), Set.of("amount"), number("amount"));
        Kind bill = new Kind("Bill", "Bills", "id", text("name"), Map.of("settlements", settlement), Set.of(),
                Set.of("amount"), number("amount"));
        Kind reimbursement = new Kind("Chair expense", "Chair budgets", "id", text("description"), Map.of(), Set.of("receiptFileName"),
                Set.of("amount"), number("amount"));
        Kind raised = new Kind("Money raised", "Chair budgets", "id", text("description"), Map.of(), Set.of(),
                Set.of("amount"), number("amount"));
        Map<String, Kind> chairChildren = new LinkedHashMap<>();
        chairChildren.put("reimbursements", reimbursement);
        chairChildren.put("raised", raised);
        Kind chair = new Kind("Chair budget", "Chair budgets", "id", text("chairName"), chairChildren, Set.of(),
                Set.of("budgetCap", "raiseGoal"), number("budgetCap"));
        Kind sale = new Kind("Event sale", "Events", "id",
                node -> firstNonBlank(text(node, "buyerName"), text(node, "description")), Map.of(), Set.of(),
                Set.of("unitPrice"), node -> node.path("quantity").asDouble(1) * node.path("unitPrice").asDouble());
        Kind expense = new Kind("Event expense", "Events", "id", text("description"), Map.of(), Set.of(),
                Set.of("amount"), number("amount"));
        Map<String, Kind> eventChildren = new LinkedHashMap<>();
        eventChildren.put("sales", sale);
        eventChildren.put("expenses", expense);
        Kind event = new Kind("Event", "Events", "id", text("name"), eventChildren, Set.of(),
                Set.of("ticketPrice", "revenueGoal"), node -> null);
        Kind preset = new Kind("Charge preset", "Settings", "id", text("name"), Map.of(), Set.of(), Set.of("amount"),
                number("amount"));
        Kind settings = new Kind("Settings", "Settings", null, node -> "Settings", Map.of("savedChargePresets", preset),
                Set.of("lastAutoRunDate", "cloudBackupStatus", "notificationsShown"),
                Set.of("ihqBillAmount", "lateFeeAmount", "socialClearanceTolerance", "openingCashBalance"), node -> null);
        Kind aliases = new Kind("Payer names", "Payer names", null, node -> "Payer names", Map.of(), Set.of(), Set.of(),
                node -> null);

        Map<String, Kind> sections = new LinkedHashMap<>();
        sections.put("members", member);
        sections.put("archivedMembers", archived);
        sections.put("residents", resident);
        sections.put("flowEntries", flow);
        sections.put("recurringBills", bill);
        sections.put("chairBudgets", chair);
        sections.put("presaleCampaigns", event);
        sections.put("settings", settings);
        sections.put("counterpartyAliases", aliases);
        SECTIONS = java.util.Collections.unmodifiableMap(sections);
    }

    private final ObjectMapper mapper;
    private ObjectNode baseline;

    ActivityRecorder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The parts of the saved data the log compares (not receipts, text logs or stat history). */
    ObjectNode snapshot(Store.DataContainer data) {
        ObjectNode node = mapper.createObjectNode();
        node.set("members", mapper.valueToTree(data.members));
        node.set("archivedMembers", mapper.valueToTree(data.archivedMembers));
        node.set("residents", mapper.valueToTree(data.residents));
        node.set("flowEntries", mapper.valueToTree(data.flowEntries));
        node.set("recurringBills", mapper.valueToTree(data.recurringBills));
        node.set("chairBudgets", mapper.valueToTree(data.chairBudgets));
        node.set("presaleCampaigns", mapper.valueToTree(data.presaleCampaigns));
        node.set("settings", mapper.valueToTree(data.settings));
        node.set("counterpartyAliases", mapper.valueToTree(data.counterpartyAliases));
        return node;
    }

    ObjectNode baseline() { return baseline; }

    void reset(ObjectNode snapshot) { this.baseline = snapshot; }

    static String keyField(String section, String collection) {
        Kind kind = SECTIONS.get(section);
        if (kind == null) return "id";
        if (collection == null || collection.isBlank()) return kind.key() == null ? "id" : kind.key();
        Kind child = kind.children().get(collection);
        return child == null || child.key() == null ? "id" : child.key();
    }

    // ------------------------------------------------------------------ diff

    private record Owner(String section, String id, String name, boolean member, boolean resident,
                         JsonNode before, JsonNode after) {}

    /** Every difference between two snapshots as activity entries sharing one batch id; empty when nothing changed. */
    List<ActivityEntry> diff(ObjectNode before, ObjectNode after, String source, LocalDateTime now, String batchId) {
        if (before == null || after == null) return List.of();
        List<ActivityEntry> out = new ArrayList<>();
        Map<String, Owner> owners = new LinkedHashMap<>();
        for (Map.Entry<String, Kind> section : SECTIONS.entrySet()) {
            Kind kind = section.getValue();
            JsonNode b = before.get(section.getKey());
            JsonNode a = after.get(section.getKey());
            if (kind.singleton()) {
                diffEntity(section.getKey(), kind, null, b, a, null, null, -1, out, owners);
            } else {
                diffList(section.getKey(), kind, b, a, null, null, out, owners);
            }
        }
        if (out.isEmpty()) return List.of();
        markArchived(out);
        foldBalanceChanges(out, owners);
        String batch = batchId == null || batchId.isBlank() ? UUID.randomUUID().toString() : batchId;
        String at = now.truncatedTo(ChronoUnit.SECONDS).toString();
        String label = source == null || source.isBlank() ? "Automatic update" : source;
        for (ActivityEntry entry : out) {
            entry.setId(UUID.randomUUID().toString());
            entry.setBatchId(batch);
            entry.setAt(at);
            entry.setSource(label);
            entry.setSummary(summary(entry));
        }
        return out;
    }

    private void diffList(String section, Kind kind, JsonNode before, JsonNode after, Owner owner, String collection,
                          List<ActivityEntry> out, Map<String, Owner> owners) {
        Map<String, Integer> beforeIndex = index(before, kind.key());
        Map<String, Integer> afterIndex = index(after, kind.key());
        for (Map.Entry<String, Integer> item : beforeIndex.entrySet()) {
            if (afterIndex.containsKey(item.getKey())) continue;
            JsonNode node = before.get(item.getValue());
            out.add(entry(section, kind, owner, collection, item.getKey(), item.getValue(), ActivityEntry.REMOVED, node));
        }
        for (Map.Entry<String, Integer> item : afterIndex.entrySet()) {
            if (beforeIndex.containsKey(item.getKey())) continue;
            JsonNode node = after.get(item.getValue());
            out.add(entry(section, kind, owner, collection, item.getKey(), item.getValue(), ActivityEntry.ADDED, node));
        }
        for (Map.Entry<String, Integer> item : afterIndex.entrySet()) {
            Integer previous = beforeIndex.get(item.getKey());
            if (previous == null) continue;
            diffEntity(section, kind, item.getKey(), before.get(previous), after.get(item.getValue()), owner, collection,
                    item.getValue(), out, owners);
        }
    }

    private void diffEntity(String section, Kind kind, String key, JsonNode before, JsonNode after, Owner owner,
                            String collection, int index, List<ActivityEntry> out, Map<String, Owner> owners) {
        if (before == null || after == null || before.equals(after)) return;
        List<ActivityEntry.FieldChange> changes = new ArrayList<>();
        Set<String> fields = new LinkedHashSet<>();
        before.fieldNames().forEachRemaining(fields::add);
        after.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            if (kind.ignored().contains(field) || kind.children().containsKey(field)) continue;
            JsonNode b = before.get(field);
            JsonNode a = after.get(field);
            if (same(b, a)) continue;
            changes.add(new ActivityEntry.FieldChange(field, fieldLabel(field), display(field, kind, b), display(field, kind, a),
                    raw(b), raw(a)));
        }
        Owner self = owner;
        if (owner == null && key != null) {
            boolean member = "members".equals(section);
            boolean resident = "residents".equals(section);
            self = new Owner(section, key, kind.label().apply(after), member, resident, before, after);
            owners.put(section + "|" + key, self);
        }
        if (!changes.isEmpty()) {
            ActivityEntry entry = entry(section, kind, owner, collection, key, index, ActivityEntry.CHANGED, after);
            entry.setChanges(changes);
            out.add(entry);
        }
        for (Map.Entry<String, Kind> child : kind.children().entrySet()) {
            diffList(section, child.getValue(), before.get(child.getKey()), after.get(child.getKey()), self, child.getKey(),
                    out, owners);
        }
    }

    private ActivityEntry entry(String section, Kind kind, Owner owner, String collection, String key, int index,
                                String action, JsonNode node) {
        ActivityEntry entry = new ActivityEntry();
        entry.setAction(action);
        entry.setSection(section);
        entry.setCollection(collection);
        entry.setItemKey(key);
        entry.setItemIndex(index);
        entry.setEntityType(kind.type());
        entry.setCategory(kind.category());
        entry.setEntityLabel(clip(kind.label().apply(node), 120));
        Double amount = node == null ? null : kind.amount().apply(node);
        entry.setAmount(amount == null || !Double.isFinite(amount) ? null : Money.cents(amount));
        if (owner != null) {
            entry.setParentId(owner.id());
            if (owner.member()) {
                entry.setMemberId(owner.id());
                entry.setMemberName(owner.name());
            } else {
                entry.setMemberName(owner.name());
            }
        } else if ("members".equals(section) || "archivedMembers".equals(section)) {
            entry.setMemberId(key);
            entry.setMemberName(kind.label().apply(node));
        } else if ("residents".equals(section)) {
            entry.setMemberName(kind.label().apply(node));
        }
        if (ActivityEntry.REMOVED.equals(action) && node != null && !"archivedMembers".equals(section)) {
            entry.setItemJson(scrubbed(node).toString());
        }
        if ("members".equals(section) && owner == null && node != null) {
            double balance = node.path("totalOwed").asDouble();
            if (ActivityEntry.ADDED.equals(action)) {
                entry.setBalanceBefore(0.0);
                entry.setBalanceAfter(Money.cents(balance));
            } else if (ActivityEntry.REMOVED.equals(action)) {
                entry.setBalanceBefore(Money.cents(balance));
                entry.setBalanceAfter(0.0);
            }
        }
        return entry;
    }

    /**
     * A deleted receipt image is gone for good, so its file name is not kept in the log either: deleted receipt data
     * must not linger in saves and backups. An undo brings the record back without its receipt.
     */
    private static JsonNode scrubbed(JsonNode node) {
        JsonNode copy = node.deepCopy();
        scrub(copy);
        return copy;
    }

    private static void scrub(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has("receiptFileName")) object.putNull("receiptFileName");
            object.forEach(ActivityRecorder::scrub);
        } else if (node != null && node.isArray()) {
            node.forEach(ActivityRecorder::scrub);
        }
    }

    /** A semester rollover moves members to the archive; that reads as one "archived" entry, not a deletion. */
    private void markArchived(List<ActivityEntry> out) {
        Set<String> archivedIds = new java.util.HashSet<>();
        for (ActivityEntry entry : out) {
            if ("archivedMembers".equals(entry.getSection()) && ActivityEntry.ADDED.equals(entry.getAction())) {
                archivedIds.add(entry.getItemKey());
            }
        }
        if (archivedIds.isEmpty()) return;
        out.removeIf(entry -> "archivedMembers".equals(entry.getSection()) && ActivityEntry.ADDED.equals(entry.getAction())
                && archivedIds.contains(entry.getItemKey()));
        for (ActivityEntry entry : out) {
            if ("members".equals(entry.getSection()) && entry.getParentId() == null
                    && ActivityEntry.REMOVED.equals(entry.getAction()) && archivedIds.contains(entry.getItemKey())) {
                entry.setAction(ActivityEntry.ARCHIVED);
                entry.setItemJson(null);
                entry.setBalanceAfter(entry.getBalanceBefore());
            }
        }
    }

    /**
     * When a charge, payment, credit or installment moved a balance, the balance change is shown on that item (with the
     * balance before and after) instead of as a separate "member updated" line, and the paid amounts it spread across
     * charges and installments are left out as noise.
     */
    private void foldBalanceChanges(List<ActivityEntry> out, Map<String, Owner> owners) {
        Map<String, List<ActivityEntry>> nestedByOwner = new HashMap<>();
        Map<String, ActivityEntry> ownerEntries = new HashMap<>();
        for (ActivityEntry entry : out) {
            String section = entry.getSection();
            if (!"members".equals(section) && !"residents".equals(section)) continue;
            if (entry.getParentId() != null) {
                nestedByOwner.computeIfAbsent(section + "|" + entry.getParentId(), k -> new ArrayList<>()).add(entry);
            } else if (ActivityEntry.CHANGED.equals(entry.getAction())) {
                ownerEntries.put(section + "|" + entry.getItemKey(), entry);
            }
        }
        List<ActivityEntry> drop = new ArrayList<>();
        for (Map.Entry<String, Owner> item : owners.entrySet()) {
            Owner owner = item.getValue();
            if (!owner.member() && !owner.resident()) continue;
            String balanceField = owner.member() ? "totalOwed" : "balanceDue";
            Set<String> balanceFields = owner.member() ? MEMBER_BALANCE : RESIDENT_BALANCE;
            Double before = owner.before().has(balanceField) ? Money.cents(owner.before().path(balanceField).asDouble()) : null;
            Double after = owner.after().has(balanceField) ? Money.cents(owner.after().path(balanceField).asDouble()) : null;
            List<ActivityEntry> nested = nestedByOwner.getOrDefault(item.getKey(), List.of());
            ActivityEntry ownerEntry = ownerEntries.get(item.getKey());
            boolean driven = nested.stream().anyMatch(entry -> BALANCE_DRIVERS.contains(entry.getCollection())
                    && !ActivityEntry.CHANGED.equals(entry.getAction()));
            boolean moneyMoved = nested.stream().anyMatch(entry -> ("paymentHistory".equals(entry.getCollection())
                    || "credits".equals(entry.getCollection())) && !ActivityEntry.CHANGED.equals(entry.getAction()));
            List<ActivityEntry.FieldChange> balanceChanges = new ArrayList<>();
            if (driven && ownerEntry != null) {
                ownerEntry.getChanges().removeIf(change -> {
                    if (!balanceFields.contains(change.getField())) return false;
                    balanceChanges.add(change);
                    return true;
                });
                if (ownerEntry.getChanges().isEmpty()) drop.add(ownerEntry);
            }
            for (ActivityEntry entry : nested) {
                if (moneyMoved && ActivityEntry.CHANGED.equals(entry.getAction())
                        && ("charges".equals(entry.getCollection()) || "installments".equals(entry.getCollection()))
                        && entry.getChanges().stream().allMatch(change -> ALLOCATION_FIELDS.contains(change.getField()))) {
                    drop.add(entry);
                    continue;
                }
                entry.setBalanceBefore(before);
                entry.setBalanceAfter(after);
                if (!ActivityEntry.CHANGED.equals(entry.getAction())) entry.setParentChanges(new ArrayList<>(balanceChanges));
            }
            if (ownerEntry != null && !drop.contains(ownerEntry)) {
                ownerEntry.setBalanceBefore(before);
                ownerEntry.setBalanceAfter(after);
                if (ownerEntry.getChanges().stream().allMatch(change -> balanceFields.contains(change.getField()))) {
                    ownerEntry.setCategory("Balances");
                }
            }
        }
        out.removeAll(drop);
    }

    // ------------------------------------------------------------------ wording

    private static String summary(ActivityEntry entry) {
        String type = entry.getEntityType();
        String label = blankTo(entry.getEntityLabel(), type);
        String who = entry.getMemberName();
        String money = entry.getAmount() == null ? "" : " (" + money(entry.getAmount()) + ")";
        boolean nested = entry.getParentId() != null;
        switch (entry.getAction()) {
            case ActivityEntry.ADDED -> {
                if (!nested) return type + " added: " + label + money;
                return switch (type) {
                    case "Payment" -> "Payment recorded for " + who + ": " + money(nz(entry.getAmount())) + " (" + label + ")";
                    case "Charge" -> "Charge added to " + who + ": " + label + money;
                    case "Credit" -> "Credit applied to " + who + ": " + label + money;
                    case "Installment" -> "Installment scheduled for " + who + ": " + label + money;
                    case "Reminder step" -> label + " for " + who;
                    case "Rent charge" -> "Rent charge added to " + who + ": " + label + money;
                    default -> type + " added to " + blankTo(who, "record") + ": " + label + money;
                };
            }
            case ActivityEntry.REMOVED -> {
                if (!nested) return type + " removed: " + label + money;
                return type + " removed from " + blankTo(who, "record") + ": " + label + money;
            }
            case ActivityEntry.ARCHIVED -> {
                return "Member archived by the semester rollover: " + label;
            }
            default -> {
                List<ActivityEntry.FieldChange> changes = entry.getChanges();
                if ("Balances".equals(entry.getCategory()) && entry.getBalanceBefore() != null && entry.getBalanceAfter() != null) {
                    return "Balance changed for " + label + ": " + money(entry.getBalanceBefore()) + " to " + money(entry.getBalanceAfter());
                }
                String fields = changes.stream().map(ActivityEntry.FieldChange::getLabel).limit(4)
                        .reduce((a, b) -> a + ", " + b).orElse("details");
                if (changes.size() > 4) fields += " and " + (changes.size() - 4) + " more";
                if ("Settings".equals(type) || "Payer names".equals(type)) return type + " changed: " + fields;
                if (nested) return type + " updated for " + blankTo(who, "record") + ": " + label + " (" + fields + ")";
                return type + " updated: " + label + " (" + fields + ")";
            }
        }
    }

    static String fieldLabel(String field) {
        String known = FIELD_LABELS.get(field);
        if (known != null) return known;
        String spaced = field.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? field : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String display(String field, Kind kind, JsonNode value) {
        if (value == null || value.isNull()) return "(blank)";
        if (value.isNumber() && kind.money().contains(field)) return money(value.asDouble());
        if (value.isBoolean()) return value.asBoolean() ? "On" : "Off";
        if (value.isTextual()) return value.asText().isBlank() ? "(blank)" : clip(value.asText(), 160);
        if (value.isNumber()) return value.asText();
        if (value.isArray()) {
            if (value.isEmpty()) return "(none)";
            boolean scalars = true;
            for (JsonNode item : value) scalars &= item.isValueNode();
            if (scalars) {
                List<String> parts = new ArrayList<>();
                value.forEach(item -> parts.add(item.asText()));
                return clip(String.join(", ", parts), 160);
            }
            return value.size() + (value.size() == 1 ? " item" : " items");
        }
        return clip(value.toString(), 160);
    }

    private static String raw(JsonNode value) {
        return value == null ? null : value.toString();
    }

    private static boolean same(JsonNode a, JsonNode b) {
        boolean aMissing = a == null || a.isNull();
        boolean bMissing = b == null || b.isNull();
        if (aMissing || bMissing) return aMissing == bMissing;
        if (a.isNumber() && b.isNumber()) return Math.abs(a.asDouble() - b.asDouble()) < 0.000_5;
        return a.equals(b);
    }

    private static Map<String, Integer> index(JsonNode list, String key) {
        Map<String, Integer> index = new LinkedHashMap<>();
        if (list == null || !list.isArray()) return index;
        for (int i = 0; i < list.size(); i++) {
            JsonNode item = list.get(i);
            String id = key == null ? null : item.path(key).asText(null);
            index.putIfAbsent(id == null || id.isBlank() ? "#" + i : id, i);
        }
        return index;
    }

    private static Function<JsonNode, String> text(String field) {
        return node -> text(node, field);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static Function<JsonNode, Double> number(String field) {
        return node -> node == null || !node.has(field) || !node.get(field).isNumber() ? null : node.get(field).asDouble();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b == null ? "" : b;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double nz(Double value) {
        return value == null ? 0 : value;
    }

    static String clip(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 3) + "...";
    }

    static String money(double value) {
        return (value < 0 ? "-" : "") + String.format(Locale.US, "$%,.2f", Math.abs(value));
    }
}
