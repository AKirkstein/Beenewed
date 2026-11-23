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
}
