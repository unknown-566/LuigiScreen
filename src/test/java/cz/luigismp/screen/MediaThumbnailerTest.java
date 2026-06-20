package cz.luigismp.screen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaThumbnailerTest {

    @TempDir
    Path directory;

    @Test
    void createsCenteredMapSizedImageThumbnail() throws Exception {
        Path source = directory.resolve("wide.png");
        BufferedImage input = new BufferedImage(320, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = input.createGraphics();
        graphics.setColor(Color.GREEN);
        graphics.fillRect(0, 0, input.getWidth(), input.getHeight());
        graphics.dispose();
        ImageIO.write(input, "png", source.toFile());

        Path thumbnail = MediaThumbnailer.create(
                directory, source, SourceType.IMAGE, "wide.png");

        assertNotNull(thumbnail);
        assertTrue(Files.isRegularFile(thumbnail));
        BufferedImage output = ImageIO.read(thumbnail.toFile());
        assertEquals(128, output.getWidth());
        assertEquals(128, output.getHeight());
    }

    @Test
    void ignoresSourcesWithoutThumbnailSupport() {
        assertEquals(null, MediaThumbnailer.create(
                directory, directory.resolve("stream"), SourceType.RTMP, "stream"));
    }
}
