package com.fraternity.dues;

import java.util.List;

/** General categories for recording an organization's money in and out from the calendar. */
public final class TransactionCategories {
    /** The one inflow category tied to a roster member: it records a dues payment and updates their balance. */
    public static final String MEMBER_DUES = "Member dues";

    public static final List<String> INFLOW = List.of(
            MEMBER_DUES,
            "Initiation & new member fees",
            "Fundraising",
            "Donations & gifts",
            "Event & ticket sales",
            "Sponsorships",
            "Merchandise sales",
            "Housing & rent income",
            "Grants & university funding",
            "Reimbursements received",
            "Interest & bank income",
            "Other income");

    public static final List<String> OUTFLOW = List.of(
            "National / headquarters fees",
            "Insurance",
            "Events & socials",
            "Food & beverages",
            "Venue & rentals",
            "Philanthropy & charitable giving",
            "Recruitment",
            "Supplies & equipment",
            "Marketing & printing",
            "Travel & conferences",
            "House maintenance & repairs",
            "Utilities",
            "Software & subscriptions",
            "Bank & payment fees",
            "Awards & gifts",
            "Refunds issued",
            "Other expense");

    private TransactionCategories() {}
}
