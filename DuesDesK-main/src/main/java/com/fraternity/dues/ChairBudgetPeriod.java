package com.fraternity.dues;

import java.util.ArrayList;
import java.util.List;

/** A chair budget's closed-out term, kept when a semester rollover starts the budget fresh. */
public class ChairBudgetPeriod {
    private String budgetId;
    private String chairName;
    private String termName = "";
    private String endedOn;
    private double budgetCap;
    private double raiseGoal;
    private double spent;
    private double raisedTotal;
    private List<ReimbursementRecord> reimbursements = new ArrayList<>();
    private List<RaisedRecord> raised = new ArrayList<>();

    public ChairBudgetPeriod() {}

    public ChairBudgetPeriod(ChairBudget budget, String termName, String endedOn) {
        this.budgetId = budget.getId();
        this.chairName = budget.getChairName();
        this.termName = termName == null ? "" : termName;
        this.endedOn = endedOn;
        this.budgetCap = budget.getBudgetCap();
        this.raiseGoal = budget.getRaiseGoal();
        this.spent = budget.getSpent();
        this.raisedTotal = budget.getRaisedTotal();
        this.reimbursements = new ArrayList<>(budget.getReimbursements());
        this.raised = new ArrayList<>(budget.getRaised());
    }

    public String getBudgetId() { return budgetId; }
    public void setBudgetId(String budgetId) { this.budgetId = budgetId; }
    public String getChairName() { return chairName; }
    public void setChairName(String chairName) { this.chairName = chairName; }
    public String getTermName() { return termName == null ? "" : termName; }
    public void setTermName(String termName) { this.termName = termName; }
    public String getEndedOn() { return endedOn; }
    public void setEndedOn(String endedOn) { this.endedOn = endedOn; }
    public double getBudgetCap() { return budgetCap; }
    public void setBudgetCap(double budgetCap) { this.budgetCap = budgetCap; }
    public double getRaiseGoal() { return raiseGoal; }
    public void setRaiseGoal(double raiseGoal) { this.raiseGoal = raiseGoal; }
    public double getSpent() { return spent; }
    public void setSpent(double spent) { this.spent = spent; }
    public double getRaisedTotal() { return raisedTotal; }
    public void setRaisedTotal(double raisedTotal) { this.raisedTotal = raisedTotal; }
    public List<ReimbursementRecord> getReimbursements() {
        if (reimbursements == null) reimbursements = new ArrayList<>();
        return reimbursements;
    }
    public void setReimbursements(List<ReimbursementRecord> reimbursements) { this.reimbursements = reimbursements; }
    public List<RaisedRecord> getRaised() {
        if (raised == null) raised = new ArrayList<>();
        return raised;
    }
    public void setRaised(List<RaisedRecord> raised) { this.raised = raised; }
}
