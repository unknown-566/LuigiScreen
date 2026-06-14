package cz.luigismp.screen;

import de.pianoman911.mapengine.api.MapEngineApi;
import de.pianoman911.mapengine.api.clientside.IMapDisplay;
import de.pianoman911.mapengine.api.drawing.IDrawingSpace;
import de.pianoman911.mapengine.api.util.Converter;
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;
import org.bytedeco.javacv.FFmpegLogCallback;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_DEBUG;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_INFO;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;

public final class LuigiScreenPlugin extends JavaPlugin implements Listener {

    private final Object renderLock = new Object();
    private final Set<UUID> spawnedViewers = new HashSet<>();
    private final AtomicReference<BufferedImage> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean forceFullFrame = new AtomicBoolean();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicLong replacedFrames = new AtomicLong();
    private final AtomicLong totalRenderNanos = new AtomicLong();
    private final AtomicLong lastRenderNanos = new AtomicLong();

    private MapEngineApi mapEngine;
    private IMapDisplay display;
    private IDrawingSpace drawing;
    private FullSpacedColorBuffer previousFrame;
    private World displayWorld;
    private RtmpStreamWorker streamWorker;
    private DebugBossBarManager debugBossBars;
    private MediaMtxSetupManager mediaMtxSetup;
    private LocalizationManager messages;
    private BukkitTask viewerTask;
    private BukkitTask streamRestartTask;
    private ScheduledExecutorService renderExecutor;
    private volatile Player[] receiverSnapshot = new Player[0];
    private volatile String lastRenderError = "none";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages = new LocalizationManager(this);
        messages.load();
        configureFfmpegLogging();
        mapEngine = MapEngineApi.instance();

        ScreenCommand commandHandler = new ScreenCommand(this);
        PluginCommand command = getCommand("luigiscreen");
        if (command == null) {
            throw new IllegalStateException("Command luigiscreen is missing from plugin.yml");
        }
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        Bukkit.getPluginManager().registerEvents(this, this);
        debugBossBars = new DebugBossBarManager(this);
        debugBossBars.start();
        mediaMtxSetup = new MediaMtxSetupManager(this);
        Bukkit.getPluginManager().registerEvents(mediaMtxSetup, this);

