package cz.luigismp.screen;

import java.util.Locale;

public final class DurationParser {

    private DurationParser() {
    }

    static long parseMillis(Object value, long fallbackMillis) {
        if (value == null) {
            return fallbackMillis;
        }
        if (value instanceof Number number) {
            return Math.max(0, Math.round(number.doubleValue() * 1000));
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return fallbackMillis;
        }

        double multiplier = 1000;
        if (text.endsWith("ms")) {
            multiplier = 1;
            text = text.substring(0, text.length() - 2).trim();
        } else if (text.endsWith("s")) {
            multiplier = 1000;
            text = text.substring(0, text.length() - 1).trim();
        } else if (text.endsWith("m")) {
            multiplier = 60_000;
            text = text.substring(0, text.length() - 1).trim();
        } else if (text.endsWith("h")) {
            multiplier = 3_600_000;
            text = text.substring(0, text.length() - 1).trim();
        }

        try {
            return Math.max(0, Math.round(Double.parseDouble(text) * multiplier));
        } catch (NumberFormatException ignored) {
            return fallbackMillis;
        }
    }
}
