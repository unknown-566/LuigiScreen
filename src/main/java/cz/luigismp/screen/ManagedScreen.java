package cz.luigismp.screen;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.util.Converter;
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class ManagedScreen {

    private final LuigiScreenPlugin plugin;
    private final Object renderLock = new Object();
    private final Set<UUID> spawnedViewers = new HashSet<>();
    private final AtomicReference<SharedVideoFrame> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean forceFullFrame = new AtomicBoolean();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicLong replacedFrames = new AtomicLong();
    private final AtomicLong totalRenderNanos = new AtomicLong();
    private final AtomicLong lastRenderNanos = new AtomicLong();

    private volatile ScreenDefinition definition;
    private volatile Player[] receiverSnapshot = new Player[0];
    private volatile String lastRenderError = "none";
    private volatile long nextRenderNanos;
    private volatile IMapDisplay display;
    private volatile IDrawingSpace drawing;
    private FullSpacedColorBuffer previousFrame;
    private World displayWorld;

    ManagedScreen(LuigiScreenPlugin plugin, MapEngineApi mapEngine,
                  ScreenDefinition definition, World world) {
        this.plugin = plugin;
        this.definition = definition;
        this.displayWorld = world;
        this.display = mapEngine.displayProvider().createBasic(
                definition.location(), definition.secondCorner(), definition.facing());
        this.display.glowing(plugin.getConfig().getBoolean("screen.glowing-frames", false));
        this.drawing = mapEngine.pipeline().createDrawingSpace(display);
        this.drawing.ctx().buffering(false);
        this.drawing.ctx().bundling(false);
        reloadRenderingSettings();
    }

    String id() {
        return definition.id();
    }

    ScreenDefinition definition() {
        return definition;
    }

    void updateDefinition(ScreenDefinition value) {
        definition = value;
        nextRenderNanos = 0;
    }

    void reloadRenderingSettings() {
        IMapDisplay currentDisplay = display;
        IDrawingSpace currentDrawing = drawing;
        if (currentDisplay != null) {
            currentDisplay.glowing(
                    plugin.getConfig().getBoolean("screen.glowing-frames", false));
        }
        if (currentDrawing != null) {
            currentDrawing.ctx().converter(
                    plugin.getConfig().getBoolean("screen.dithering", false)
                            ? Converter.FLOYD_STEINBERG : Converter.DIRECT);
        }
        forceFullFrame.set(true);
    }

    int width() {
        IMapDisplay current = display;
        return current == null ? 0 : current.width();
    }

    int height() {
        IMapDisplay current = display;
        return current == null ? 0 : current.height();
    }

    int pixelWidth() {
        IMapDisplay current = display;
        return current == null ? 0 : current.pixelWidth();
    }

    int pixelHeight() {
        IMapDisplay current = display;
        return current == null ? 0 : current.pixelHeight();
    }

    int viewers() {
        return receiverSnapshot.length;
    }

    boolean hasViewers() {
        return receiverSnapshot.length > 0;
    }

    boolean enabled() {
        return definition.enabled();
    }

    double effectiveFps() {
        IMapDisplay current = display;
        return ScreenPolicy.effectiveFps(
                definition.fps(),
                plugin.getConfig().getBoolean("performance.adaptive-fps", true),
                current == null ? 0 : current.width(),
                current == null ? 0 : current.height(),
                plugin.getConfig().getDouble("performance.max-map-updates-per-second", 400),
                plugin.getConfig().getDouble("performance.minimum-fps", 0.2)
        );
    }

    void refreshViewers() {
        if (destroyed.get()) {
            return;
        }
        World currentWorld = displayWorld;
        IMapDisplay currentDisplay = display;
        if (currentWorld == null || currentDisplay == null) {
            return;
        }

        double maxDistance = definition.distance();
        double maxDistanceSquared = maxDistance * maxDistance;
        double centerX = (currentDisplay.box().getMinX() + currentDisplay.box().getMaxX()) / 2.0;
        double centerY = (currentDisplay.box().getMinY() + currentDisplay.box().getMaxY()) / 2.0;
        double centerZ = (currentDisplay.box().getMinZ() + currentDisplay.box().getMaxZ()) / 2.0;
        Set<UUID> shouldSee = new HashSet<>();
        List<Player> receivers = new ArrayList<>();
        boolean addedViewer = false;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != currentWorld) {
                continue;
            }
            if (!ScreenPermissions.canView(player, definition)) {
                continue;
            }
            double dx = player.getLocation().getX() - centerX;
            double dy = player.getLocation().getY() - centerY;
            double dz = player.getLocation().getZ() - centerZ;
            if (dx * dx + dy * dy + dz * dz > maxDistanceSquared) {
                continue;
            }
            shouldSee.add(player.getUniqueId());
            receivers.add(player);
            if (spawnedViewers.add(player.getUniqueId())) {
                addedViewer = true;
                currentDisplay.spawn(player);
            }
        }

        Set<UUID> remove = new HashSet<>(spawnedViewers);
        remove.removeAll(shouldSee);
        for (UUID uuid : remove) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                currentDisplay.despawn(player);
            }
            spawnedViewers.remove(uuid);
        }
        receiverSnapshot = receivers.toArray(Player[]::new);
        if (addedViewer) {
            forceFullFrame.set(true);
        }
    }

    void removeViewer(UUID playerId) {
        spawnedViewers.remove(playerId);
    }

    void offerFrame(SharedVideoFrame frame) {
        if (destroyed.get()) {
            return;
        }
        receivedFrames.incrementAndGet();
        SharedVideoFrame old = pendingFrame.getAndSet(frame.retain());
        if (old != null) {
            replacedFrames.incrementAndGet();
            old.release();
        }
        if (destroyed.get()) {
            SharedVideoFrame queued = pendingFrame.getAndSet(null);
            if (queued != null) {
                queued.release();
            }
        }
    }

    void showOfflineFrame(String message) {
        if (destroyed.get()) {
            return;
        }
        int width = Math.min(pixelWidth(), 896);
        int height = Math.min(pixelHeight(), 512);
        if (width < 1 || height < 1) {
            return;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(10, 18, 14));
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(44, 212, 112));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(24, height / 10)));
            drawCentered(graphics, plugin.messages().plain("screen.offline-title"),
                    width, height / 2 - 20);
            graphics.setColor(Color.LIGHT_GRAY);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(14, height / 20)));
            drawCentered(graphics, message, width, height / 2 + 35);
        } finally {
            graphics.dispose();
        }
        SharedVideoFrame frame = new SharedVideoFrame(image);
        offerFrame(frame);
        frame.release();
    }

    void flushPendingFrame(long now) {
        if (destroyed.get()) {
            SharedVideoFrame queued = pendingFrame.getAndSet(null);
            if (queued != null) {
                queued.release();
            }
            return;
        }
        boolean fullFrame = forceFullFrame.get();
        if (!fullFrame && now < nextRenderNanos) {
            return;
        }

        SharedVideoFrame shared = pendingFrame.getAndSet(null);
        fullFrame = forceFullFrame.getAndSet(false);
        if (shared == null && !fullFrame) {
            return;
        }
        IMapDisplay currentDisplay = display;
        if (currentDisplay == null) {
            if (shared != null) {
                shared.release();
            }
            return;
        }
        long interval = Math.max(50_000_000L,
                Math.round(1_000_000_000.0 / effectiveFps()));
        nextRenderNanos = now + interval;

        long renderStarted = System.nanoTime();
        boolean renderCompleted = false;
        try {
            synchronized (renderLock) {
                IDrawingSpace currentDrawing = drawing;
                if (currentDrawing == null) {
                    return;
                }
                currentDrawing.ctx().receivers(Arrays.asList(receiverSnapshot));
                if (shared != null) {
                    scaleIntoBuffer(shared.image(), currentDrawing.buffer());
                }

                int maxDeltaMaps = Math.max(0,
                        plugin.getConfig().getInt("performance.delta-updates-max-maps", 256));
                boolean useDelta = (long) currentDisplay.width()
                        * currentDisplay.height() <= maxDeltaMaps;
                currentDrawing.ctx().previousBuffer(fullFrame || !useDelta ? null : previousFrame);
                currentDrawing.flush();
                previousFrame = useDelta ? currentDrawing.buffer().copy() : null;
                renderedFrames.incrementAndGet();
                lastRenderError = "none";
                renderCompleted = true;
            }
        } catch (Throwable throwable) {
            lastRenderError = throwable.getClass().getSimpleName();
            plugin.getLogger().severe(plugin.messages().plain(
                    "logs.render-failed-screen",
                    "screen", id(),
                    "error", StreamUrlSanitizer.maskError(throwable)));
        } finally {
            if (renderCompleted) {
                long elapsed = System.nanoTime() - renderStarted;
                lastRenderNanos.set(elapsed);
                totalRenderNanos.addAndGet(elapsed);
            }
            if (shared != null) {
                shared.release();
            }
        }
    }

    void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        IMapDisplay oldDisplay;
        synchronized (renderLock) {
            oldDisplay = display;
            display = null;
            drawing = null;
            previousFrame = null;
        }
        SharedVideoFrame queued = pendingFrame.getAndSet(null);
        if (queued != null) {
            queued.release();
        }
        receiverSnapshot = new Player[0];
        forceFullFrame.set(false);
        if (oldDisplay != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                oldDisplay.despawn(player);
            }
            oldDisplay.destroy();
        }
        spawnedViewers.clear();
        displayWorld = null;
    }

    long receivedFrames() {
        return receivedFrames.get();
    }

    long renderedFrames() {
        return renderedFrames.get();
    }

    long replacedFrames() {
        return replacedFrames.get();
    }

    boolean frameQueued() {
        return pendingFrame.get() != null;
    }

    long lastRenderNanos() {
        return lastRenderNanos.get();
    }

    long averageRenderNanos() {
        long rendered = renderedFrames.get();
        return rendered == 0 ? 0 : totalRenderNanos.get() / rendered;
    }

    String lastRenderError() {
        return lastRenderError;
    }

    long estimatedImageBufferBytes() {
        long displayBytes = (long) pixelWidth() * pixelHeight() * Integer.BYTES;
        long bytes = displayBytes;
        synchronized (renderLock) {
            if (previousFrame != null) {
                bytes += displayBytes;
            }
        }
        SharedVideoFrame queued = pendingFrame.get();
        if (queued != null) {
            bytes += (long) queued.image().getWidth() * queued.image().getHeight()
                    * Integer.BYTES;
        }
        return bytes;
    }

    private static void scaleIntoBuffer(BufferedImage source, FullSpacedColorBuffer target) {
        int width = target.width();
        int height = target.height();
        int[] masks = {0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000};
        DataBufferInt dataBuffer = new DataBufferInt(target.buffer(), target.size());
        WritableRaster raster = WritableRaster.createPackedRaster(
                dataBuffer, width, height, width, masks, null);
        DirectColorModel colorModel = new DirectColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                32, masks[0], masks[1], masks[2], masks[3],
                false, DataBuffer.TYPE_INT);
        BufferedImage output = new BufferedImage(colorModel, raster, false, null);

        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_SPEED);
            double scale = Math.min((double) width / source.getWidth(),
                    (double) height / source.getHeight());
            int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
            int x = (width - scaledWidth) / 2;
            int y = (height - scaledHeight) / 2;
            graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
        } finally {
            graphics.dispose();
        }
    }

    private static void drawCentered(Graphics2D graphics, String text, int width, int y) {
        int x = (width - graphics.getFontMetrics().stringWidth(text)) / 2;
        graphics.drawString(text, x, y);
    }
}
