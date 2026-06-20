package cz.luigismp.screen;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

final class ThumbnailMapRenderer extends MapRenderer {

    private final Path path;
    private final AtomicBoolean rendered = new AtomicBoolean();

    ThumbnailMapRenderer(Path path) {
        super(false);
        this.path = path;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (!rendered.compareAndSet(false, true)) return;
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image != null) {
                canvas.drawImage(0, 0, image);
            }
        } catch (Exception ignored) {
        }
    }
}
