package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamUrlSanitizerTest {

    @Test
    void masksPasswordQueryParameter() {
        assertEquals(
                "rtmp://example.com:1935/screen?user=streamer&pass=***",
                StreamUrlSanitizer.mask(
                        "rtmp://example.com:1935/screen?user=streamer&pass=very-secret")
        );
    }

    @Test
    void masksPasswordInUserInfo() {
        assertEquals(
                "rtmp://streamer:***@example.com/live",
                StreamUrlSanitizer.mask("rtmp://streamer:very-secret@example.com/live")
        );
    }

    @Test
    void masksPathBasedStreamKey() {
        assertEquals(
                "rtmp://example.com/live/***",
                StreamUrlSanitizer.mask("rtmp://example.com/live/very-secret")
        );
    }

    @Test
    void keepsSinglePublicPathVisible() {
        assertEquals(
                "rtmp://example.com/screen",
                StreamUrlSanitizer.mask("rtmp://example.com/screen")
        );
    }

    @Test
    void handlesMissingValues() {
        assertEquals("", StreamUrlSanitizer.mask(null));
        assertEquals("", StreamUrlSanitizer.mask("  "));
    }
}
