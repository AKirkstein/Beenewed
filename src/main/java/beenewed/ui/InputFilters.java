package beenewed.ui;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reusable keystroke/paste {@link DocumentFilter}s and strict submission parsers
 * for currency (positive {@link BigDecimal}, max 2 decimal places) and date ({@link LocalDate}) fields.
 */
public final class InputFilters {
    private InputFilters() {}

    /** Standard length of an email OTP verification code. */
    public static final int OTP_CODE_LENGTH = 8;

    /** Allows digits with at most one decimal point and up to 2 fractional digits while typing. */
    private static final Pattern DECIMAL_TYPING = Pattern.compile("^\\d*(?:\\.\\d{0,2})?$");
    /** Strict submission pattern: digits with optional 1–2 decimal places (or leading dot with 1–2 digits). */
    private static final Pattern DECIMAL_COMPLETE = Pattern.compile("^(?:\\d+(?:\\.\\d{1,2})?|\\.\\d{1,2})$");
    /** Strict 8-digit verification code pattern. */
    private static final Pattern OTP_CODE_PATTERN = Pattern.compile("^\\d{" + OTP_CODE_LENGTH + "}$");

    private static final DateTimeFormatter STRICT_SLASH_PADDED =
            DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter STRICT_SLASH_SHORT =
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter STRICT_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);

    /** Restricts a text field to digits and at most one decimal point with up to 2 decimal places. */
    public static void installDecimalFilter(JTextField field) {
        if (field.getDocument() instanceof AbstractDocument doc) {
            doc.setDocumentFilter(new DecimalDocumentFilter());
        }
    }

    /** Restricts a text field to valid date characters (digits and '/' or '-' separators, max 10 chars). */
    public static void installDateFilter(JTextField field) {
        if (field.getDocument() instanceof AbstractDocument doc) {
            doc.setDocumentFilter(new DateDocumentFilter());
        }
    }

    /** Restricts a text field to ASCII digits (0-9) up to {@code maxLength} characters. */
    public static void installDigitsFilter(JTextField field, int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        if (field.getDocument() instanceof AbstractDocument doc) {
            doc.setDocumentFilter(new DigitsDocumentFilter(maxLength));
        }
    }

    /** Returns {@code true} only if {@code text} (after trimming) is a strict 8-digit verification code. */
    public static boolean isValidVerificationCode(String text) {
        return text != null && OTP_CODE_PATTERN.matcher(text.trim()).matches();
    }

    /**
     * Strictly parses a positive currency amount into a 2-decimal {@link BigDecimal}.
     * Rejects zero, negative values, letters, scientific notation, and more than 2 decimal places.
     */
    public static BigDecimal parsePositiveAmount(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || !DECIMAL_COMPLETE.matcher(trimmed).matches()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(trimmed);
            if (bd.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            return bd.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    /**
     * Strictly parses a date string in {@code MM/dd/yyyy}, {@code M/d/yyyy}, or {@code yyyy-MM-dd}
     * format using {@link ResolverStyle#STRICT}, rejecting impossible dates (e.g. {@code 02/30/2026})
     * and alphabetical text.
     */
    public static LocalDate parseStrictDate(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{STRICT_SLASH_PADDED, STRICT_SLASH_SHORT, STRICT_ISO}) {
            try {
                return LocalDate.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {
                // try next strict format
            }
        }
        return null;
    }

    static boolean isAllowedDecimalCandidate(String candidate) {
        return candidate != null && DECIMAL_TYPING.matcher(candidate).matches();
    }

    static boolean isAllowedDateCandidate(String candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate.isEmpty()) {
            return true;
        }
        if (candidate.length() > 10) {
            return false;
        }
        char separator = 0;
        int sepCount = 0;
        boolean prevWasSep = false;
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (ch >= '0' && ch <= '9') {
                prevWasSep = false;
            } else if (ch == '/' || ch == '-') {
                if (i == 0 || prevWasSep) {
                    return false;
                }
                if (separator == 0) {
                    separator = ch;
                } else if (separator != ch) {
                    return false; // do not mix '/' and '-'
                }
                sepCount++;
                if (sepCount > 2) {
                    return false;
                }
                prevWasSep = true;
            } else {
                return false;
            }
        }
        return true;
    }

    private static String candidateAfterReplace(Document doc, int offset, int length, String text)
            throws BadLocationException {
        String current = doc.getText(0, doc.getLength());
        String insert = text == null ? "" : text;
        return current.substring(0, offset) + insert + current.substring(offset + length);
    }

    static final class DecimalDocumentFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (string == null) return;
            String candidate = candidateAfterReplace(fb.getDocument(), offset, 0, string);
            if (isAllowedDecimalCandidate(candidate)) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            String candidate = candidateAfterReplace(fb.getDocument(), offset, length, text);
            if (isAllowedDecimalCandidate(candidate)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            String candidate = candidateAfterReplace(fb.getDocument(), offset, length, "");
            if (isAllowedDecimalCandidate(candidate)) {
                super.remove(fb, offset, length);
            }
        }
    }

    static final class DateDocumentFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (string == null) return;
            String candidate = candidateAfterReplace(fb.getDocument(), offset, 0, string);
            if (isAllowedDateCandidate(candidate)) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            String candidate = candidateAfterReplace(fb.getDocument(), offset, length, text);
            if (isAllowedDateCandidate(candidate)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            String candidate = candidateAfterReplace(fb.getDocument(), offset, length, "");
            if (isAllowedDateCandidate(candidate)) {
                super.remove(fb, offset, length);
            }
        }
    }

    static final class DigitsDocumentFilter extends DocumentFilter {
        private final int maxLength;

        DigitsDocumentFilter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (string == null || string.isEmpty()) {
                return;
            }
            String digits = stripNonDigits(string);
            if (digits.isEmpty()) {
                return;
            }
            int remaining = maxLength - fb.getDocument().getLength();
            if (remaining <= 0) {
                return;
            }
            String toInsert = digits.length() > remaining ? digits.substring(0, remaining) : digits;
            super.insertString(fb, offset, toInsert, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            if (text == null || text.isEmpty()) {
                if (length > 0) {
                    super.replace(fb, offset, length, "", attrs);
                }
                return;
            }
            String digits = stripNonDigits(text);
            if (digits.isEmpty()) {
                return;
            }
            int currentLength = fb.getDocument().getLength();
            int remaining = maxLength - (currentLength - length);
            if (remaining <= 0) {
                return;
            }
            String toInsert = digits.length() > remaining ? digits.substring(0, remaining) : digits;
            super.replace(fb, offset, length, toInsert, attrs);
        }

        private static String stripNonDigits(String input) {
            StringBuilder sb = new StringBuilder(input.length());
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (c >= '0' && c <= '9') {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
