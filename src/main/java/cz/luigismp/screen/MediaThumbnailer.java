package cz.luigismp.screen;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class MediaThumbnailer {

    private MediaThumbnailer() {
    }

    static Path create(Path mediaRoot, Path source, SourceType type, String id) {
        if (type == null) return null;
        Path directory = mediaRoot.resolve(".thumbnails");
        Path target = directory.resolve(hash(id) + ".png");
        try {
            if (Files.isRegularFile(target)
                    && Files.getLastModifiedTime(target).toMillis()
                    >= Files.getLastModifiedTime(source).toMillis()) {
                return target;
            }
            Files.createDirectories(directory);
            BufferedImage image = switch (type) {
                case IMAGE, GIF -> ImageIO.read(source.toFile());
                case VIDEO -> videoFrame(source);
                default -> null;
            };
            if (image == null) return null;
            BufferedImage scaled = scale(image);
            ImageIO.write(scaled, "png", target.toFile());
            return target;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BufferedImage videoFrame(Path source) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source.toFile());
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.setOption("threads", "1");
            grabber.start();
            for (int attempt = 0; attempt < 30; attempt++) {
                Frame frame = grabber.grabImage();
                if (frame != null && frame.image != null) {
                    BufferedImage converted = converter.convert(frame);
                    if (converted != null) {
                        BufferedImage copy = new BufferedImage(converted.getWidth(),
                                converted.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics graphics = copy.getGraphics();
                        try {
                            graphics.drawImage(converted, 0, 0, null);
                        } finally {
                            graphics.dispose();
                        }
                        return copy;
                    }
                }
            }
            return null;
        }
    }

    private static BufferedImage scale(BufferedImage input) {
        BufferedImage output = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double factor = Math.min(128.0 / input.getWidth(), 128.0 / input.getHeight());
            int width = Math.max(1, (int) Math.round(input.getWidth() * factor));
            int height = Math.max(1, (int) Math.round(input.getHeight() * factor));
            int x = (128 - width) / 2;
            int y = (128 - height) / 2;
            graphics.drawImage(input, x, y, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
