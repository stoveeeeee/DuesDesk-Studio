package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class VenmoEmailParserTest {
    private final VenmoEmailParser parser = new VenmoEmailParser(ZoneOffset.UTC);

    @Test
    void readsAVerifiedPaidYouEmail() throws Exception {
        VenmoEmailParser.Result result = parser.parse(fixture("paid-you.eml"));

        assertEquals(VenmoEmailParser.Outcome.PAYMENT, result.outcome());
        VenmoEmailParser.Payment payment = result.payment();
        assertEquals("<paid-you-0001@email.amazonses.com>", payment.messageId());
        assertEquals("Alex Rivera", payment.payerName());
        assertEquals(50.0, payment.amount());
        assertEquals("Fall dues DUES-ARIVERA & thanks!", payment.note());
        assertEquals("2026-10-25T23:08:48", payment.date());
        assertEquals("4700000000000000001", payment.venmoPaymentId());
        assertEquals("8AB12345CD678901E", payment.transactionId());
        assertTrue(payment.verified());
    }

    @Test
    void rejectsTheRealOutgoingYouPaidEmail() throws Exception {
        VenmoEmailParser.Result result = parser.parse(fixture("you-paid.eml"));

        assertEquals(VenmoEmailParser.Outcome.NOT_INCOMING_PAYMENT, result.outcome());
        assertNotNull(result.messageId());
    }

    @Test
    void aFailingVenmoSignatureIsUnverifiedEvenWithAPlantedPassHeaderBelowIt() throws Exception {
        VenmoEmailParser.Result result = parser.parse(fixture("paid-you-spoofed.eml"));

        assertEquals(VenmoEmailParser.Outcome.PAYMENT, result.outcome());
        assertFalse(result.payment().verified());
        assertTrue(result.payment().verification().contains("did not verify"));
    }

    @Test
    void handForwardedEmailIsRecognizedFromTheOriginalSenderButNotVerified() throws Exception {
        VenmoEmailParser.Result result = parser.parse(fixture("paid-you-hand-forwarded.eml"));

        assertEquals(VenmoEmailParser.Outcome.PAYMENT, result.outcome());
        VenmoEmailParser.Payment payment = result.payment();
        assertEquals("Alex Rivera", payment.payerName());
        assertEquals(50.0, payment.amount());
        assertFalse(payment.verified());
        // Forwarded the next day: the payment keeps Venmo's own date.
        assertEquals("2026-10-25", payment.date());
    }

    @Test
    void paidYouSubjectFromSomeoneElseIsNotVenmo() {
        VenmoEmailParser.Result result = parser.parse(email("someone@example.com",
                "mx.google.com; dkim=pass header.i=@example.com", "John Smith paid you $50.00", "<p>hi</p>"));

        assertEquals(VenmoEmailParser.Outcome.NOT_VENMO, result.outcome());
    }

    @Test
    void lookAlikeDomainsDoNotCountAsVenmo() {
        assertEquals(VenmoEmailParser.Outcome.NOT_VENMO, parser.parse(email("venmo@notvenmo.com",
                "mx.google.com; dkim=pass header.i=@notvenmo.com", "John Smith paid you $50.00", "<p>x</p>")).outcome());
        VenmoEmailParser.Result evil = parser.parse(email("venmo@venmo.com",
                "mx.google.com; dkim=pass header.i=@evilvenmo.com", "John Smith paid you $50.00", "<p>x</p>"));
        assertEquals(VenmoEmailParser.Outcome.PAYMENT, evil.outcome());
        assertFalse(evil.payment().verified());
        VenmoEmailParser.Result suffix = parser.parse(email("venmo@venmo.com",
                "mx.google.com; dkim=pass header.d=venmo.com.evil.example", "John Smith paid you $50.00", "<p>x</p>"));
        assertFalse(suffix.payment().verified());
    }

    @Test
    void requestPaymentsAndThousandsSeparatorsAreRead() {
        VenmoEmailParser.Result result = parser.parse(email("venmo@venmo.com",
                "mx.google.com; dkim=pass header.i=@venmo.com header.s=x", "Fwd: Jane Doe paid your $1,250.00 request", "<p>x</p>"));

        assertEquals(VenmoEmailParser.Outcome.PAYMENT, result.outcome());
        assertEquals("Jane Doe", result.payment().payerName());
        assertEquals(1250.0, result.payment().amount());
        assertTrue(result.payment().verified());
        assertEquals("", result.payment().note());
    }

    @Test
    void otherVenmoEmailsAreNotPayments() {
        assertEquals(VenmoEmailParser.Outcome.NOT_INCOMING_PAYMENT, parser.parse(email("venmo@venmo.com",
                "mx.google.com; dkim=pass header.i=@venmo.com", "Jane Doe requests $20.00", "<p>x</p>")).outcome());
        assertFalse(VenmoEmailParser.Outcome.PAYMENT == parser.parse("not an email".getBytes(StandardCharsets.UTF_8)).outcome());
        assertFalse(VenmoEmailParser.Outcome.PAYMENT == parser.parse(new byte[0]).outcome());
    }

    @Test
    void htmlTextDecodesEntitiesAndEmoji() {
        assertEquals("Rent & dues 👍 \"fall\"", VenmoEmailParser.htmlToText("Rent &amp; dues &#128077; <b>&quot;fall&quot;</b>"));
    }

    static byte[] fixture(String name) throws Exception {
        try (InputStream in = VenmoEmailParserTest.class.getResourceAsStream("/venmo/" + name)) {
            if (in == null) throw new IllegalStateException("Missing fixture " + name);
            return in.readAllBytes();
        }
    }

    static byte[] email(String from, String authenticationResults, String subject, String html) {
        String text = "Authentication-Results: " + authenticationResults + "\r\n"
                + "From: Venmo <" + from + ">\r\n"
                + "To: chapter.dues@example.com\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: Sun, 25 Oct 2026 23:08:48 +0000\r\n"
                + "Message-ID: <" + Math.abs((from + subject + authenticationResults).hashCode()) + "@test.example>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
                + html + "\r\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
