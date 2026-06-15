package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenSourceTest {

    @Test
    void parsesEveryPublicSourceTypeAndAliases() {
        assertEquals(SourceType.RTMP, SourceType.parse("rtmp"));
        assertEquals(SourceType.MJPEG, SourceType.parse("mjpg"));
        assertEquals(SourceType.VIDEO, SourceType.parse("local_video"));
        assertEquals(SourceType.IMAGE, SourceType.parse("local-image"));
        assertEquals(SourceType.URL_IMAGE, SourceType.parse("image-url"));
        assertEquals(SourceType.GIF, SourceType.parse("gif"));
        assertNull(SourceType.parse("youtube"));
    }

    @Test
    void validatesLocationsBySourceType() {
        assertTrue(new ScreenSource(SourceType.RTMP,
                "rtmp://example/live").isValid());
        assertTrue(new ScreenSource(SourceType.MJPEG,
                "https://camera.example/feed").isValid());
        assertTrue(new ScreenSource(SourceType.VIDEO,
                "intro.mp4").isValid());
        assertTrue(new ScreenSource(SourceType.IMAGE,
                "poster.png").isValid());
        assertTrue(new ScreenSource(SourceType.URL_IMAGE,
                "https://example/poster.png").isValid());
        assertTrue(new ScreenSource(SourceType.GIF,
                "animation.gif").isValid());
        assertTrue(new ScreenSource(SourceType.GIF,
                "https://example/animation.gif").isValid());

        assertFalse(new ScreenSource(SourceType.RTMP,
                "https://example/live").isValid());
        assertFalse(new ScreenSource(SourceType.IMAGE,
                "https://example/poster.png").isValid());
        assertFalse(new ScreenSource(SourceType.VIDEO,
                "ftp://example/intro.mp4").isValid());
        assertFalse(new ScreenSource(SourceType.GIF,
                "ftp://example/animation.gif").isValid());
        assertFalse(new ScreenSource(SourceType.VIDEO, "").isValid());
    }

    @Test
    void sharingKeyIncludesTypeAndNormalizedValue() {
        ScreenSource first = new ScreenSource(SourceType.VIDEO, " intro.mp4 ");
        ScreenSource same = new ScreenSource(SourceType.VIDEO, "intro.mp4");
        ScreenSource normalized = new ScreenSource(
                SourceType.VIDEO, "folder/../intro.mp4");
        ScreenSource otherType = new ScreenSource(SourceType.GIF, "intro.mp4");

        assertEquals(first.key(), same.key());
        assertEquals(first.key(), normalized.key());
        assertFalse(first.key().equals(otherType.key()));
    }

    @Test
    void displayValueMasksRemoteSecretsAndHidesLocalDirectories() {
        assertEquals("rtmp://example/live/***",
                ScreenSource.rtmp("rtmp://example/live/private").displayValue());
        assertEquals("intro.mp4",
                new ScreenSource(SourceType.VIDEO, "folder/intro.mp4").displayValue());
    }
}
