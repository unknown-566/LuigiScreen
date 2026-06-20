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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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
    private volatile List<Player> receiverSnapshot = List.of();
    private volatile String lastRenderError = "none";
    private volatile long nextRenderNanos;
    private volatile double effectiveFps;
    private volatile int maxDeltaMaps;
    private volatile IMapDisplay display;
    private volatile IDrawingSpace drawing;
    private boolean glowing;
    private boolean dithering;
    private FullSpacedColorBuffer previousFrame;
    private RenderSurface renderSurface;
    private World displayWorld;

    ManagedScreen(LuigiScreenPlugin plugin, MapEngineApi mapEngine,
                  ScreenDefinition definition, World world) {
        this.plugin = plugin;
        this.definition = definition;
        this.displayWorld = world;
        this.display = mapEngine.displayProvider().createBasic(
                definition.location(), definition.secondCorner(), definition.facing());
        this.drawing = mapEngine.pipeline().createDrawingSpace(display);
        this.drawing.ctx().buffering(false);
        this.drawing.ctx().bundling(false);
        this.glowing = plugin.getConfig().getBoolean("screen.glowing-frames", false);
        this.dithering = plugin.getConfig().getBoolean("screen.dithering", false);
        this.display.glowing(glowing);
        this.drawing.ctx().converter(
                dithering ? Converter.FLOYD_STEINBERG : Converter.DIRECT);
        reloadPerformanceSettings();
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
        reloadPerformanceSettings();
    }

    void reloadRenderingSettings() {
        IMapDisplay currentDisplay = display;
        IDrawingSpace currentDrawing = drawing;
        boolean updatedGlowing =
                plugin.getConfig().getBoolean("screen.glowing-frames", false);
        boolean updatedDithering =
                plugin.getConfig().getBoolean("screen.dithering", false);
        if (currentDisplay != null && updatedGlowing != glowing) {
            despawnTrackedViewers(currentDisplay);
            currentDisplay.glowing(updatedGlowing);
            glowing = updatedGlowing;
        }
        if (currentDrawing != null && updatedDithering != dithering) {
            currentDrawing.ctx().converter(
                    updatedDithering ? Converter.FLOYD_STEINBERG : Converter.DIRECT);
            dithering = updatedDithering;
        }
        reloadPerformanceSettings();
        forceFullFrame.set(true);
    }

    void prepareViewerResync() {
        IMapDisplay currentDisplay = display;
        if (currentDisplay != null) {
            despawnTrackedViewers(currentDisplay);
        }
        forceFullFrame.set(true);
    }

    private void despawnTrackedViewers(IMapDisplay currentDisplay) {
        for (UUID uuid : new HashSet<>(spawnedViewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                currentDisplay.despawn(player);
            }
        }
        spawnedViewers.clear();
        receiverSnapshot = List.of();
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
        return receiverSnapshot.size();
    }

    boolean hasViewerWithPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        for (Player player : receiverSnapshot) {
            if (player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    boolean allViewersHavePermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return !receiverSnapshot.isEmpty()
                && receiverSnapshot.stream().allMatch(player -> player.hasPermission(permission));
    }

    boolean hasViewers() {
        return !receiverSnapshot.isEmpty();
    }

    boolean enabled() {
        return definition.enabled();
    }

    double effectiveFps() {
        return effectiveFps;
    }

    void refreshViewers(List<ViewerPosition> onlinePlayers) {
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

        ScreenDefinition currentDefinition = definition;
        for (ViewerPosition viewer : onlinePlayers) {
            Player player = viewer.player();
            if (viewer.world() != currentWorld) {
                continue;
            }
            if (!ScreenPermissions.canView(player, currentDefinition)) {
                continue;
            }
            double dx = viewer.x() - centerX;
            double dy = viewer.y() - centerY;
            double dz = viewer.z() - centerZ;
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
        receiverSnapshot = List.copyOf(receivers);
        if (addedViewer) {
            forceFullFrame.set(true);
        }
    }

    void removeViewer(UUID playerId) {
        spawnedViewers.remove(playerId);
        List<Player> current = receiverSnapshot;
        if (current.stream().anyMatch(player -> player.getUniqueId().equals(playerId))) {
            receiverSnapshot = current.stream()
                    .filter(player -> !player.getUniqueId().equals(playerId))
                    .toList();
        }
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
                currentDrawing.ctx().receivers(receiverSnapshot);
                if (shared != null) {
                    FullSpacedColorBuffer target = currentDrawing.buffer();
                    RenderSurface surface = renderSurface;
                    if (surface == null || !surface.wraps(target)) {
                        surface = new RenderSurface(target);
                        renderSurface = surface;
                    }
                    scaleIntoImage(shared.image(), surface.image());
                }

                boolean useDelta = (long) currentDisplay.width()
                        * currentDisplay.height() <= maxDeltaMaps;
                currentDrawing.ctx().previousBuffer(fullFrame || !useDelta ? null : previousFrame);
                currentDrawing.flush();
                if (useDelta) {
                    capturePreviousFrame(currentDrawing.buffer());
                } else {
                    previousFrame = null;
                }
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
            renderSurface = null;
        }
        SharedVideoFrame queued = pendingFrame.getAndSet(null);
        if (queued != null) {
            queued.release();
        }
        receiverSnapshot = List.of();
        forceFullFrame.set(false);
        if (oldDisplay != null) {
            despawnTrackedViewers(oldDisplay);
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

    private void reloadPerformanceSettings() {
        IMapDisplay current = display;
        effectiveFps = ScreenPolicy.effectiveFps(
                definition.fps(),
                plugin.getConfig().getBoolean("performance.adaptive-fps", true),
                current == null ? 0 : current.width(),
                current == null ? 0 : current.height(),
                plugin.getConfig().getDouble("performance.max-map-updates-per-second", 400),
                plugin.getConfig().getDouble("performance.minimum-fps", 0.2));
        maxDeltaMaps = Math.max(0,
                plugin.getConfig().getInt("performance.delta-updates-max-maps", 256));
    }

    private void capturePreviousFrame(FullSpacedColorBuffer current) {
        if (previousFrame == null
                || previousFrame.width() != current.width()
                || previousFrame.height() != current.height()) {
            previousFrame = new FullSpacedColorBuffer(current.width(), current.height());
        }
        System.arraycopy(current.buffer(), 0, previousFrame.buffer(), 0, current.size());
    }

    private static void scaleIntoImage(BufferedImage source, BufferedImage output) {
        int width = output.getWidth();
        int height = output.getHeight();
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
