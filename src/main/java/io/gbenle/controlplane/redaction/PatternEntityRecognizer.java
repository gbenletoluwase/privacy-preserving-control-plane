package io.gbenle.controlplane.redaction;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes structured identifiers with regular expressions and arithmetic checks.
 *
 * <p>Structured identifiers are the easy half of the problem and worth doing precisely.
 * A payment card number is not merely sixteen digits, it is sixteen digits that satisfy
 * the Luhn checksum, so this recognizer runs the checksum before emitting a span. The
 * effect is a sharp drop in false positives on order numbers and timestamps, which
 * matters because every false positive deletes information a downstream analyst needed.
 */
public final class PatternEntityRecognizer implements EntityRecognizer {

    private record Rule(String type, Pattern pattern, double confidence, boolean luhn) { }

    private static final List<Rule> RULES = List.of(
            new Rule("EMAIL",
                    Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
                    0.98, false),
            new Rule("SSN",
                    Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)"),
                    0.95, false),
            new Rule("CREDIT_CARD",
                    Pattern.compile("(?<![\\d\\-])(?:\\d[ \\-]?){12,18}\\d(?![\\d\\-])"),
                    0.99, true),
            new Rule("PHONE",
                    Pattern.compile("(?<!\\d)(?:\\+?1[ .\\-]?)?(?:\\(\\d{3}\\)|\\d{3})[ .\\-]\\d{3}[ .\\-]\\d{4}(?!\\d)"),
                    0.80, false),
            new Rule("IPV4",
                    Pattern.compile("(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
                            + "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?![\\d.])"),
                    0.70, false));

    @Override
    public String name() {
        return "pattern";
    }

    @Override
    public List<Entity> recognize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Entity> found = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                String match = matcher.group();
                if (rule.luhn() && !passesLuhn(match)) {
                    continue;
                }
                found.add(new Entity(rule.type(), matcher.start(), matcher.end(),
                        match, rule.confidence(), name()));
            }
        }
        return found;
    }

    /**
     * The Luhn checksum, ISO/IEC 7812-1, used by every major payment network.
     *
     * @return true when the digits of {@code candidate} form a valid Luhn sequence
     */
    public static boolean passesLuhn(String candidate) {
        int sum = 0;
        int digits = 0;
        boolean doubling = false;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            int digit = c - '0';
            digits++;
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return digits >= 13 && digits <= 19 && sum % 10 == 0;
    }
}
