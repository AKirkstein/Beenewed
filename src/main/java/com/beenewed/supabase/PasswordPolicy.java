package com.beenewed.supabase;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Strong-password rules applied when creating a Beenewed account.
 * <p>
 * Works on {@code char[]} so callers can wipe the password after checking it.
 * Mirror these rules in the Supabase dashboard (Authentication &rarr; Providers &rarr;
 * Email &rarr; Password requirements) so they are also enforced server-side.
 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 8;

    public enum Rule {
        LENGTH("At least " + MIN_LENGTH + " characters", "at least " + MIN_LENGTH + " characters"),
        UPPER("One uppercase letter (A\u2013Z)", "one uppercase letter"),
        LOWER("One lowercase letter (a\u2013z)", "one lowercase letter"),
        DIGIT("One number (0\u20139)", "one number"),
        SPECIAL("One special character (e.g. ! @ # $ %)", "one special character");

        /** Checklist label shown in the UI. */
        public final String label;
        /** Short phrase used in error sentences. */
        public final String phrase;

        Rule(String label, String phrase) {
            this.label = label;
            this.phrase = phrase;
        }
    }

    private PasswordPolicy() {
    }

    /** Returns the rules that the password does NOT satisfy (empty set = valid). */
    public static EnumSet<Rule> unmet(char[] password) {
        EnumSet<Rule> unmet = EnumSet.allOf(Rule.class);
        if (password == null) {
            return unmet;
        }
        int codePoints = 0;
        for (int i = 0; i < password.length; ) {
            int cp = Character.codePointAt(password, i);
            i += Character.charCount(cp);
            codePoints++;
            if (Character.isUpperCase(cp)) {
                unmet.remove(Rule.UPPER);
            } else if (Character.isLowerCase(cp)) {
                unmet.remove(Rule.LOWER);
            } else if (Character.isDigit(cp)) {
                unmet.remove(Rule.DIGIT);
            } else if (!Character.isLetter(cp) && !Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
                unmet.remove(Rule.SPECIAL);
            }
        }
        if (codePoints >= MIN_LENGTH) {
            unmet.remove(Rule.LENGTH);
        }
        return unmet;
    }

    public static boolean isValid(char[] password) {
        return unmet(password).isEmpty();
    }

    /** Builds a user-facing sentence such as "Password must contain one uppercase letter and one number." */
    public static String describe(Set<Rule> unmet) {
        if (unmet == null || unmet.isEmpty()) {
            return "Password meets all requirements.";
        }
        List<String> parts = new ArrayList<>();
        for (Rule rule : Rule.values()) {
            if (unmet.contains(rule)) {
                parts.add(rule.phrase);
            }
        }
        String joined;
        if (parts.size() == 1) {
            joined = parts.get(0);
        } else {
            joined = String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        }
        return "Password must contain " + joined + ".";
    }
}
