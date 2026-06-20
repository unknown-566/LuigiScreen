package cz.luigismp.screen;

import de.pianoman911.mapengine.api.MapEngineApi;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_DEBUG;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_INFO;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;

public final class LuigiScreenPlugin extends JavaPlugin implements Listener {

    private final Map<String, ManagedScreen> screens = new ConcurrentHashMap<>();
    private final Map<String, SharedMediaSource> sources = new ConcurrentHashMap<>();
    private final Map<String, ScreenSource> activeSources = new ConcurrentHashMap<>();
    private final java.util.Set<String> frameOnlyScreens = ConcurrentHashMap.newKeySet();
    private MapEngineApi mapEngine;
    private DebugBossBarManager debugBossBars;
    private MediaMtxSetupManager mediaMtxSetup;
    private PlaybackController playbackController;
    private ModrinthUpdateChecker updateChecker;
    private LocalizationManager messages;
    private BukkitTask viewerTask;
    private BukkitTask streamRestartTask;
    private ScheduledExecutorService renderExecutor;
    private volatile Path configuredMediaDirectory;
    private volatile boolean allowAbsoluteMediaPaths;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadRuntimeSettings();
        createMediaDirectory();
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
        playbackController = new PlaybackController(this);

        migrateLegacyScreen();
        loadScreens();
        saveConfig();
        startRenderExecutor();
        viewerTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshViewers, 20L, 20L);
        playbackController.reload();
        playbackController.activate();
        startEnabledSources();
        playbackController.start();
        updateChecker = new ModrinthUpdateChecker(this);
        updateChecker.start();
    }

    @Override
    public void onDisable() {
        if (viewerTask != null) {
            viewerTask.cancel();
        }
        cancelPendingStreamRestart();
        if (debugBossBars != null) {
            debugBossBars.stop();
        }
        if (mediaMtxSetup != null) {
            mediaMtxSetup.shutdown();
        }
        if (playbackController != null) {
            playbackController.stop();
        }
        if (updateChecker != null) {
            updateChecker.stop();
        }
        requestStopAllSources();
        stopAllSources();
        destroyScreens();
        stopRenderExecutor();
    }

    LocalizationManager messages() {
        return messages;
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

    boolean hasScreens() {
        return !screens.isEmpty();
    }

    boolean hasScreen(String id) {
        return screens.containsKey(ScreenDefinition.normalizeId(id));
    }

    List<String> screenIds() {
        return screens.keySet().stream().sorted().toList();
    }

    int screenCount() {
        return screens.size();
    }

    ScreenDefinition screenDefinition(String id) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        return screen == null ? null : screen.definition();
    }

    boolean createScreen(String id, World world, BlockVector location,
                         int width, int height, BlockFace facing) {
        String normalized = ScreenDefinition.normalizeId(id);
        if (!ScreenDefinition.isValidId(normalized) || screens.containsKey(normalized)) {
            return false;
        }
        ScreenDefinition definition = new ScreenDefinition(
                normalized,
                defaultSource(),
                getConfig().getDouble("stream.fps", 8),
                getConfig().getDouble("screen.viewer-distance", 64),
                world.getName(),
                location,
                width,
                height,
                facing,
                getConfig().getBoolean("screen.auto-start", true),
                false,
                ""
        );
        if (!isScreenAllowed(definition)) {
            return false;
        }
        persist(definition);
        saveConfig();
        ManagedScreen screen = register(definition, world);
        screen.showOfflineFrame(messages.plain(
                definition.enabled() ? "screen.connecting" : "screen.stopped"));
        refreshViewers();
        if (definition.enabled()) {
            sourceFor(definition.source()).start();
        }
        return true;
    }

    boolean cloneScreen(String sourceId, String cloneId, World world,
                        BlockVector location, BlockFace facing) {
        ManagedScreen original = screens.get(ScreenDefinition.normalizeId(sourceId));
        String normalizedClone = ScreenDefinition.normalizeId(cloneId);
        if (original == null || !ScreenDefinition.isValidId(normalizedClone)
                || screens.containsKey(normalizedClone)) {
            return false;
        }
        ScreenDefinition source = original.definition();
        ScreenDefinition clone = new ScreenDefinition(
                normalizedClone,
                source.source(),
                source.fps(),
                source.distance(),
                world.getName(),
                location,
                source.width(),
                source.height(),
                facing,
                source.enabled(),
                source.permissionRequired(),
                source.playlist()
        );
        if (!isScreenAllowed(clone)) {
            return false;
        }
        persist(clone);
        saveConfig();
        ManagedScreen screen = register(clone, world);
        screen.showOfflineFrame(messages.plain(
                clone.enabled() ? "screen.connecting" : "screen.stopped"));
        refreshViewers();
        if (clone.enabled()) {
            sourceFor(clone.source()).start();
        }
        return true;
    }

    boolean removeScreen(String id) {
        String normalized = ScreenDefinition.normalizeId(id);
        ManagedScreen screen = screens.get(normalized);
        if (screen == null) {
            return false;
        }
        if (playbackController != null) {
            playbackController.clearRuntime(normalized);
        }
        if (!detachActiveSource(screen)) {
            return false;
        }
        screens.remove(normalized);
        activeSources.remove(normalized);
        frameOnlyScreens.remove(normalized);
        screen.destroy();
        getConfig().set("screens." + normalized, null);
        saveConfig();
        return true;
    }

    boolean startScreen(String id) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null) {
            return false;
        }
        if (!screen.enabled()) {
            updateDefinition(screen, screen.definition().withEnabled(true));
        }
        screen.showOfflineFrame(messages.plain("screen.connecting"));
        if (playbackController != null) {
            playbackController.resetRuntime(screen.id());
        }
        sourceFor(activeSource(screen)).start();
        return true;
    }

    boolean stopScreen(String id) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null) {
            return false;
        }
        SharedMediaSource source = attachedSource(screen);
        if (screen.enabled()) {
            updateDefinition(screen, screen.definition().withEnabled(false));
        }
        screen.showOfflineFrame(messages.plain("screen.stopped"));
        return source == null || source.hasEnabledScreens() || source.stop();
    }

    int startAllScreens() {
        int changed = 0;
        for (String id : screenIds()) {
            ManagedScreen screen = screens.get(id);
            if (screen != null && !screen.enabled()) {
                applyDefinition(screen, screen.definition().withEnabled(true));
                screen.showOfflineFrame(messages.plain("screen.connecting"));
                if (playbackController != null) {
                    playbackController.resetRuntime(screen.id());
                }
                changed++;
            }
        }
        if (changed > 0) {
            saveConfig();
        }
        startEnabledSources();
        return changed;
    }

    int stopAllScreens() {
        int changed = 0;
        for (String id : screenIds()) {
            ManagedScreen screen = screens.get(id);
            if (screen != null && screen.enabled()) {
                applyDefinition(screen, screen.definition().withEnabled(false));
                screen.showOfflineFrame(messages.plain("screen.stopped"));
                changed++;
            }
        }
        if (changed > 0) {
            saveConfig();
        }
        requestStopAllSources();
        stopAllSources();
        return changed;
    }

    boolean setScreenUrl(String id, String url) {
        return setScreenSource(id, ScreenSource.rtmp(url));
    }

    boolean setScreenSource(String id, ScreenSource newScreenSource) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null || newScreenSource == null || !newScreenSource.isValid()) {
            return false;
        }
        if (!newScreenSource.usesRemoteLocation()) {
            try {
                resolveSourceInput(newScreenSource);
            } catch (IOException ignored) {
                return false;
            }
        }
        ScreenDefinition oldDefinition = screen.definition();
        if (oldDefinition.source().equals(newScreenSource)
                && oldDefinition.playlist().isBlank()
                && activeSource(screen).equals(newScreenSource)) {
            return true;
        }
        if (playbackController != null) {
            playbackController.clearRuntime(screen.id());
        }
        if (!detachActiveSource(screen)) {
            return false;
        }
        ScreenDefinition updated = oldDefinition.withSource(newScreenSource).withPlaylist("");
        updateDefinition(screen, updated);
        SharedMediaSource newSource = sourceFor(newScreenSource);
        activeSources.put(screen.id(), newScreenSource);
        newSource.attach(screen);
        screen.showOfflineFrame(messages.plain(
                updated.enabled() ? "screen.connecting" : "screen.stopped"));
        if (updated.enabled()) {
            newSource.start();
        }
        return true;
    }

    boolean setScreenFps(String id, double fps) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null || fps < 0.1 || fps > 20) {
            return false;
        }
        updateDefinition(screen, screen.definition().withFps(fps));
        return true;
    }

    boolean setScreenDistance(String id, double distance) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null || distance < 8 || distance > 1024) {
            return false;
        }
        updateDefinition(screen, screen.definition().withDistance(distance));
        refreshViewers();
        return true;
    }

    boolean setScreenEnabled(String id, boolean enabled) {
        return enabled ? startScreen(id) : stopScreen(id);
    }

    boolean setScreenPermissionRequired(String id, boolean permissionRequired) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null) {
            return false;
        }
        updateDefinition(screen,
                screen.definition().withPermissionRequired(permissionRequired));
        refreshViewers();
        return true;
    }

    Component status() {
        if (screens.isEmpty()) {
            return messages.component("status.no-display");
        }
        long enabled = screens.values().stream().filter(ManagedScreen::enabled).count();
        long maps = screens.values().stream()
                .mapToLong(screen -> (long) screen.width() * screen.height()).sum();
        return messages.component("status.summary",
                "screens", screens.size(),
                "enabled", enabled,
                "sources", sources.size(),
                "maps", maps);
    }

    Component status(String id) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null) {
            return messages.component("status.missing", "screen", id);
        }
        ScreenSource activeSource = activeSource(screen);
        SharedMediaSource source = attachedSource(screen);
        String streamState = source == null ? "stopped" : source.state();
        String streamError = source == null ? "none" : source.lastError();
        ScreenDefinition definition = screen.definition();
        return messages.component("status.line", Map.ofEntries(
                Map.entry("name", definition.id()),
                Map.entry("width", screen.width()),
                Map.entry("height", screen.height()),
                Map.entry("enabled", definition.enabled()),
                Map.entry("stream", messages.state(streamState)),
                Map.entry("stream_error", messages.error(streamError)),
                Map.entry("fps", String.format(Locale.ROOT, "%.2f", screen.effectiveFps())),
                Map.entry("viewers", screen.viewers()),
                Map.entry("received", screen.receivedFrames()),
                Map.entry("rendered", screen.renderedFrames()),
                Map.entry("render_error", messages.error(screen.lastRenderError())),
                Map.entry("world", definition.world()),
                Map.entry("facing", messages.direction(definition.facing())),
                Map.entry("distance", String.format(Locale.ROOT, "%.1f", definition.distance())),
                Map.entry("permission_required", definition.permissionRequired()),
                Map.entry("view_permission", ScreenPermissions.viewNode(definition.id())),
                Map.entry("shared", source == null ? 0 : source.screenCount()),
                Map.entry("source_type", activeSource.type().id()),
                Map.entry("source_value", activeSource.displayValue()),
                Map.entry("playlist", definition.playlist().isBlank()
                        ? messages.plain("common.none") : definition.playlist()),
                Map.entry("playback", playbackDescription(definition.id()))
        ));
    }

    boolean reloadScreenConfig() {
        cancelPendingStreamRestart();
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (playbackController != null) {
            playbackController.stop();
        }
        requestStopAllSources();
        if (!stopAllSources()) {
            getLogger().severe(messages.plain("logs.reload-stop-failed"));
            if (playbackController != null) {
                playbackController.start();
            }
            if (updateChecker != null) {
                updateChecker.start();
            }
            return false;
        }
        stopRenderExecutor();
        try {
            reloadConfig();
            getConfig().options().copyDefaults(true);
            saveConfig();
            reloadRuntimeSettings();
            createMediaDirectory();
            messages.load();
            configureFfmpegLogging();
            migrateLegacyScreen();
            reconcileScreens(readScreens());
            saveConfig();
            startRenderExecutor();
            scheduleViewerRefresh();
            if (playbackController != null) {
                playbackController.reload();
                playbackController.activate();
                playbackController.start();
            }
            startEnabledSources();
            if (debugBossBars != null) {
                debugBossBars.start();
            }
            if (updateChecker != null) {
                updateChecker.start();
            }
            getLogger().info(messages.plain("logs.reload-success"));
            return true;
        } catch (Exception exception) {
            getLogger().severe(messages.plain(
                    "logs.reload-failed", "error", StreamUrlSanitizer.maskError(exception)));
            if (renderExecutor == null) {
                startRenderExecutor();
            }
            startEnabledSources();
            if (playbackController != null) {
                playbackController.start();
            }
            if (updateChecker != null) {
                updateChecker.start();
            }
            return false;
        }
    }

    boolean toggleDebug(Player player) {
        return debugBossBars != null && debugBossBars.toggle(player);
    }

    List<String> playlistIds() {
        return playbackController == null ? List.of() : playbackController.playlistIds();
    }

    List<String> eventIds() {
        return playbackController == null ? List.of() : playbackController.eventIds();
    }

    boolean setScreenPlaylist(String id, String playlistId) {
        return playbackController != null && playbackController.setPlaylist(id, playlistId);
    }

    boolean clearScreenPlaylist(String id) {
        return playbackController != null && playbackController.clearPlaylist(id);
    }

    boolean playScreenEvent(String id, String eventId) {
        return playbackController != null && playbackController.playEvent(id, eventId);
    }

    boolean stopScreenEvent(String id) {
        return playbackController != null && playbackController.stopEvent(id);
    }

    boolean applyPlaylistSetting(String id, String playlistId) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null) {
            return false;
        }
        updateDefinition(screen, screen.definition().withPlaylist(playlistId));
        return true;
    }

    boolean switchRuntimeSource(String id, ScreenSource newScreenSource) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null || newScreenSource == null || !newScreenSource.isValid()) {
            return false;
        }
        if (!newScreenSource.usesRemoteLocation()) {
            try {
                resolveSourceInput(newScreenSource);
            } catch (IOException ignored) {
                return false;
            }
        }
        if (activeSource(screen).equals(newScreenSource)) {
            SharedMediaSource current = sourceFor(newScreenSource);
            frameOnlyScreens.remove(screen.id());
            activeSources.put(screen.id(), newScreenSource);
            current.attach(screen);
            if (screen.enabled()) {
                current.start();
            }
            return true;
        }
        if (!detachActiveSource(screen)) {
            return false;
        }
        activeSources.put(screen.id(), newScreenSource);
        frameOnlyScreens.remove(screen.id());
        SharedMediaSource source = sourceFor(newScreenSource);
        source.attach(screen);
        screen.showOfflineFrame(messages.plain(
                screen.enabled() ? "screen.loading" : "screen.stopped"));
        if (screen.enabled()) {
            source.start();
        }
        return true;
    }

    boolean showRuntimeMessage(String id, String message) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        if (screen == null) {
            return false;
        }
        if (!detachActiveSource(screen)) {
            return false;
        }
        activeSources.remove(screen.id());
        frameOnlyScreens.add(screen.id());
        screen.showOfflineFrame(message);
        return true;
    }

    boolean restoreConfiguredSource(String id) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        return screen != null && switchRuntimeSource(screen.id(), screen.definition().source());
    }

    ScreenSource activeSource(String id) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        return screen == null ? null : activeSource(screen);
    }

    String playbackDescription(String id) {
        return playbackController == null
                ? messages.plain("playback.direct")
                : playbackController.description(id);
    }

    boolean screenHasViewerWithPermission(String id, String permission) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        return screen != null && screen.hasViewerWithPermission(permission);
    }

    int screenViewerCount(String id) {
        ManagedScreen screen = screens.get(ScreenDefinition.normalizeId(id));
        return screen == null ? 0 : screen.viewers();
    }

    List<ScreenSource> mediaFolderSources(String folder, List<SourceType> allowedTypes)
            throws IOException {
        Path mediaDirectory = mediaDirectory();
        Path resolved = mediaDirectory.resolve(folder == null ? "" : folder).normalize();
        if (!resolved.startsWith(mediaDirectory)) {
            throw new IOException("media folder leaves the media directory");
        }
        if (!Files.isDirectory(resolved)) {
            throw new IOException("media folder not found: " + folder);
        }
        List<SourceType> allowed = allowedTypes == null || allowedTypes.isEmpty()
                ? List.of(SourceType.VIDEO, SourceType.IMAGE, SourceType.GIF)
                : allowedTypes;
        try (var stream = Files.list(resolved)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> folderSource(mediaDirectory, path, allowed))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(ScreenSource::value))
                    .toList();
        }
    }

    void beginMediaMtxSetup(Player player, String situation) {
        if (mediaMtxSetup != null) {
            mediaMtxSetup.begin(player, situation);
        }
    }

    boolean applyGeneratedStreamUrl(String streamUrl) {
        ScreenSource previousDefault = defaultSource();
        getConfig().set("stream.url", streamUrl);
        getConfig().set("source.type", SourceType.RTMP.id());
        getConfig().set("source.value", streamUrl);
        for (ManagedScreen screen : screens.values()) {
            if (screen.definition().source().equals(previousDefault)) {
                boolean activeWasPreviousDefault = activeSource(screen).equals(previousDefault);
                ScreenDefinition updated = screen.definition()
                        .withSource(ScreenSource.rtmp(streamUrl));
                screen.updateDefinition(updated);
                if (activeWasPreviousDefault) {
                    activeSources.put(screen.id(), updated.source());
                }
                persist(updated);
            }
        }
        saveConfig();

        cancelPendingStreamRestart();
        requestStopAllSources();
        streamRestartTask = Bukkit.getScheduler().runTaskTimer(
                this, this::finishGeneratedStreamReload, 1L, 5L);
        return true;
    }

    boolean isStreamRestartPending() {
        return streamRestartTask != null;
    }

    DebugSnapshot debugSnapshot() {
        ManagedScreen primary = screens.values().stream()
                .min(Comparator.comparing(ManagedScreen::id))
                .orElse(null);
        SharedMediaSource source = sources.values().stream()
                .sorted(Comparator
                        .comparing(SharedMediaSource::isRunning).reversed()
                        .thenComparing(value -> value.source().key()))
                .findFirst()
                .orElse(null);
        long received = screens.values().stream().mapToLong(ManagedScreen::receivedFrames).sum();
        long rendered = screens.values().stream().mapToLong(ManagedScreen::renderedFrames).sum();
        long replaced = screens.values().stream().mapToLong(ManagedScreen::replacedFrames).sum();
        long buffers = screens.values().stream()
                .mapToLong(ManagedScreen::estimatedImageBufferBytes).sum();
        int viewers = screens.values().stream().mapToInt(ManagedScreen::viewers).sum();
        boolean queued = screens.values().stream().anyMatch(ManagedScreen::frameQueued);
        long lastRender = screens.values().stream()
                .mapToLong(ManagedScreen::lastRenderNanos).max().orElse(0);
        long averageRender = screens.values().stream()
                .mapToLong(ManagedScreen::averageRenderNanos).max().orElse(0);
        String renderError = screens.values().stream()
                .map(ManagedScreen::lastRenderError)
                .filter(error -> !"none".equalsIgnoreCase(error))
                .findFirst().orElse("none");
        return new DebugSnapshot(
                source == null ? "stopped" : source.state(),
                source == null ? "none" : source.lastError(),
                source == null ? 0 : source.sourceWidth(),
                source == null ? 0 : source.sourceHeight(),
                sources.values().stream().mapToLong(SharedMediaSource::reconnects).sum(),
                source == null ? -1 : source.lastFrameAgeMillis(),
                primary == null ? 0 : primary.width(),
                primary == null ? 0 : primary.height(),
                primary == null ? 0 : primary.pixelWidth(),
                primary == null ? 0 : primary.pixelHeight(),
                viewers,
                received,
                rendered,
                replaced,
                queued,
                lastRender,
                averageRender,
                buffers,
                primary == null ? 0 : primary.effectiveFps(),
                renderError,
                screens.size(),
                (int) screens.values().stream().filter(ManagedScreen::enabled).count(),
                sources.size()
        );
    }

    private ManagedScreen register(ScreenDefinition definition, World world) {
        ManagedScreen screen = new ManagedScreen(this, mapEngine, definition, world);
        screens.put(definition.id(), screen);
        activeSources.put(definition.id(), definition.source());
        frameOnlyScreens.remove(definition.id());
        sourceFor(definition.source()).attach(screen);
        return screen;
    }

    private ScreenSource activeSource(ManagedScreen screen) {
        return activeSources.getOrDefault(screen.id(), screen.definition().source());
    }

    private SharedMediaSource attachedSource(ManagedScreen screen) {
        if (frameOnlyScreens.contains(screen.id())) {
            return null;
        }
        return sources.get(sourceKey(activeSource(screen)));
    }

    private SharedMediaSource sourceFor(ScreenSource source) {
        String key = sourceKey(source);
        return sources.computeIfAbsent(key, ignored -> new SharedMediaSource(this, source));
    }

    private boolean detachActiveSource(ManagedScreen screen) {
        if (frameOnlyScreens.remove(screen.id())) {
            return true;
        }
        SharedMediaSource source = sources.get(sourceKey(activeSource(screen)));
        if (source == null) {
            return true;
        }
        if (source.screenCount() == 1 && !source.stop()) {
            return false;
        }
        source.detach(screen);
        removeUnusedSource(source);
        return true;
    }

    private void removeUnusedSource(SharedMediaSource source) {
        if (!source.isUnused()) {
            return;
        }
        source.stop();
        sources.remove(sourceKey(source.source()), source);
    }

    private void updateDefinition(ManagedScreen screen, ScreenDefinition definition) {
        applyDefinition(screen, definition);
        saveConfig();
    }

    private void applyDefinition(ManagedScreen screen, ScreenDefinition definition) {
        screen.updateDefinition(definition);
        persist(definition);
    }

    private void persist(ScreenDefinition definition) {
        String path = "screens." + definition.id();
        FileConfiguration config = getConfig();
        config.set(path + ".source.type", definition.source().type().id());
        config.set(path + ".source.value", definition.source().value());
        config.set(path + ".url", null);
        config.set(path + ".fps", definition.fps());
        config.set(path + ".distance", definition.distance());
        config.set(path + ".world", definition.world());
        config.set(path + ".location", serialize(definition.location()));
        config.set(path + ".width", definition.width());
        config.set(path + ".height", definition.height());
        config.set(path + ".facing", definition.facing().name());
        config.set(path + ".enabled", definition.enabled());
        config.set(path + ".permission-required", definition.permissionRequired());
        config.set(path + ".playlist",
                definition.playlist().isBlank() ? null : definition.playlist());
    }

    private void loadScreens() {
        for (LoadedScreen loaded : readScreens().values()) {
            persist(loaded.definition());
            ManagedScreen screen = register(loaded.definition(), loaded.world());
            showConfiguredState(screen);
        }
    }

    private Map<String, LoadedScreen> readScreens() {
        Map<String, LoadedScreen> loaded = new LinkedHashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection("screens");
        if (section == null) {
            return loaded;
        }
        for (String rawId : section.getKeys(false)) {
            String id = ScreenDefinition.normalizeId(rawId);
            String path = "screens." + rawId;
            if (!ScreenDefinition.isValidId(id)) {
                getLogger().warning(messages.plain(
                        "logs.saved-screen-invalid-name", "screen", rawId));
                continue;
            }
            BlockVector location = deserialize(getConfig().getString(path + ".location", ""));
            String worldName = getConfig().getString(path + ".world", "");
            World world = Bukkit.getWorld(worldName);
            BlockFace facing = parseFace(getConfig().getString(path + ".facing", "NORTH"));
            ScreenSource screenSource = readSource(path);
            ScreenDefinition definition = new ScreenDefinition(
                    id,
                    screenSource,
                    getConfig().getDouble(path + ".fps",
                            getConfig().getDouble("stream.fps", 8)),
                    getConfig().getDouble(path + ".distance",
                            getConfig().getDouble("screen.viewer-distance", 64)),
                    worldName,
                    location,
                    getConfig().getInt(path + ".width", 7),
                    getConfig().getInt(path + ".height", 4),
                    facing,
                    getConfig().getBoolean(path + ".enabled",
                            getConfig().getBoolean("screen.auto-start", true)),
                    getConfig().getBoolean(path + ".permission-required", false),
                    getConfig().getString(path + ".playlist", "")
            );
            if (world == null || facing == null || !isScreenAllowed(definition)
                    || !definition.source().isValid()) {
                getLogger().warning(messages.plain(
                        "logs.saved-screen-invalid-name", "screen", rawId));
                continue;
            }
            loaded.put(id, new LoadedScreen(definition, world));
        }
        return loaded;
    }

    private void reconcileScreens(Map<String, LoadedScreen> desiredScreens) {
        Map<String, LoadedScreen> remaining = new LinkedHashMap<>(desiredScreens);
        sources.clear();
        activeSources.clear();
        frameOnlyScreens.clear();

        for (Map.Entry<String, ManagedScreen> entry : screens.entrySet()) {
            String id = entry.getKey();
            ManagedScreen current = entry.getValue();
            LoadedScreen desired = remaining.remove(id);

            if (desired == null) {
                persist(current.definition());
                current.prepareViewerResync();
                current.reloadRenderingSettings();
                activeSources.put(id, current.definition().source());
                sourceFor(current.definition().source()).attach(current);
                showConfiguredState(current);
                getLogger().warning(messages.plain(
                        "logs.reload-screen-preserved", "screen", id));
                continue;
            }

            ScreenDefinition updated = desired.definition();
            if (!current.definition().hasSameDisplayGeometry(updated)) {
                updated = current.definition().withRuntimeSettingsFrom(updated);
                persist(updated);
                getLogger().warning(messages.plain(
                        "logs.reload-geometry-preserved", "screen", id));
            }
            current.prepareViewerResync();
            current.updateDefinition(updated);
            current.reloadRenderingSettings();
            activeSources.put(id, updated.source());
            sourceFor(updated.source()).attach(current);
            showConfiguredState(current);
        }

        for (LoadedScreen desired : remaining.values()) {
            ManagedScreen screen = register(desired.definition(), desired.world());
            showConfiguredState(screen);
        }
    }

    private void showConfiguredState(ManagedScreen screen) {
        screen.showOfflineFrame(messages.plain(
                screen.enabled() ? "screen.waiting" : "screen.stopped"));
    }

    private void migrateLegacyScreen() {
        ConfigurationSection existing = getConfig().getConfigurationSection("screens");
        if (existing != null && !existing.getKeys(false).isEmpty()) {
            return;
        }
        if (getConfig().getBoolean("migration.single-screen-completed", false)
                || !getConfig().getBoolean("screen.configured", false)) {
            return;
        }
        BlockVector first = deserialize(getConfig().getString("screen.corner-a", ""));
        BlockVector second = deserialize(getConfig().getString("screen.corner-b", ""));
        BlockFace facing = parseFace(getConfig().getString("screen.facing", "NORTH"));
        String worldName = getConfig().getString("screen.world", "");
        if (first == null || second == null || facing == null || Bukkit.getWorld(worldName) == null) {
            getLogger().warning(messages.plain("logs.saved-screen-invalid"));
            return;
        }
        ScreenDefinition migrated = new ScreenDefinition(
                "main",
                defaultSource(),
                getConfig().getDouble("stream.fps", 8),
                getConfig().getDouble("screen.viewer-distance", 64),
                worldName,
                first,
                ScreenPolicy.width(first, second, facing),
                ScreenPolicy.height(first, second),
                facing,
                getConfig().getBoolean("screen.auto-start", true),
                false,
                ""
        );
        if (!isScreenAllowed(migrated)) {
            getLogger().warning(messages.plain("logs.saved-screen-too-large"));
            return;
        }
        persist(migrated);
        getConfig().set("migration.single-screen-completed", true);
        getConfig().set("screen.configured", false);
        saveConfig();
        getLogger().info(messages.plain("logs.legacy-migrated"));
    }

    private void refreshViewers() {
        List<ViewerPosition> onlinePlayers = Bukkit.getOnlinePlayers().stream()
                .map(ViewerPosition::capture)
                .toList();
        for (ManagedScreen screen : screens.values()) {
            screen.refreshViewers(onlinePlayers);
        }
    }

    private void startEnabledSources() {
        for (SharedMediaSource source : sources.values()) {
            if (source.hasEnabledScreens()) {
                source.start();
            }
        }
    }

    private void requestStopAllSources() {
        for (SharedMediaSource source : sources.values()) {
            source.requestStop();
        }
    }

    private boolean stopAllSources() {
        boolean stopped = true;
        for (SharedMediaSource source : new ArrayList<>(sources.values())) {
            stopped &= source.stop();
        }
        return stopped;
    }

    private void destroyScreens() {
        for (ManagedScreen screen : new ArrayList<>(screens.values())) {
            screen.destroy();
        }
        screens.clear();
        activeSources.clear();
        frameOnlyScreens.clear();
    }

    private void finishGeneratedStreamReload() {
        if (sources.values().stream().anyMatch(source -> !source.isTerminated())) {
            return;
        }
        cancelPendingStreamRestart();
        stopRenderExecutor();
        reconcileScreens(readScreens());
        saveConfig();
        startRenderExecutor();
        scheduleViewerRefresh();
        startEnabledSources();
    }

    private void cancelPendingStreamRestart() {
        BukkitTask task = streamRestartTask;
        streamRestartTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void scheduleViewerRefresh() {
        Bukkit.getScheduler().runTask(this, this::refreshViewers);
    }

    private void startRenderExecutor() {
        if (renderExecutor != null) {
            return;
        }
        renderExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LuigiScreen-MapEngine");
            thread.setDaemon(true);
            return thread;
        });
        renderExecutor.scheduleWithFixedDelay(() -> {
            long now = System.nanoTime();
            for (ManagedScreen screen : screens.values()) {
                try {
                    screen.flushPendingFrame(now);
                } catch (Throwable throwable) {
                    getLogger().severe(messages.plain(
                            "logs.render-failed-screen",
                            "screen", screen.id(),
                            "error", StreamUrlSanitizer.maskError(throwable)));
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
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

    private boolean isScreenAllowed(ScreenDefinition definition) {
        return definition.location() != null
                && definition.facing() != null
                && ScreenPolicy.isSizeAllowed(
                definition.location(),
                definition.secondCorner(),
                definition.facing(),
                maxScreenWidth(),
                maxScreenHeight(),
                maxTotalMaps());
    }

    private ScreenSource defaultSource() {
        String builtInDefault = "rtmp://127.0.0.1:55556/screen";
        String legacyUrl = getConfig().getString(
                "stream.url", builtInDefault);
        String type = getConfig().getString("source.type", SourceType.RTMP.id());
        String value = getConfig().getString("source.value", legacyUrl);
        if (SourceType.parse(type) == SourceType.RTMP
                && builtInDefault.equals(value)
                && !builtInDefault.equals(legacyUrl)) {
            return ScreenSource.rtmp(legacyUrl);
        }
        ScreenSource source = ScreenSource.parse(type, value);
        return source != null && source.isValid() ? source : ScreenSource.rtmp(legacyUrl);
    }

    private ScreenSource readSource(String path) {
        String type = getConfig().getString(path + ".source.type");
        String value = getConfig().getString(path + ".source.value");
        ScreenSource source = type == null ? null : ScreenSource.parse(type, value);
        if (source != null) {
            return source;
        }
        String legacyUrl = getConfig().getString(path + ".url");
        return legacyUrl == null ? defaultSource() : ScreenSource.rtmp(legacyUrl);
    }

    String resolveSourceInput(ScreenSource source) throws IOException {
        if (source.usesRemoteLocation()) {
            return source.value();
        }
        Path configured = Path.of(source.value());
        if (configured.isAbsolute()) {
            if (!allowAbsoluteMediaPaths) {
                throw new IOException("absolute media paths are disabled");
            }
            Path normalized = configured.normalize();
            if (!Files.isRegularFile(normalized)) {
                throw new IOException("media file not found: " + configured);
            }
            return normalized.toString();
        }
        Path mediaDirectory = mediaDirectory();
        Path resolved = mediaDirectory.resolve(configured).normalize();
        if (!resolved.startsWith(mediaDirectory)) {
            throw new IOException("media path leaves the media directory");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("media file not found: " + configured);
        }
        return resolved.toString();
    }

    private static ScreenSource folderSource(
            Path mediaDirectory, Path file, List<SourceType> allowedTypes) {
        SourceType type = inferLocalSourceType(file);
        if (type == null || !allowedTypes.contains(type)) {
            return null;
        }
        String relative = mediaDirectory.relativize(file)
                .toString()
                .replace('\\', '/');
        return new ScreenSource(type, relative);
    }

    private static SourceType inferLocalSourceType(Path file) {
        String name = file.getFileName() == null ? ""
                : file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return switch (extension) {
            case "mp4", "mov", "mkv", "webm" -> SourceType.VIDEO;
            case "png", "jpg", "jpeg", "webp" -> SourceType.IMAGE;
            case "gif" -> SourceType.GIF;
            default -> null;
        };
    }

    private Path mediaDirectory() {
        Path current = configuredMediaDirectory;
        if (current != null) {
            return current;
        }
        return getDataFolder().toPath().resolve("media").toAbsolutePath().normalize();
    }

    String mediaDirectoryDisplay() {
        return "plugins/" + getName() + "/"
                + getConfig().getString("sources.media-directory", "media");
    }

    private void createMediaDirectory() {
        try {
            Files.createDirectories(mediaDirectory());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create LuigiScreen media directory",
                    exception);
        }
    }

    private void reloadRuntimeSettings() {
        Path dataFolder = getDataFolder().toPath().toAbsolutePath().normalize();
        String configured = getConfig().getString("sources.media-directory", "media");
        try {
            Path value = Path.of(configured == null || configured.isBlank()
                    ? "media" : configured.trim());
            configuredMediaDirectory = (value.isAbsolute()
                    ? value : dataFolder.resolve(value)).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            configuredMediaDirectory = dataFolder.resolve("media").normalize();
        }
        allowAbsoluteMediaPaths = getConfig().getBoolean(
                "sources.allow-absolute-paths", false);
    }

    private static String sourceKey(ScreenSource source) {
        return ScreenSourcePolicy.key(source);
    }

    private void configureFfmpegLogging() {
        FFmpegLogCallback.set();
        String configured = getConfig().getString("logging.ffmpeg-level", "quiet");
        String level = configured == null ? "quiet"
                : configured.trim().toLowerCase(Locale.ROOT);
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
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && updateChecker != null) {
                updateChecker.notifyPlayer(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        removeViewer(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, this::refreshViewers, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeViewer(event.getPlayer().getUniqueId());
        if (debugBossBars != null) {
            debugBossBars.remove(event.getPlayer());
        }
        Bukkit.getScheduler().runTask(this, this::refreshViewers);
    }

    private void removeViewer(UUID playerId) {
        for (ManagedScreen screen : screens.values()) {
            screen.removeViewer(playerId);
        }
    }

    private static String serialize(BlockVector vector) {
        return vector.getBlockX() + "," + vector.getBlockY() + "," + vector.getBlockZ();
    }

    private static BlockVector deserialize(String value) {
        String[] parts = value == null ? new String[0] : value.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockVector(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BlockFace parseFace(String value) {
        try {
            return BlockFace.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record LoadedScreen(ScreenDefinition definition, World world) {
    }
}
