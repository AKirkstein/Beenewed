package com.beenewed.supabase;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static com.beenewed.supabase.PasswordPolicy.Rule.*;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {

    private static EnumSet<PasswordPolicy.Rule> unmet(String pw) {
        return PasswordPolicy.unmet(pw == null ? null : pw.toCharArray());
    }

    @Test
    void simpleNumericPasswordFailsEverythingButDigit() {
        assertEquals(EnumSet.of(LENGTH, UPPER, LOWER, SPECIAL), unmet("123456"));
    }

    @Test
    void lowercaseWordFails() {
        assertEquals(EnumSet.of(UPPER, DIGIT, SPECIAL), unmet("password"));
    }

    @Test
    void strongPasswordPasses() {
        assertTrue(unmet("Passw0rd!").isEmpty());
        assertTrue(PasswordPolicy.isValid("Xy7#abcd".toCharArray()));
    }

    @Test
    void sevenCharactersIsTooShort() {
        assertEquals(EnumSet.of(LENGTH), unmet("Aa1!aaa"));
        assertTrue(unmet("Aa1!aaaa").isEmpty());
    }

    @Test
    void whitespaceIsNotASpecialCharacter() {
        assertEquals(EnumSet.of(SPECIAL), unmet("Abcdef 1"));
    }

    @Test
    void unicodeLettersAreClassified() {
        assertTrue(unmet("\u00C4bcdefg1!").isEmpty()); // Ä counts as uppercase
    }

    @Test
    void nullAndEmptyFailAllRules() {
        assertEquals(EnumSet.allOf(PasswordPolicy.Rule.class), unmet(null));
        assertEquals(EnumSet.allOf(PasswordPolicy.Rule.class), unmet(""));
    }

    @Test
    void describeReadsNaturally() {
        assertEquals("Password must contain one number.", PasswordPolicy.describe(EnumSet.of(DIGIT)));
        assertEquals("Password must contain one uppercase letter and one special character.",
                PasswordPolicy.describe(EnumSet.of(SPECIAL, UPPER)));
        assertEquals("Password must contain at least 8 characters, one uppercase letter, one lowercase letter and one special character.",
                PasswordPolicy.describe(unmet("123456")));
    }
}
