package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugTextTest {

    @Test
    void formatsBinaryMemoryUnits() {
        assertEquals("0 B", DebugText.bytes(0));
        assertEquals("1.0 KiB", DebugText.bytes(1024));
        assertEquals("1.5 MiB", DebugText.bytes(1_572_864));
    }

    @Test
    void formatsDurations() {
        assertEquals("0m 00s", DebugText.duration(0));
        assertEquals("2m 05s", DebugText.duration(125_000));
        assertEquals("1h 01m", DebugText.duration(3_660_000));
    }

    @Test
    void formatsFrameAge() {
        assertEquals("never", DebugText.age(-1, "never"));
        assertEquals("500 ms", DebugText.age(500, "never"));
        assertEquals("1.5 s", DebugText.age(1500, "never"));
    }

    @Test
    void shortensErrorsForBossBar() {
        assertEquals("none", DebugText.shortError(null, "none"));
        assertEquals("stream offline", DebugText.shortError("stream offline", "none"));
        assertEquals(32,
                DebugText.shortError("abcdefghijklmnopqrstuvwxyz0123456789", "none").length());
    }
}
