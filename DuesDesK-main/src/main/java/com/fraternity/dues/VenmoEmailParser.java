package com.fraternity.dues;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one Venmo "paid you" email (as raw RFC 822 bytes) into a payment.
 *
 * <p>Only incoming payments count: the subject must say "&lt;name&gt; paid you $X" (or "paid your $X request"), and
 * "You paid ..." emails are rejected. The email must come from Venmo: either the From line is a venmo.com address
 * (Gmail's filter forwarding keeps the original sender), or the body of a hand-forwarded email shows the original
 * "From: Venmo &lt;venmo@venmo.com&gt;".
 *
 * <p>Anyone can type a Venmo From line, so a payment is only {@link Payment#verified() verified} when the receiving
 * mail server's own (top-most) Authentication-Results header shows a passing DKIM signature from venmo.com.
 * Unverified payments are never applied automatically.
 */
public final class VenmoEmailParser {

    /** Why an email was or wasn't turned into a payment. */
    public enum Outcome { PAYMENT, NOT_VENMO, NOT_INCOMING_PAYMENT, UNREADABLE }

    /**
     * @param venmoPaymentId the number in the email's "See transaction" link (venmo.com/story/&lt;id&gt;), which is
     *                       the ID Venmo statement CSVs use; null if the email has no such link
     * @param date ISO date-time (local time) the payment was made, or ISO date when only the day is known
     */
    public record Payment(String messageId, String payerName, double amount, String note, String date,
                          Instant receivedAt, String venmoPaymentId, String transactionId,
                          boolean verified, String verification) {}

    /** {@code payment} is set only when the outcome is PAYMENT; {@code messageId} is always set. */
    public record Result(Outcome outcome, String messageId, Payment payment, String reason, Instant receivedAt) {}

    private static final Pattern FORWARD_PREFIX = Pattern.compile("^\\s*((fwd?|fw)\\s*:\\s*)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAID_YOU = Pattern.compile(
            "^(.+?)\\s+paid\\s+(?:you|your)\\s+\\$\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?:\\s+request)?\\b.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YOU_PAID = Pattern.compile("^you\\s+paid\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VENMO_ADDRESS = Pattern.compile("@(?:[a-z0-9-]+\\.)*venmo\\.com$", Pattern.CASE_INSENSITIVE);
    /** "From: Venmo <venmo@venmo.com>" inside a hand-forwarded email's body (plain text or HTML). */
    private static final Pattern FORWARDED_FROM_VENMO = Pattern.compile(
            "From:\\s*(?:\"?Venmo\"?\\s*)?(?:<|&lt;)?\\s*(?:mailto:)?[a-z0-9._%+-]*@(?:[a-z0-9-]+\\.)*venmo\\.com\\b",
            Pattern.CASE_INSENSITIVE);
    /** A passing DKIM result signed by venmo.com (header.i=@venmo.com or header.d=venmo.com, subdomains allowed). */
    private static final Pattern DKIM_PASS_VENMO = Pattern.compile(
            "dkim=pass[^;]*?header\\.(?:i=[^;\\s]*@|d=)(?:[a-z0-9-]+\\.)*venmo\\.com(?![a-z0-9.-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTE = Pattern.compile(
            "class=\"[^\"]*\\btransaction-note\\b[^\"]*\"[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STORY_ID = Pattern.compile("venmo\\.com/story/([0-9]{6,30})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DETAIL = Pattern.compile(
            "<h3[^>]*>\\s*([^<]+?)\\s*</h3>\\s*<(?:p|div)[^>]*>(.*?)</(?:p|div)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final DateTimeFormatter DETAIL_DATE = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH);

    private final ZoneId zone;

    public VenmoEmailParser() {
        this(ZoneId.systemDefault());
    }

    VenmoEmailParser(ZoneId zone) {
        this.zone = zone;
    }

    public Result parse(byte[] raw) {
        String fallbackId = "sha256:" + sha256(raw == null ? new byte[0] : raw);
        MimeMessage message;
        String messageId;
        Instant receivedAt = null;
        try {
            message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
            String header = message.getMessageID();
            messageId = header == null || header.isBlank() ? fallbackId : header.trim();
            if (message.getSentDate() != null) receivedAt = message.getSentDate().toInstant();
        } catch (Exception e) {
            return new Result(Outcome.UNREADABLE, fallbackId, null, "The email could not be read.", null);
        }
        try {
            return parse(message, messageId, receivedAt);
        } catch (Exception e) {
            return new Result(Outcome.UNREADABLE, messageId, null, "The email could not be read.", receivedAt);
        }
    }

    private Result parse(MimeMessage message, String messageId, Instant receivedAt) throws Exception {
        String subject = message.getSubject() == null ? "" : message.getSubject().replaceAll("\\s+", " ").trim();
        boolean forwardedSubject = FORWARD_PREFIX.matcher(subject).find();
        String plainSubject = FORWARD_PREFIX.matcher(subject).replaceFirst("").trim();
        if (YOU_PAID.matcher(plainSubject).find()) {
            return new Result(Outcome.NOT_INCOMING_PAYMENT, messageId, null, "Payment sent by the chapter, not received.", receivedAt);
        }
        Matcher paid = PAID_YOU.matcher(plainSubject);
        if (!paid.matches()) {
            return new Result(Outcome.NOT_INCOMING_PAYMENT, messageId, null, "Subject is not a \"paid you\" payment.", receivedAt);
        }

        String html = firstText(message, "text/html");
        String text = html == null ? firstText(message, "text/plain") : html;
        if (text == null) text = "";
        boolean fromVenmo = fromVenmo(message);
        // Gmail's forward header wraps the sender in tags ("From: <strong>Venmo</strong> &lt;venmo@venmo.com&gt;").
        boolean forwardedFromVenmo = !fromVenmo && (FORWARDED_FROM_VENMO.matcher(text).find()
                || FORWARDED_FROM_VENMO.matcher(htmlToText(text)).find());
        if (!fromVenmo && !forwardedFromVenmo) {
            return new Result(Outcome.NOT_VENMO, messageId, null, "Not sent by Venmo.", receivedAt);
        }

        String verification;
        boolean verified;
        String[] authentication = message.getHeader("Authentication-Results");
        if (!fromVenmo) {
            verified = false;
            verification = "Forwarded by hand, so the original Venmo sender can't be verified.";
        } else if (authentication == null || authentication.length == 0) {
            verified = false;
            verification = "The mail server did not report a Venmo signature check.";
        } else if (DKIM_PASS_VENMO.matcher(authentication[0].replaceAll("\\s+", " ")).find()) {
            verified = true;
            verification = forwardedSubject ? "Venmo signature verified (forwarded)." : "Venmo signature verified.";
        } else {
            verified = false;
            verification = "Venmo's email signature did not verify.";
        }

        BigDecimal amountValue;
        try {
            amountValue = new BigDecimal(paid.group(2).replace(",", ""));
        } catch (NumberFormatException e) {
            return new Result(Outcome.UNREADABLE, messageId, null, "The payment amount could not be read.", receivedAt);
        }
        double amount = Money.cents(amountValue.doubleValue());
        if (amount <= 0 || amount > 100_000) {
            return new Result(Outcome.UNREADABLE, messageId, null, "The payment amount is not valid.", receivedAt);
        }
        String payer = paid.group(1).trim();
        if (payer.isEmpty() || payer.length() > 120) {
            return new Result(Outcome.UNREADABLE, messageId, null, "The payer's name could not be read.", receivedAt);
        }

        String note = "";
        String transactionId = null;
        LocalDate detailDate = null;
        String status = null;
        if (html != null) {
            Matcher noteMatch = NOTE.matcher(html);
            if (noteMatch.find()) note = htmlToText(noteMatch.group(1));
            Matcher details = DETAIL.matcher(html);
            while (details.find()) {
                String label = htmlToText(details.group(1)).toLowerCase(Locale.ROOT);
                String value = htmlToText(details.group(2));
                switch (label) {
                    case "transaction id" -> transactionId = value.matches("[A-Za-z0-9-]{4,64}") ? value : null;
                    case "date" -> detailDate = parseDetailDate(value);
                    case "status" -> status = value;
                    default -> { }
                }
            }
        }
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("completed")) {
            return new Result(Outcome.NOT_INCOMING_PAYMENT, messageId, null, "The payment is " + status + ", not completed.", receivedAt);
        }
        if (note.length() > 500) note = note.substring(0, 500);
        Matcher story = STORY_ID.matcher(text);
        String venmoPaymentId = story.find() ? story.group(1) : null;

        String date;
        if (receivedAt != null) {
            LocalDateTime local = LocalDateTime.ofInstant(receivedAt, zone).withNano(0);
            // A hand-forwarded email is dated when it was forwarded; Venmo's own date line says when it was paid.
            date = detailDate != null && !detailDate.equals(local.toLocalDate()) ? detailDate.toString() : local.toString();
        } else if (detailDate != null) {
            date = detailDate.toString();
        } else {
            return new Result(Outcome.UNREADABLE, messageId, null, "The payment date could not be read.", null);
        }

        Payment payment = new Payment(messageId, payer, amount, note, date, receivedAt, venmoPaymentId, transactionId,
                verified, verification);
        return new Result(Outcome.PAYMENT, messageId, payment, verification, receivedAt);
    }

    private static boolean fromVenmo(MimeMessage message) throws Exception {
        Address[] from = message.getFrom();
        if (from == null || from.length != 1 || !(from[0] instanceof InternetAddress address)) return false;
        return address.getAddress() != null && VENMO_ADDRESS.matcher(address.getAddress().trim()).find();
    }

    /** The first body part of {@code type}, searching nested multiparts and attached (forwarded) messages. */
    private static String firstText(Part part, String type) throws Exception {
        if (part.isMimeType(type)) {
            Object content = part.getContent();
            return content instanceof String value ? value : null;
        }
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String found = firstText(child, type);
                if (found != null && !found.isBlank()) return found;
            }
        }
        if (part.isMimeType("message/rfc822") && part.getContent() instanceof Part attached) {
            return firstText(attached, type);
        }
        return null;
    }

    private static LocalDate parseDetailDate(String value) {
        try {
            return LocalDate.parse(value.trim(), DETAIL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Strips tags and decodes the common HTML entities, collapsing whitespace. */
    static String htmlToText(String html) {
        if (html == null) return "";
        String text = html.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]*>", " ");
        Matcher entity = Pattern.compile("&(#[0-9]+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp|#39);").matcher(text);
        StringBuilder out = new StringBuilder();
        while (entity.find()) {
            String name = entity.group(1);
            String replacement = switch (name) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                case "nbsp" -> " ";
                default -> {
                    try {
                        int codePoint = name.startsWith("#x") ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                        yield Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : "";
                    } catch (NumberFormatException e) {
                        yield "";
                    }
                }
            };
            entity.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        entity.appendTail(out);
        return out.toString().replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
