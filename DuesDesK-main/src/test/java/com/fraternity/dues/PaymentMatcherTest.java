package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentMatcherTest {

    @Test
    void savedVenmoNameWinsOverADuesCodeInTheNote() {
        Member john = member("1", "John Smith", "Johnny S", "DUES-JSMITH");
        Member alex = member("2", "Alex Rivera", "", "DUES-ARIVERA");

        PaymentMatcher.Match match = PaymentMatcher.match("johnny s.", "DUES-ARIVERA", List.of(john, alex), Map.of());

        assertEquals(john, match.member());
        assertEquals(PaymentMatcher.Rule.VENMO_NAME, match.rule());
    }

    @Test
    void confirmedPayerNamesCountAsSavedNames() {
        Member john = member("1", "John Smith", "", "DUES-JSMITH");
        john.getRecognizedPayerAliases().add("Mary Smith");
        Member alex = member("2", "Alex Rivera", "", "DUES-ARIVERA");

        assertEquals(john, PaymentMatcher.match("Mary Smith", "", List.of(john, alex), Map.of()).member());
        assertEquals(alex, PaymentMatcher.match("Pat Rivera", "", List.of(john, alex), Map.of("patrivera", "2")).member());
    }

    @Test
    void duesCodeInTheNoteMatchesWhenNoNameDoes() {
        Member john = member("1", "John Smith", "", "DUES-JSMITH");

        PaymentMatcher.Match match = PaymentMatcher.match("Parent Account", "fall dues dues jsmith thx", List.of(john), Map.of());

        assertEquals(john, match.member());
        assertEquals(PaymentMatcher.Rule.DUES_CODE, match.rule());
    }

    @Test
    void nothingIsGuessed() {
        Member john = member("1", "John Smith", "", "DUES-JSMITH");
        // Same name as the roster, but no saved Venmo name: not a match.
        assertFalse(PaymentMatcher.match("John Smith", "dues", List.of(john), Map.of()).matched());
        // A longer code is a different code.
        assertFalse(PaymentMatcher.match("X", "DUES-JSMITH2", List.of(john), Map.of()).matched());
        assertFalse(PaymentMatcher.match("X", "DUESJSMITHS", List.of(john), Map.of()).matched());
    }

    @Test
    void anAmbiguousMatchIsUnmatched() {
        Member a = member("1", "John Smith", "J Smith", "DUES-JSMITH");
        Member b = member("2", "Jake Smith", "J Smith", "DUES-JSMITH2");
        PaymentMatcher.Match byName = PaymentMatcher.match("J Smith", "", List.of(a, b), Map.of());
        assertNull(byName.member());
        assertTrue(byName.reason().contains("more than one"));

        Member c = member("3", "Cara Lee", "", "DUES-CLEE");
        assertFalse(PaymentMatcher.match("X", "DUES-JSMITH and DUES-CLEE", List.of(a, c), Map.of()).matched());
    }

    @Test
    void generatedCodesAreUnique() {
        Set<String> taken = new HashSet<>();
        String first = PaymentMatcher.generateDuesCode(member("1", "John Smith", "", ""), taken);
        taken.add(first);
        String second = PaymentMatcher.generateDuesCode(member("2", "Jake  Smith", "", ""), taken);

        assertEquals("DUES-JSMITH", first);
        assertEquals("DUES-JSMITH2", second);
        assertEquals("DUES-JONEIL", PaymentMatcher.generateDuesCode(member("3", "José O'Neil", "", ""), Set.of()));
        assertEquals("DUES-MEMBER", PaymentMatcher.generateDuesCode(member("4", "", "", ""), Set.of()));
    }

    private static Member member(String id, String name, String venmoName, String code) {
        Member member = new Member(id, name, "555-0100", 100, 0, 0, "Current", "Never", false);
        member.setVenmoDisplayName(venmoName);
        member.setDuesCode(code);
        return member;
    }
}
