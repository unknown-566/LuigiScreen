package cz.luigismp.screen;

import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.WritableRaster;

/** Reusable BufferedImage view over a MapEngine ARGB buffer. */
final class RenderSurface {

    private static final int[] MASKS = {
            0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000
    };
    private static final DirectColorModel COLOR_MODEL = new DirectColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            32, MASKS[0], MASKS[1], MASKS[2], MASKS[3],
            false, DataBuffer.TYPE_INT);

    private final int[] pixels;
    private final int width;
    private final int height;
    private final BufferedImage image;

    RenderSurface(FullSpacedColorBuffer target) {
        this.pixels = target.buffer();
        this.width = target.width();
        this.height = target.height();

        DataBufferInt dataBuffer = new DataBufferInt(pixels, pixels.length);
        WritableRaster raster = WritableRaster.createPackedRaster(
                dataBuffer, width, height, width, MASKS, null);
        this.image = new BufferedImage(COLOR_MODEL, raster, false, null);
    }

    boolean wraps(FullSpacedColorBuffer target) {
        return pixels == target.buffer()
                && width == target.width()
                && height == target.height();
    }

    BufferedImage image() {
        return image;
    }
}
