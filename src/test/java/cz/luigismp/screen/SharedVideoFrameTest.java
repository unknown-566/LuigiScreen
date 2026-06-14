package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedVideoFrameTest {

    @Test
    void imageIsReleasedOnlyAfterEveryClonedScreenFinishes() {
        TrackingImage image = new TrackingImage();
        SharedVideoFrame frame = new SharedVideoFrame(image);

        frame.retain();
        frame.retain();
        frame.release();
        assertFalse(image.flushed);

        frame.release();
        assertFalse(image.flushed);

        frame.release();
        assertTrue(image.flushed);
    }

    private static final class TrackingImage extends BufferedImage {

        private boolean flushed;

        private TrackingImage() {
            super(2, 2, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public void flush() {
            flushed = true;
            super.flush();
        }
    }
}
