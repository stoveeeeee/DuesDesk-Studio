package com.fraternity.dues;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One pre-sale transaction: a ticket, a wristband, merchandise or a custom item, optionally credited to the member who
 * made the sale. Tickets and wristbands record whether the guest is male or female so the chapter can see who is buying.
 */
public class PresaleSale {
    public static final String TICKET = "Ticket";
    public static final String WRISTBAND = "Wristband";
    public static final String MERCH = "Merch";
    /** Anything else the chapter sells; {@link #getDescription()} holds its name. */
    public static final String OTHER = "Custom";

    public static final String MALE = "Male";
    public static final String FEMALE = "Female";

    private String id;
    private String date;
    private String buyerName = "";
    /** One of {@link #TICKET}, {@link #WRISTBAND}, {@link #MERCH} or {@link #OTHER}. */
    private String item = TICKET;
    /** What was sold: the item's name for {@link #OTHER}, optional detail for merch such as "T-shirt (L)". */
    private String description = "";
    /** {@link #MALE} or {@link #FEMALE} for tickets and wristbands; blank for everything else (and for older sales). */
    private String guestType = "";
    private int quantity = 1;
    private double unitPrice;
    /** The member credited with the sale on the leaderboard; null for walk-ups and door sales. */
    private String sellerMemberId;

    public PresaleSale() {}

    public PresaleSale(String id, String date, String buyerName, String item, String description, String guestType,
                       int quantity, double unitPrice, String sellerMemberId) {
        this.id = id;
        this.date = date;
        this.buyerName = buyerName == null ? "" : buyerName;
        this.item = item;
        this.description = description == null ? "" : description;
        this.guestType = guestType == null ? "" : guestType;
        this.quantity = quantity;
        this.unitPrice = Money.cents(unitPrice);
        this.sellerMemberId = sellerMemberId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getBuyerName() { return buyerName == null ? "" : buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }
    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }
    public String getDescription() { return description == null ? "" : description; }
    public void setDescription(String description) { this.description = description; }
    public String getGuestType() { return guestType == null ? "" : guestType; }
    public void setGuestType(String guestType) { this.guestType = guestType; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public double getUnitPrice() { return unitPrice; }
    public void setUnitPrice(double unitPrice) { this.unitPrice = Money.cents(unitPrice); }
    public String getSellerMemberId() { return sellerMemberId; }
    public void setSellerMemberId(String sellerMemberId) { this.sellerMemberId = sellerMemberId; }

    @JsonIgnore
    public double getAmount() { return Money.cents(quantity * unitPrice); }
    /** A plain ticket. Anything not recognised (e.g. from an older save) counts as one. */
    @JsonIgnore
    public boolean isTicket() { return !WRISTBAND.equals(item) && !MERCH.equals(item) && !OTHER.equals(item); }
    @JsonIgnore
    public boolean isWristband() { return WRISTBAND.equals(item); }
    /** Tickets and wristbands: the items that get the male/female choice and count as people attending. */
    @JsonIgnore
    public boolean isAdmission() { return isTicket() || isWristband(); }
    /** "Ticket", "Wristband", "Merch · Hoodie" or just the custom item's name. */
    @JsonIgnore
    public String getLabel() {
        if (OTHER.equals(item)) return getDescription();
        return MERCH.equals(item) && !getDescription().isBlank() ? item + " · " + getDescription() : item;
    }
}
