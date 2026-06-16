package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationParserTest {

    @Test
    void parsesHumanFriendlyDurations() {
        assertEquals(250, DurationParser.parseMillis("250ms", 1));
        assertEquals(30_000, DurationParser.parseMillis("30s", 1));
        assertEquals(120_000, DurationParser.parseMillis("2m", 1));
        assertEquals(3_600_000, DurationParser.parseMillis("1h", 1));
        assertEquals(1_500, DurationParser.parseMillis("1.5s", 1));
    }

    @Test
    void treatsPlainNumbersAsSecondsAndFallsBackOnInvalidValues() {
        assertEquals(42_000, DurationParser.parseMillis(42, 1));
        assertEquals(42_000, DurationParser.parseMillis("42", 1));
        assertEquals(999, DurationParser.parseMillis("wat", 999));
        assertEquals(999, DurationParser.parseMillis(null, 999));
    }
}
