package cz.luigismp.screen;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

public final class SharedVideoFrame {

    private final BufferedImage image;
    private final AtomicInteger references = new AtomicInteger(1);

    SharedVideoFrame(BufferedImage image) {
        this.image = image;
    }

    BufferedImage image() {
        return image;
    }

    SharedVideoFrame retain() {
        references.incrementAndGet();
        return this;
    }

    void release() {
        if (references.decrementAndGet() == 0) {
            image.flush();
        }
    }
}
