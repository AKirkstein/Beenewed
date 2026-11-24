package beenewed.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InputFiltersTest {

    @Test
    void decimalDocumentFilterAllowsNumericWithUpToTwoDecimalsAndBlocksInvalidChars() throws BadLocationException {
        Theme.apply(false);
        UI.PrefixField pf = new UI.PrefixField("\u20B1", "0.00");
        Document doc = pf.field.getDocument();

        // Typing valid price "899.00"
        doc.insertString(0, "899", null);
        assertEquals("899", pf.getText());

        // Letters and symbols are rejected
        doc.insertString(doc.getLength(), "abc", null);
        doc.insertString(doc.getLength(), "d", null);
        doc.insertString(doc.getLength(), "-", null);
        doc.insertString(doc.getLength(), ",", null);
        doc.insertString(doc.getLength(), "$", null);
        assertEquals("899", pf.getText());

        // Decimal point and 2 decimal digits allowed
        doc.insertString(doc.getLength(), ".", null);
        doc.insertString(doc.getLength(), "50", null);
        assertEquals("899.50", pf.getText());

        // Second decimal point or 3rd fractional digit rejected
        doc.insertString(doc.getLength(), ".", null);
        doc.insertString(doc.getLength(), "5", null);
        assertEquals("899.50", pf.getText());

        // Clearing the field works cleanly
        doc.remove(0, doc.getLength());
        assertEquals("", pf.getText());

        // Pasting valid decimal succeeds; pasting malformed string is ignored
        doc.insertString(0, "1200.99", null);
        assertEquals("1200.99", pf.getText());
        doc.insertString(0, "bad!", null);
        assertEquals("1200.99", pf.getText());
    }

    @Test
    void dateDocumentFilterBlocksLettersAndInvalidSeparators() throws BadLocationException {
        Theme.apply(false);
        DateField df = new DateField("mm/dd/yyyy");
        JTextField inner = (JTextField) df.getComponent(0);
        Document doc = inner.getDocument();

        // Alphabetical and special chars rejected
        doc.insertString(0, "Nov", null);
        doc.insertString(0, "abc@!", null);
        doc.insertString(0, "/", null); // leading separator blocked
        assertEquals("", inner.getText());

        // Valid MM/dd/yyyy accepted
        doc.insertString(0, "11/23/2026", null);
        assertEquals("11/23/2026", inner.getText());
        assertEquals(LocalDate.of(2026, 11, 23), df.getDate());

        // Extra characters beyond length 10 rejected
        doc.insertString(doc.getLength(), "9", null);
        assertEquals("11/23/2026", inner.getText());

        // Mixed separators or consecutive separators rejected
        doc.remove(0, doc.getLength());
        doc.insertString(0, "2026-11", null);
        doc.insertString(doc.getLength(), "/", null);
        doc.insertString(doc.getLength(), "-", null); // trailing dash ok
        doc.insertString(doc.getLength(), "-", null); // consecutive dash blocked
        doc.insertString(doc.getLength(), "23", null);
        assertEquals("2026-11-23", inner.getText());
        assertEquals(LocalDate.of(2026, 11, 23), df.getDate());
    }

    @Test
    void parsePositiveAmountStrictlyValidatesBigDecimalGreaterThanZero() {
        assertEquals(new BigDecimal("899.00"), InputFilters.parsePositiveAmount("899"));
        assertEquals(new BigDecimal("899.50"), InputFilters.parsePositiveAmount("899.5"));
        assertEquals(new BigDecimal("899.00"), InputFilters.parsePositiveAmount("899.00"));
        assertEquals(new BigDecimal("0.50"), InputFilters.parsePositiveAmount(".50"));
        assertEquals(new BigDecimal("0.01"), InputFilters.parsePositiveAmount("0.01"));

        assertNull(InputFilters.parsePositiveAmount(null));
        assertNull(InputFilters.parsePositiveAmount(""));
        assertNull(InputFilters.parsePositiveAmount("0"));
        assertNull(InputFilters.parsePositiveAmount("0.00"));
        assertNull(InputFilters.parsePositiveAmount("."));
        assertNull(InputFilters.parsePositiveAmount("-10.00"));
        assertNull(InputFilters.parsePositiveAmount("100d"));
        assertNull(InputFilters.parsePositiveAmount("100f"));
        assertNull(InputFilters.parsePositiveAmount("1e3"));
        assertNull(InputFilters.parsePositiveAmount("12.345"));
        assertNull(InputFilters.parsePositiveAmount("abc"));
        assertNull(InputFilters.parsePositiveAmount("1,200.00"));
    }

    @Test
    void parseStrictDateRejectsImpossibleCalendarDatesAndLetters() {
        assertEquals(LocalDate.of(2026, 11, 23), InputFilters.parseStrictDate("11/23/2026"));
        assertEquals(LocalDate.of(2026, 1, 5), InputFilters.parseStrictDate("1/5/2026"));
        assertEquals(LocalDate.of(2026, 11, 23), InputFilters.parseStrictDate("2026-11-23"));
        assertEquals(LocalDate.of(2024, 2, 29), InputFilters.parseStrictDate("02/29/2024"));

        // Impossible calendar dates must NOT be silently coerced by ResolverStyle.SMART
        assertNull(InputFilters.parseStrictDate("02/30/2026"));
        assertNull(InputFilters.parseStrictDate("02/29/2025"));
        assertNull(InputFilters.parseStrictDate("04/31/2026"));
        assertNull(InputFilters.parseStrictDate("2026-02-30"));
        assertNull(InputFilters.parseStrictDate("13/01/2026"));
        assertNull(InputFilters.parseStrictDate("Nov 23, 2026"));
        assertNull(InputFilters.parseStrictDate("abc"));
        assertNull(InputFilters.parseStrictDate(""));
    }

    @Test
    void digitsDocumentFilterEnforcesNumericOnlyAndMaxEightDigits() throws BadLocationException {
        Theme.apply(false);
        UI.TextField otp = new UI.TextField("Enter 8-digit verification code");
        InputFilters.installDigitsFilter(otp, InputFilters.OTP_CODE_LENGTH);
        Document doc = otp.getDocument();

        // Pure non-digits are rejected
        doc.insertString(0, "dfddfsfdsfsfsf", null);
        assertEquals("", otp.getText());
        otp.replaceSelection("abc!@# ");
        assertEquals("", otp.getText());

        // Mixed paste extracts only digits and caps at 8 characters
        otp.replaceSelection("12-ab 34cd567899");
        assertEquals("12345678", otp.getText());

        // Further insertions beyond 8 digits are ignored
        doc.insertString(doc.getLength(), "9", null);
        assertEquals("12345678", otp.getText());

        // Typing/pasting pure non-digits over a selection does not wipe existing digits
        otp.select(2, 4);
        otp.replaceSelection("xyz");
        assertEquals("12345678", otp.getText());

        // Replacing a 2-digit selection with digits respects remaining capacity (2)
        otp.select(2, 4);
        otp.replaceSelection("9876");
        assertEquals("12985678", otp.getText());

        // Clearing the field works cleanly
        otp.setText("");
        assertEquals("", otp.getText());

        // Appending mixed text respects remaining capacity
        doc.insertString(0, "12345", null);
        doc.insertString(doc.getLength(), "a6b7c8d9e0", null);
        assertEquals("12345678", otp.getText());
    }

    @Test
    void isValidVerificationCodeRequiresExactlyEightDigits() {
        assertTrue(InputFilters.isValidVerificationCode("12345678"));
        assertTrue(InputFilters.isValidVerificationCode(" 00123456 "));

        assertFalse(InputFilters.isValidVerificationCode(null));
        assertFalse(InputFilters.isValidVerificationCode(""));
        assertFalse(InputFilters.isValidVerificationCode("   "));
        assertFalse(InputFilters.isValidVerificationCode("1234567"));
        assertFalse(InputFilters.isValidVerificationCode("123456789"));
        assertFalse(InputFilters.isValidVerificationCode("1234abcd"));
        assertFalse(InputFilters.isValidVerificationCode("dfddfsfdsfsfsf"));
    }

    @Test
    void isValidRecoveryCodeAcceptsSixToEightDigitsAndRejectsInvalid() {
        assertTrue(InputFilters.isValidRecoveryCode("123456"));
        assertTrue(InputFilters.isValidRecoveryCode(" 001234 "));
        assertTrue(InputFilters.isValidRecoveryCode("12345678"));

        assertFalse(InputFilters.isValidRecoveryCode(null));
        assertFalse(InputFilters.isValidRecoveryCode(""));
        assertFalse(InputFilters.isValidRecoveryCode("   "));
        assertFalse(InputFilters.isValidRecoveryCode("12345"));
        assertFalse(InputFilters.isValidRecoveryCode("123456789"));
        assertFalse(InputFilters.isValidRecoveryCode("1234ab"));
    }
}
