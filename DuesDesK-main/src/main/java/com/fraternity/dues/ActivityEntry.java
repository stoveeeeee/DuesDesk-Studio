package com.fraternity.dues;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One recorded change in the activity log: something added, removed or changed by a single save. Every entry made by
 * the same save shares a {@code batchId}, so one action (a batch charge to 40 members, a CSV import) reads as one item
 * and can be undone together.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActivityEntry {
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String CHANGED = "CHANGED";
    public static final String ARCHIVED = "ARCHIVED";

    /** One field that changed: display text for people, raw JSON so an undo can put the old value back. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldChange {
        private String field;
        private String label;
        private String before;
        private String after;
        private String rawBefore;
        private String rawAfter;

        public FieldChange() {}

        public FieldChange(String field, String label, String before, String after, String rawBefore, String rawAfter) {
            this.field = field;
            this.label = label;
            this.before = before;
            this.after = after;
            this.rawBefore = rawBefore;
            this.rawAfter = rawAfter;
        }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getBefore() { return before; }
        public void setBefore(String before) { this.before = before; }
        public String getAfter() { return after; }
        public void setAfter(String after) { this.after = after; }
        public String getRawBefore() { return rawBefore; }
        public void setRawBefore(String rawBefore) { this.rawBefore = rawBefore; }
        public String getRawAfter() { return rawAfter; }
        public void setRawAfter(String rawAfter) { this.rawAfter = rawAfter; }
    }

    private String id;
    private String batchId;
    /** ISO local date-time, e.g. 2026-10-25T14:03:11. */
    private String at;
    /** What caused the change, e.g. "Add charge", "Venmo CSV import", "Automatic reminder sequence". */
    private String source;
    private String category;
    private String action;
    private String entityType;
    private String entityLabel;
    private String summary;
    private String memberId;
    private String memberName;
    private Double amount;
    /** The member's (or resident's) balance just before and just after this save. */
    private Double balanceBefore;
    private Double balanceAfter;
    private List<FieldChange> changes = new ArrayList<>();
    /** Where the item lives, so an undo can put it back: section ("members"), owner id, list name, key, position. */
    private String section;
    private String parentId;
    private String collection;
    private String itemKey;
    private int itemIndex = -1;
    /** The removed item as JSON (removals only). */
    private String itemJson;
    /** Balance fields of the owner that moved with this item (removals only), used to reverse them on undo. */
    private List<FieldChange> parentChanges = new ArrayList<>();
    private String undoneAt;
    private String undoneBatchId;

    public ActivityEntry() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getAt() { return at; }
    public void setAt(String at) { this.at = at; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityLabel() { return entityLabel; }
    public void setEntityLabel(String entityLabel) { this.entityLabel = entityLabel; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getMemberName() { return memberName; }
    public void setMemberName(String memberName) { this.memberName = memberName; }
    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
    public Double getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(Double balanceBefore) { this.balanceBefore = balanceBefore; }
    public Double getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Double balanceAfter) { this.balanceAfter = balanceAfter; }
    public List<FieldChange> getChanges() {
        if (changes == null) changes = new ArrayList<>();
        return changes;
    }
    public void setChanges(List<FieldChange> changes) { this.changes = changes; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public String getItemKey() { return itemKey; }
    public void setItemKey(String itemKey) { this.itemKey = itemKey; }
    public int getItemIndex() { return itemIndex; }
    public void setItemIndex(int itemIndex) { this.itemIndex = itemIndex; }
    public String getItemJson() { return itemJson; }
    public void setItemJson(String itemJson) { this.itemJson = itemJson; }
    public List<FieldChange> getParentChanges() {
        if (parentChanges == null) parentChanges = new ArrayList<>();
        return parentChanges;
    }
    public void setParentChanges(List<FieldChange> parentChanges) { this.parentChanges = parentChanges; }
    public String getUndoneAt() { return undoneAt; }
    public void setUndoneAt(String undoneAt) { this.undoneAt = undoneAt; }
    public String getUndoneBatchId() { return undoneBatchId; }
    public void setUndoneBatchId(String undoneBatchId) { this.undoneBatchId = undoneBatchId; }

    @JsonIgnore
    public boolean isRemoval() { return REMOVED.equals(action); }

    @JsonIgnore
    public boolean isUndone() { return undoneAt != null && !undoneAt.isBlank(); }

    @JsonIgnore
    public java.time.LocalDateTime getTime() {
        try {
            return java.time.LocalDateTime.parse(at);
        } catch (RuntimeException e) {
            return java.time.LocalDateTime.MIN;
        }
    }
}
