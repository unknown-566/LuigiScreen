package cz.luigismp.screen;

import java.util.Locale;

public final class DebugText {

    private DebugText() {
    }

    static String bytes(long bytes) {
        if (bytes < 1024) {
            return Math.max(0, bytes) + " B";
        }

        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s",
                value, units[unit]);
    }

    static String duration(long millis) {
        long safeMillis = Math.max(0, millis);
        long totalSeconds = safeMillis / 1000;
        long days = totalSeconds / 86_400;
        long hours = totalSeconds % 86_400 / 3_600;
        long minutes = totalSeconds % 3_600 / 60;
        long seconds = totalSeconds % 60;

        if (days > 0) {
            return String.format(Locale.ROOT, "%dd %02dh", days, hours);
        }
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
    }

    static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String age(long millis, String never) {
        if (millis < 0) {
            return never;
        }
        if (millis < 1000) {
            return millis + " ms";
        }
        return decimal(millis / 1000.0) + " s";
    }

    static String shortError(String error, String none) {
        if (error == null || error.isBlank()) {
            return none;
        }
        String compact = error.replace('\n', ' ').replace('\r', ' ');
        return compact.length() > 32 ? compact.substring(0, 29) + "..." : compact;
    }
}
