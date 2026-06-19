package cz.luigismp.screen;

import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;
import org.junit.jupiter.api.Test;

import java.awt.image.DataBufferInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderSurfaceTest {

    @Test
    void wrapsMapEnginePixelsWithoutCopyingThem() {
        FullSpacedColorBuffer target = new FullSpacedColorBuffer(4, 3);
        RenderSurface surface = new RenderSurface(target);

        int[] imagePixels = ((DataBufferInt) surface.image()
                .getRaster().getDataBuffer()).getData();

        assertSame(target.buffer(), imagePixels);
        assertTrue(surface.wraps(target));

        surface.image().setRGB(2, 1, 0xff123456);
        assertEquals(0xff123456, target.pixel(2, 1));
    }

    @Test
    void rejectsAReplacementBufferWithTheSameDimensions() {
        RenderSurface surface = new RenderSurface(new FullSpacedColorBuffer(4, 3));

        assertFalse(surface.wraps(new FullSpacedColorBuffer(4, 3)));
    }
}
