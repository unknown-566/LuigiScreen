package cz.luigismp.screen;

import org.bukkit.Bukkit;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

final class StudioWebSnapshot {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE HH:mm");
    private final LuigiScreenPlugin plugin;

    StudioWebSnapshot(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
    }

    Map<String, Object> build(StudioWebSecurity.Session session, int draftChanges,
                              boolean externalChanges) {
        List<Map<String, Object>> screens = screens();
        List<Map<String, Object>> media = media();
        List<Map<String, Object>> playlists = playlists();
        List<Map<String, Object>> events = events();
        List<Map<String, Object>> schedules = schedules();
        List<Map<String, Object>> alerts = alerts(screens, media, playlists);
        DebugSnapshot debug = plugin.debugSnapshot();

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", System.currentTimeMillis());
        root.put("revision", revision());
        root.put("server", server(debug, screens, alerts));
        root.put("session", session(session, draftChanges, externalChanges));
        root.put("screens", screens);
        root.put("media", media);
        root.put("playlists", playlists);
        root.put("events", events);
        root.put("groups", groups());
        root.put("schedules", schedules);
        root.put("alerts", alerts);
        root.put("upcoming", schedules.stream().limit(8).toList());
        root.put("audit", plugin.studio().audit().stream().limit(30).toList());
        root.put("emergency", plugin.studio().emergency());
        root.put("web", webSummary());
        root.put("config", configSummary());
        return root;
    }

    Map<String, Object> buildLive(StudioWebSecurity.Session session, int draftChanges,
                                  boolean externalChanges) {
        List<Map<String, Object>> screens = screens();
        List<Map<String, Object>> screenAlerts = alerts(screens, List.of(), List.of());
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", System.currentTimeMillis());
        root.put("revision", revision());
        root.put("server", server(plugin.debugSnapshot(), screens, screenAlerts));
        root.put("session", session(session, draftChanges, externalChanges));
        root.put("screens", screens);
        root.put("emergency", plugin.studio().emergency());
        return root;
    }

    private Map<String, Object> session(StudioWebSecurity.Session session, int draftChanges,
                                        boolean externalChanges) {
        return Map.of(
                "actor", session.actor(),
                "csrf", session.csrf(),
                "expiresAt", session.expiresAtMillis(),
                "capabilities", session.capabilities(),
                "draftChanges", draftChanges,
                "externalChanges", externalChanges);
    }

    private long revision() {
        long revision = modified(plugin.getDataFolder().toPath().resolve("config.yml"));
        revision = 31 * revision + modified(plugin.getDataFolder().toPath().resolve("studio.yml"));
        for (MediaEntry entry : plugin.studio().media()) {
            revision = 31 * revision + entry.modifiedMillis();
            revision = 31 * revision + entry.sizeBytes();
        }
        return revision;
    }

