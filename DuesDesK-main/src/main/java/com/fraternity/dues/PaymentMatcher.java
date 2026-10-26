package com.fraternity.dues;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which member an incoming Venmo payment belongs to, using only exact rules:
 * <ol>
 *   <li>the payer's Venmo name equals a member's saved Venmo display name, or a payer name the treasurer already
 *       confirmed for that member (ignoring case, spaces, punctuation and accents);</li>
 *   <li>otherwise, the payment note contains the member's dues code, e.g. {@code DUES-JSMITH};</li>
 *   <li>otherwise the payment is unmatched and waits for the treasurer.</li>
 * </ol>
 * There is no fuzzy guessing: a rule that points at two different members counts as no match.
 */
public final class PaymentMatcher {

    public enum Rule { VENMO_NAME, DUES_CODE, NONE }

    /** {@code member} is null when nothing matched; {@code reason} explains the outcome for the Unmatched screen. */
    public record Match(Member member, Rule rule, String reason) {
        public boolean matched() { return member != null; }
    }

    private PaymentMatcher() {}

    /**
     * @param confirmedAliases normalized payer name to member ID, remembered from confirmed matches
     */
    public static Match match(String payerName, String note, Collection<Member> members, Map<String, String> confirmedAliases) {
        String payer = normalize(payerName);
        if (!payer.isEmpty()) {
            Set<Member> byName = new LinkedHashSet<>();
            for (Member member : members) {
                if (payer.equals(normalize(member.getVenmoDisplayName()))
                        || member.getRecognizedPayerAliases().stream().anyMatch(alias -> payer.equals(normalize(alias)))) {
                    byName.add(member);
                }
            }
            String aliasMemberId = confirmedAliases == null ? null : confirmedAliases.get(payer);
            if (aliasMemberId != null) {
                members.stream().filter(member -> aliasMemberId.equals(member.getId())).findFirst().ifPresent(byName::add);
            }
            if (byName.size() == 1) {
                return new Match(byName.iterator().next(), Rule.VENMO_NAME, "Venmo name matches a saved name");
            }
            if (byName.size() > 1) {
                return new Match(null, Rule.NONE, "Venmo name \"" + payerName.trim() + "\" is saved on more than one member");
            }
        }

        Set<Member> byCode = new LinkedHashSet<>();
        for (Member member : members) {
            if (noteContainsCode(note, member.getDuesCode())) byCode.add(member);
        }
        if (byCode.size() == 1) return new Match(byCode.iterator().next(), Rule.DUES_CODE, "Note contains the member's dues code");
        if (byCode.size() > 1) return new Match(null, Rule.NONE, "Note contains more than one member's dues code");
        return new Match(null, Rule.NONE, "No saved Venmo name or dues code matched");
    }

    /**
     * True when {@code note} contains {@code code} as a whole word. "DUES-JSMITH", "dues jsmith" and "DUES_JSMITH"
     * all count; "DUES-JSMITH2" does not count for DUES-JSMITH.
     */
    static boolean noteContainsCode(String note, String code) {
        if (note == null || note.isBlank() || code == null || code.isBlank()) return false;
        String[] parts = code.trim().toUpperCase(Locale.ROOT).split("[^A-Z0-9]+");
        StringBuilder regex = new StringBuilder("(?<![A-Z0-9])");
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!first) regex.append("[\\s_\\-]?");
            regex.append(Pattern.quote(part));
            first = false;
        }
        if (first) return false;
        regex.append("(?![A-Z0-9])");
        return Pattern.compile(regex.toString()).matcher(note.toUpperCase(Locale.ROOT)).find();
    }

    /** A new dues code for {@code member}, such as DUES-JSMITH, made unique against {@code taken} (upper case). */
    public static String generateDuesCode(Member member, Set<String> taken) {
        String[] words = normalizeWords(member.getName());
        String base;
        if (words.length == 0) base = "MEMBER";
        else if (words.length == 1) base = words[0];
        else base = words[0].charAt(0) + words[words.length - 1];
        if (base.length() > 16) base = base.substring(0, 16);
        String code = "DUES-" + base;
        for (int n = 2; taken.contains(code); n++) code = "DUES-" + base + n;
        return code;
    }

    /** "José O'Neil" -> "joseoneil": the comparison key for names. */
    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static String[] normalizeWords(String name) {
        if (name == null) return new String[0];
        String plain = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\\s]", "").trim();
        return plain.isEmpty() ? new String[0] : List.of(plain.split("\\s+")).toArray(String[]::new);
    }
}