        loadDisplay();
        startRenderExecutor();
        viewerTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshViewers, 20L, 20L);
        if (hasDisplay() && getConfig().getBoolean("screen.auto-start", true)) {
            startStream();
        }
    }

    @Override
    public void onDisable() {
        if (viewerTask != null) {
            viewerTask.cancel();
        }
        if (debugBossBars != null) {
            debugBossBars.stop();
        }
        if (mediaMtxSetup != null) {
            mediaMtxSetup.shutdown();
        }
        removeDisplay(false);
        stopRenderExecutor();
    }

    boolean hasDisplay() {
        return display != null;
    }

    int maxScreenWidth() {
        return ScreenPolicy.maxDimension(getConfig().getInt("screen.max-width", 10));
    }

    int maxScreenHeight() {
        return ScreenPolicy.maxDimension(getConfig().getInt("screen.max-height", 6));
    }

    int maxTotalMaps() {
        return ScreenPolicy.maxTotalMaps(getConfig().getInt("screen.max-total-maps", 60));
    }

    int screenPixelWidth() {
        IMapDisplay current = display;
        return current == null ? 0 : current.pixelWidth();
    }

    int screenPixelHeight() {
        IMapDisplay current = display;
        return current == null ? 0 : current.pixelHeight();
    }

    boolean shouldRenderVideo() {
        return !getConfig().getBoolean("performance.pause-rendering-without-viewers", true)
                || receiverSnapshot.length > 0;
    }

    double effectiveFps() {
        IMapDisplay current = display;
        return ScreenPolicy.effectiveFps(
                getConfig().getDouble("stream.fps", 8),
                getConfig().getBoolean("performance.adaptive-fps", true),
                current == null ? 0 : current.width(),
                current == null ? 0 : current.height(),
                getConfig().getDouble("performance.max-map-updates-per-second", 400),
                getConfig().getDouble("performance.minimum-fps", 0.2)
        );
    }

    boolean createDisplay(World world, BlockVector a, BlockVector b, BlockFace facing) {
        if (!isScreenSizeAllowed(a, b, facing)) {
            return false;
        }
        if (!removeDisplay(false)) {
            return false;
        }
        createDisplayInternal(world, a, b, facing);

        FileConfiguration config = getConfig();
        config.set("screen.configured", true);
        config.set("screen.world", world.getName());
        config.set("screen.corner-a", serialize(a));
        config.set("screen.corner-b", serialize(b));
        config.set("screen.facing", facing.name());
        saveConfig();

        renderOfflineFrame(messages.plain("screen.connecting"));
        refreshViewers();
        startStream();
        return true;
    }

    boolean startStream() {
        if (display == null) {
            return false;
        }
        if (streamWorker != null) {
            if (!streamWorker.isTerminated()) {
                return false;
            }
            streamWorker = null;
        }

        streamWorker = new RtmpStreamWorker(
                this,
                getConfig().getString("stream.url", "rtmp://127.0.0.1:55556/screen"),
                effectiveFps(),
                getConfig().getInt("stream.reconnect-delay-seconds", 3),
                getConfig().getInt("stream.reconnect-max-delay-seconds", 30)
        );
        return streamWorker.start();
    }

    boolean stopStream() {
        cancelPendingStreamRestart();
        RtmpStreamWorker worker = streamWorker;
        if (worker == null) {
            return true;
        }
        if (worker.stop()) {
            streamWorker = null;
            showOfflineFrameKey("screen.stopped");
            return true;
        }
        return false;
    }

    boolean reloadScreenConfig() {
        if (!stopStream()) {
            getLogger().severe(messages.plain("logs.reload-stop-failed"));
            return false;
        }

        stopRenderExecutor();
        if (!removeDisplay(false)) {
            startRenderExecutor();
            return false;
        }

        try {
            reloadConfig();
            getConfig().options().copyDefaults(true);
            saveConfig();
            messages.load();
            configureFfmpegLogging();
            if (debugBossBars != null) {
                debugBossBars.start();
            }
            loadDisplay();
            startRenderExecutor();
            refreshViewers();
            if (hasDisplay() && getConfig().getBoolean("screen.auto-start", true)) {
                startStream();
            }
            getLogger().info(messages.plain("logs.reload-success"));
            return true;
        } catch (Exception exception) {
            getLogger().severe(messages.plain(
                    "logs.reload-failed", "error", StreamUrlSanitizer.maskError(exception)));
            if (renderExecutor == null) {
                startRenderExecutor();
            }
            return false;
        }
    }

    boolean removeDisplay(boolean clearConfig) {
        cancelPendingStreamRestart();
        RtmpStreamWorker worker = streamWorker;
        if (worker != null && !worker.stop()) {
            getLogger().severe(messages.plain("logs.remove-stop-failed"));
            return false;
        }
        streamWorker = null;

        IMapDisplay oldDisplay;
        synchronized (renderLock) {
            oldDisplay = display;
            display = null;
            drawing = null;
            previousFrame = null;
        }
        BufferedImage queued = pendingFrame.getAndSet(null);
        if (queued != null) {
            queued.flush();
        }
        forceFullFrame.set(false);
        receiverSnapshot = new Player[0];

        if (oldDisplay != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                oldDisplay.despawn(player);
            }
            oldDisplay.destroy();
        }
        spawnedViewers.clear();
        displayWorld = null;

        if (clearConfig) {
            getConfig().set("screen.configured", false);
            getConfig().set("screen.world", "");
            getConfig().set("screen.corner-a", "");
            getConfig().set("screen.corner-b", "");
            saveConfig();
        }
        return true;
    }

    Component status() {
        if (display == null) {
            return messages.component("status.no-display");
        }
        String streamState = streamWorker == null ? "stopped" : streamWorker.state();
        String streamError = streamWorker == null ? "none" : streamWorker.lastError();
        return messages.component("status.line", Map.ofEntries(
                Map.entry("width", display.width()),
                Map.entry("height", display.height()),
                Map.entry("stream", messages.state(streamState)),
                Map.entry("stream_error", messages.error(streamError)),
                Map.entry("fps", String.format(java.util.Locale.ROOT, "%.2f", effectiveFps())),
                Map.entry("viewers", spawnedViewers.size()),
                Map.entry("received", receivedFrames.get()),
                Map.entry("rendered", renderedFrames.get()),
                Map.entry("render_error", messages.error(lastRenderError)),
                Map.entry("world", displayWorld.getName()),
                Map.entry("facing", messages.direction(display.direction())),
                Map.entry("url", StreamUrlSanitizer.mask(getConfig().getString("stream.url")))
        ));
    }

    LocalizationManager messages() {
        return messages;
    }

    boolean toggleDebug(Player player) {
        return debugBossBars != null && debugBossBars.toggle(player);
    }

    void beginMediaMtxSetup(Player player, String situation) {
        if (mediaMtxSetup != null) {
            mediaMtxSetup.begin(player, situation);
        }
    }

    boolean applyGeneratedStreamUrl(String streamUrl) {
        RtmpStreamWorker worker = streamWorker;
        boolean restart = worker != null && worker.isRunning();

        getConfig().set("stream.url", streamUrl);
        saveConfig();

        boolean shouldStart = hasDisplay()
                && (restart || getConfig().getBoolean("screen.auto-start", true));
        if (worker == null || worker.isTerminated()) {
            streamWorker = null;
            if (shouldStart) {
                startStream();
            }
            return true;
        }

        cancelPendingStreamRestart();
        worker.requestStop();
        streamRestartTask = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> finishGeneratedStreamRestart(worker, shouldStart),
                1L,
                5L
        );
        return true;
    }

    boolean isStreamRestartPending() {
        return streamRestartTask != null;
    }

    private void finishGeneratedStreamRestart(RtmpStreamWorker worker, boolean shouldStart) {
        if (streamWorker != worker) {
            cancelPendingStreamRestart();
            return;
        }
        if (!worker.isTerminated()) {
            return;
        }

        streamWorker = null;
        cancelPendingStreamRestart();
        showOfflineFrameKey(shouldStart ? "screen.connecting" : "screen.stopped");
        if (shouldStart) {
            startStream();
        }
    }

    private void cancelPendingStreamRestart() {
        BukkitTask task = streamRestartTask;
        streamRestartTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    DebugSnapshot debugSnapshot() {
        IMapDisplay currentDisplay = display;
        RtmpStreamWorker worker = streamWorker;
        int mapWidth = currentDisplay == null ? 0 : currentDisplay.width();
        int mapHeight = currentDisplay == null ? 0 : currentDisplay.height();
        int pixelWidth = currentDisplay == null ? 0 : currentDisplay.pixelWidth();
        int pixelHeight = currentDisplay == null ? 0 : currentDisplay.pixelHeight();
        long rendered = renderedFrames.get();

        return new DebugSnapshot(
                worker == null ? "stopped" : worker.state(),
                worker == null ? "none" : worker.lastError(),
                worker == null ? 0 : worker.sourceWidth(),
                worker == null ? 0 : worker.sourceHeight(),
                worker == null ? 0 : worker.reconnects(),
                worker == null ? 0 : worker.lastFrameAgeMillis(),
                mapWidth,
                mapHeight,
                pixelWidth,
                pixelHeight,
                receiverSnapshot.length,
                receivedFrames.get(),
                rendered,
                replacedFrames.get(),
                pendingFrame.get() != null,
                lastRenderNanos.get(),
                rendered == 0 ? 0 : totalRenderNanos.get() / rendered,
                estimatedImageBufferBytes(),
                effectiveFps(),
                lastRenderError
        );
    }

    void renderFrame(BufferedImage image) {
        receivedFrames.incrementAndGet();
        BufferedImage old = pendingFrame.getAndSet(image);
        if (old != null) {
            replacedFrames.incrementAndGet();
            old.flush();
        }
    }

    private void createDisplayInternal(World world, BlockVector a, BlockVector b, BlockFace facing) {
        synchronized (renderLock) {
            displayWorld = world;
            display = mapEngine.displayProvider().createBasic(a, b, facing);
            display.glowing(getConfig().getBoolean("screen.glowing-frames", false));
            drawing = mapEngine.pipeline().createDrawingSpace(display);
            drawing.ctx().buffering(false);
            drawing.ctx().bundling(false);
            drawing.ctx().converter(getConfig().getBoolean("screen.dithering", false)
                    ? Converter.FLOYD_STEINBERG : Converter.DIRECT);
            previousFrame = null;
        }
        receivedFrames.set(0);
        renderedFrames.set(0);
        replacedFrames.set(0);
        totalRenderNanos.set(0);
        lastRenderNanos.set(0);
        lastRenderError = "none";
    }

    private void loadDisplay() {
        if (!getConfig().getBoolean("screen.configured", false)) {
            return;
        }

        String worldName = getConfig().getString("screen.world", "");
        World world = Bukkit.getWorld(worldName);
        BlockVector a = deserialize(getConfig().getString("screen.corner-a", ""));
        BlockVector b = deserialize(getConfig().getString("screen.corner-b", ""));
        BlockFace facing;
        try {
            facing = BlockFace.valueOf(getConfig().getString("screen.facing", "NORTH"));
        } catch (IllegalArgumentException exception) {
            facing = null;
        }

        if (world == null || a == null || b == null || facing == null) {
            getLogger().warning(messages.plain("logs.saved-screen-invalid"));
            return;
        }
        if (!isScreenSizeAllowed(a, b, facing)) {
            getLogger().warning(messages.plain("logs.saved-screen-too-large"));
            return;
        }

        createDisplayInternal(world, a, b, facing);
        renderOfflineFrame(messages.plain("screen.waiting"));
    }

    void showOfflineFrameKey(String key) {
        renderOfflineFrame(messages.plain(key));
    }

    private void refreshViewers() {
        IMapDisplay currentDisplay = display;
        World currentWorld = displayWorld;
        IDrawingSpace currentDrawing = drawing;
        if (currentDisplay == null || currentWorld == null || currentDrawing == null) {
            return;
        }

        double maxDistance = Math.max(8, getConfig().getDouble("screen.viewer-distance", 64));
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
            double dx = player.getLocation().getX() - centerX;
            double dy = player.getLocation().getY() - centerY;
            double dz = player.getLocation().getZ() - centerZ;
            if (dx * dx + dy * dy + dz * dz <= maxDistanceSquared) {
                shouldSee.add(player.getUniqueId());
                receivers.add(player);
                if (spawnedViewers.add(player.getUniqueId())) {
                    addedViewer = true;
                    currentDisplay.spawn(player);
                }
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

    private void renderOfflineFrame(String message) {
        if (display == null) {
            return;
        }
        int width = Math.min(display.pixelWidth(), 896);
        int height = Math.min(display.pixelHeight(), 512);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(10, 18, 14));
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(44, 212, 112));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(24, height / 10)));
            drawCentered(graphics, messages.plain("screen.title"), width, height / 2 - 20);
            graphics.setColor(Color.LIGHT_GRAY);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(14, height / 20)));
            drawCentered(graphics, message, width, height / 2 + 35);
        } finally {
            graphics.dispose();
        }
        renderFrame(image);
    }

    private void startRenderExecutor() {
        double fps = effectiveFps();
        long periodMillis = Math.max(50, Math.round(1000.0 / fps));
        renderExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LuigiScreen-MapEngine");
            thread.setDaemon(true);
            return thread;
        });
        renderExecutor.scheduleWithFixedDelay(this::flushPendingFrame,
                0, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void stopRenderExecutor() {
        ScheduledExecutorService executor = renderExecutor;
        renderExecutor = null;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void flushPendingFrame() {
        BufferedImage frame = pendingFrame.getAndSet(null);
        boolean fullFrame = forceFullFrame.getAndSet(false);
        if (frame == null && !fullFrame) {
            return;
        }

        long renderStarted = System.nanoTime();
        boolean renderCompleted = false;
        try {
            synchronized (renderLock) {
                IDrawingSpace currentDrawing = drawing;
                if (currentDrawing == null) {
                    return;
                }

                currentDrawing.ctx().receivers(Arrays.asList(receiverSnapshot));
                if (frame != null) {
                    scaleIntoBuffer(frame, currentDrawing.buffer());
                }

                int maxDeltaMaps = Math.max(0,
                        getConfig().getInt("performance.delta-updates-max-maps", 256));
                IMapDisplay currentDisplay = display;
                boolean useDelta = currentDisplay != null
                        && (long) currentDisplay.width() * currentDisplay.height() <= maxDeltaMaps;
                currentDrawing.ctx().previousBuffer(fullFrame || !useDelta ? null : previousFrame);
                currentDrawing.flush();
                previousFrame = useDelta ? currentDrawing.buffer().copy() : null;
                renderedFrames.incrementAndGet();
                lastRenderError = "none";
                renderCompleted = true;
            }
        } catch (Throwable throwable) {
            lastRenderError = throwable.getClass().getSimpleName();
            getLogger().severe(messages.plain(
                    "logs.render-failed", "error", StreamUrlSanitizer.maskError(throwable)));
        } finally {
            if (renderCompleted) {
                long elapsed = System.nanoTime() - renderStarted;
                lastRenderNanos.set(elapsed);
                totalRenderNanos.addAndGet(elapsed);
            }
            if (frame != null) {
                frame.flush();
            }
        }
    }

    private long estimatedImageBufferBytes() {
        long bytes = 0;
        IMapDisplay currentDisplay = display;
        if (currentDisplay != null) {
            long displayBytes = (long) currentDisplay.pixelWidth() * currentDisplay.pixelHeight()
                    * Integer.BYTES;
            bytes += displayBytes;
            synchronized (renderLock) {
                if (previousFrame != null) {
                    bytes += displayBytes;
                }
            }
        }

        BufferedImage queued = pendingFrame.get();
        if (queued != null) {
            bytes += (long) queued.getWidth() * queued.getHeight() * Integer.BYTES;
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

    private boolean isScreenSizeAllowed(BlockVector a, BlockVector b, BlockFace facing) {
        return ScreenPolicy.isSizeAllowed(
                a, b, facing, maxScreenWidth(), maxScreenHeight(), maxTotalMaps());
    }

    private void configureFfmpegLogging() {
        FFmpegLogCallback.set();
        String configured = getConfig().getString("logging.ffmpeg-level", "quiet");
        String level = configured == null ? "quiet"
                : configured.trim().toLowerCase(java.util.Locale.ROOT);
        int nativeLevel = switch (level) {
            case "error" -> AV_LOG_ERROR;
            case "warning", "warn" -> AV_LOG_WARNING;
            case "info" -> AV_LOG_INFO;
            case "debug" -> AV_LOG_DEBUG;
            default -> AV_LOG_QUIET;
        };
        FFmpegLogCallback.setLevel(nativeLevel);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, this::refreshViewers, 20L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        spawnedViewers.remove(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, this::refreshViewers, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        spawnedViewers.remove(event.getPlayer().getUniqueId());
        if (debugBossBars != null) {
            debugBossBars.remove(event.getPlayer());
        }
        Bukkit.getScheduler().runTask(this, this::refreshViewers);
    }

    private static String serialize(BlockVector vector) {
        return vector.getBlockX() + "," + vector.getBlockY() + "," + vector.getBlockZ();
    }

    private static BlockVector deserialize(String value) {
        String[] parts = value.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockVector(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