    private long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private Map<String, Object> server(DebugSnapshot debug,
                                       List<Map<String, Object>> screens,
                                       List<Map<String, Object>> alerts) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        int active = (int) screens.stream().filter(screen ->
                Boolean.TRUE.equals(screen.get("enabled"))).count();
        int viewers = screens.stream().mapToInt(screen -> (int) screen.get("viewers")).sum();
        int broadcasts = (int) screens.stream().filter(screen ->
                !"direct".equals(screen.get("mode")) || "running".equals(screen.get("state")))
                .count();
        String health = alerts.stream().anyMatch(alert -> "critical".equals(alert.get("level")))
                ? "critical" : alerts.isEmpty() ? "healthy" : "warnings";
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("name", plugin.getConfig().getString(
                "web-studio.server-name", Bukkit.getServer().getName()));
        result.put("version", plugin.getPluginMeta().getVersion());
        result.put("minecraft", Bukkit.getMinecraftVersion());
        result.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        result.put("maxPlayers", Bukkit.getMaxPlayers());
        result.put("tps", round(Bukkit.getTPS()[0], 2));
        result.put("mspt", round(Bukkit.getAverageTickTime(), 2));
        result.put("activeScreens", active);
        result.put("totalScreens", screens.size());
        result.put("viewers", viewers);
        result.put("broadcasts", broadcasts);
        result.put("sources", debug.sourceCount());
        result.put("health", health);
        result.put("warnings", alerts.size());
        result.put("ffmpeg", debug.streamState());
        result.put("mapEngine", Bukkit.getPluginManager().isPluginEnabled("MapEngine"));
        result.put("memoryUsed", used);
        result.put("memoryMax", runtime.maxMemory());
        result.put("receivedFrames", debug.receivedFrames());
        result.put("renderedFrames", debug.renderedFrames());
        result.put("droppedFrames", debug.replacedFrames());
        result.put("effectiveFps", round(debug.effectiveFps(), 2));
        result.put("renderNanos", debug.averageRenderNanos());
        return result;
    }

    private List<Map<String, Object>> screens() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : plugin.screenIds()) {
            ScreenDefinition definition = plugin.screenDefinition(id);
            ScreenHealth health = plugin.screenHealth(id);
            PlaybackSnapshot playback = plugin.playbackSnapshot(id);
            ScreenSource source = plugin.activeSource(id);
            StudioStatistics statistics = plugin.studio().screenStatistics(id);
            if (definition == null || health == null) continue;
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("enabled", definition.enabled());
            item.put("state", health.state());
            item.put("error", safeError(health.error()));
            item.put("world", definition.world());
            item.put("x", definition.location().getBlockX());
            item.put("y", definition.location().getBlockY());
            item.put("z", definition.location().getBlockZ());
            item.put("facing", definition.facing().name().toLowerCase());
            item.put("width", definition.width());
            item.put("height", definition.height());
            item.put("maps", definition.width() * definition.height());
            item.put("viewers", health.viewers());
            item.put("targetFps", round(health.targetFps(), 2));
            item.put("actualFps", round(health.effectiveFps(), 2));
            item.put("droppedFrames", health.droppedFrames());
            item.put("renderedFrames", health.renderedFrames());
            item.put("receivedFrames", health.receivedFrames());
            item.put("renderMillis", round(health.averageRenderNanos() / 1_000_000.0, 2));
            item.put("reconnects", health.reconnects());
            item.put("lastFrameAge", health.lastFrameAgeMillis());
            item.put("distance", definition.distance());
            item.put("permissionRequired", definition.permissionRequired());
            item.put("viewPermission", ScreenPermissions.viewNode(id));
            item.put("sourceType", source.type().id());
            item.put("source", source.displayValue());
            item.put("playlist", definition.playlist());
            item.put("mode", playback.mode());
            item.put("current", playback.current());
            item.put("next", playback.next());
            item.put("controller", playback.controller());
            item.put("reason", playback.reason());
            item.put("remaining", playback.remainingMillis());
            item.put("paused", playback.paused());
            item.put("repeat", playback.repeat());
            item.put("queue", playback.queue());
            item.put("history", playback.history());
            item.put("sharedScreens", health.sharedScreens());
            long previewVersion = plugin.screenPreviewVersion(id);
            item.put("preview", previewVersion < 1 ? "" : "/api/preview?screen=" + url(id)
                    + "&v=" + previewVersion);
            item.put("statistics", statistics(statistics));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> media() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MediaEntry entry : plugin.studio().media()) {
            StudioStatistics statistics = plugin.studio().mediaStatistics(entry.id());
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.id());
            item.put("name", entry.path().getFileName().toString());
            item.put("type", entry.type() == null ? "unknown" : entry.type().id());
            item.put("size", entry.sizeBytes());
            item.put("modified", entry.modifiedMillis());
            item.put("width", entry.width());
            item.put("height", entry.height());
            item.put("valid", entry.valid());
            item.put("problem", entry.problem());
            item.put("references", entry.references());
            item.put("thumbnail", entry.thumbnail() == null ? ""
                    : "/api/thumbnail?id=" + url(entry.id()));
            item.put("statistics", statistics(statistics));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> playlists() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : plugin.playlistIds()) {
            List<PlaybackItemView> entries = plugin.playlistItems(id);
            result.add(Map.of(
                    "id", id,
                    "items", entries.stream().map(this::playbackItem).toList(),
                    "valid", !entries.isEmpty()));
        }
        return result;
    }

    private List<Map<String, Object>> events() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : plugin.eventIds()) {
            List<PlaybackItemView> entries = plugin.eventItems(id);
            result.add(Map.of(
                    "id", id,
                    "steps", entries.stream().map(this::playbackItem).toList(),
                    "valid", !entries.isEmpty()));
        }
        return result;
    }

    private Map<String, Object> playbackItem(PlaybackItemView item) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.id());
        result.put("type", item.type());
        result.put("value", item.value());
        result.put("weight", item.weight());
        result.put("probability", round(item.probability(), 2));
        result.put("duration", item.durationMillis());
        result.put("cooldown", item.cooldownMillis());
        result.put("enabled", item.enabled());
        result.put("conditions", item.conditions());
        return result;
    }

    private List<Map<String, Object>> groups() {
        return plugin.studio().groupIds().stream().map(id -> Map.<String, Object>of(
                "id", id, "screens", plugin.studio().groupScreens(id))).toList();
    }

    private List<Map<String, Object>> schedules() {
        ZonedDateTime now = ZonedDateTime.now();
        return plugin.studio().schedules().stream()
                .sorted(Comparator.comparing(schedule -> nextRun(schedule, now)))
                .map(schedule -> {
                    ZonedDateTime next = nextRun(schedule, now);
                    LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                    item.put("id", schedule.id());
                    item.put("enabled", schedule.enabled());
                    item.put("days", schedule.days().stream().map(day -> day.name().toLowerCase()).toList());
                    item.put("time", schedule.time().toString());
                    item.put("target", schedule.target());
                    item.put("action", schedule.action());
                    item.put("value", schedule.value());
                    item.put("priority", schedule.priority());
                    item.put("conflict", schedule.conflict());
                    item.put("nextRun", next.toInstant().toEpochMilli());
                    item.put("nextLabel", TIME.format(next));
                    item.put("conflicts", plugin.studio().scheduleConflicts(schedule.id()));
                    return (Map<String, Object>) item;
                }).toList();
    }

    private ZonedDateTime nextRun(StudioSchedule schedule, ZonedDateTime now) {
        for (int offset = 0; offset < 8; offset++) {
            ZonedDateTime candidate = now.plusDays(offset).with(schedule.time());
            if (schedule.days().contains(candidate.getDayOfWeek())
                    && candidate.isAfter(now)) return candidate;
        }
        return now.plusWeeks(1).with(TemporalAdjusters.next(schedule.days().iterator().next()))
                .with(schedule.time());
    }

    private List<Map<String, Object>> alerts(List<Map<String, Object>> screens,
                                             List<Map<String, Object>> media,
                                             List<Map<String, Object>> playlists) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> screen : screens) {
            String state = String.valueOf(screen.get("state"));
            String error = String.valueOf(screen.get("error"));
            if (Boolean.TRUE.equals(screen.get("enabled"))
                    && !List.of("running", "waiting", "frame", "paused").contains(state)) {
                result.add(alert("critical", "Screen " + screen.get("id") + " is " + state,
                        error, "screens", String.valueOf(screen.get("id"))));
            } else if (!"none".equals(error)) {
                result.add(alert("warning", "Screen " + screen.get("id") + " reported an error",
                        error, "diagnostics", String.valueOf(screen.get("id"))));
            }
        }
        media.stream().filter(item -> !Boolean.TRUE.equals(item.get("valid"))).limit(10)
                .forEach(item -> result.add(alert("warning", "Media is unavailable",
                        item.get("id") + ": " + item.get("problem"), "media",
                        String.valueOf(item.get("id")))));
        playlists.stream().filter(item -> !Boolean.TRUE.equals(item.get("valid")))
                .forEach(item -> result.add(alert("warning", "Playlist is empty",
                        String.valueOf(item.get("id")), "playlists",
                        String.valueOf(item.get("id")))));
        return result;
    }

    private Map<String, Object> alert(String level, String title, String detail,
                                      String target, String id) {
        return Map.of("level", level, "title", title, "detail", detail,
                "target", target, "id", id);
    }

    private Map<String, Object> configSummary() {
        return Map.of(
                "language", plugin.getConfig().getString("language", "en"),
                "adaptiveFps", plugin.getConfig().getBoolean("performance.adaptive-fps", true),
                "pauseWithoutViewers", plugin.getConfig().getBoolean(
                        "performance.pause-rendering-without-viewers", true),
                "maxMapUpdates", plugin.getConfig().getInt(
                        "performance.max-map-updates-per-second", 400),
                "mediaDirectory", plugin.mediaDirectoryDisplay());
    }

    private Map<String, Object> webSummary() {
        StudioWebServer web = plugin.webStudio();
        if (web == null) {
            return Map.of("enabled", false, "running", false, "lanReady", false);
        }
        return web.accessSummary();
    }

    private Map<String, Object> statistics(StudioStatistics value) {
        return Map.of(
                "plays", value.plays(),
                "plannedSeconds", value.plannedSeconds(),
                "viewerSeconds", value.viewerSeconds(),
                "averageViewers", round(value.averageViewers(), 2),
                "skips", value.skips(),
                "failures", value.failures());
    }

    private static String safeError(String error) {
        if (error == null || error.isBlank() || "none".equalsIgnoreCase(error)) return "none";
        return StreamUrlSanitizer.mask(error);
    }

    private static String url(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static double round(double value, int places) {
        if (!Double.isFinite(value)) return 0;
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
