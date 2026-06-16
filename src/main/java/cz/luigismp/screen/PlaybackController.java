package cz.luigismp.screen;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

final class PlaybackController {

    private static final long RETRY_MILLIS = 5_000;

    private final LuigiScreenPlugin plugin;
    private final Random random = new Random();
    private final Map<String, RuntimeState> states = new ConcurrentHashMap<>();
    private Map<String, PlaylistDefinition> playlists = Map.of();
    private Map<String, EventDefinition> events = Map.of();
    private BukkitTask task;

    PlaybackController(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        playlists = readPlaylists();
        events = readEvents();
        states.clear();
    }

    void start() {
        if (task != null) {
            return;
        }
        long tickSeconds = Math.max(1, plugin.getConfig().getLong("playback.tick-seconds", 1));
        task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, 20L, tickSeconds * 20L);
    }

    void stop() {
        BukkitTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }

    List<String> playlistIds() {
        return playlists.keySet().stream().sorted().toList();
    }

    List<String> eventIds() {
        return events.keySet().stream().sorted().toList();
    }

    boolean setPlaylist(String screenId, String playlistId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        String playlist = ScreenDefinition.normalizeId(playlistId);
        if (plugin.screenDefinition(screen) == null || !playlists.containsKey(playlist)) {
            return false;
        }
        if (!plugin.applyPlaylistSetting(screen, playlist)) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        state.activeEvent = null;
        state.nextSwitchMillis = 0;
        tickScreen(screen, System.currentTimeMillis());
        return true;
    }

    boolean clearPlaylist(String screenId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        if (plugin.screenDefinition(screen) == null) {
            return false;
        }
        if (!plugin.applyPlaylistSetting(screen, "")) {
            return false;
        }
        states.remove(screen);
        return plugin.restoreConfiguredSource(screen);
    }

    boolean playEvent(String screenId, String eventId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        String event = ScreenDefinition.normalizeId(eventId);
        if (plugin.screenDefinition(screen) == null || !events.containsKey(event)) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        state.activeEvent = new EventPlayback(event);
        state.nextSwitchMillis = 0;
        tickScreen(screen, System.currentTimeMillis());
        return true;
    }

    boolean stopEvent(String screenId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        if (plugin.screenDefinition(screen) == null) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        state.activeEvent = null;
        state.nextSwitchMillis = 0;
        ScreenDefinition definition = plugin.screenDefinition(screen);
        if (definition == null) {
            return false;
        }
        if (definition.playlist().isBlank()) {
            return plugin.restoreConfiguredSource(screen);
        }
        tickScreen(screen, System.currentTimeMillis());
        return true;
    }

    void clearRuntime(String screenId) {
        states.remove(ScreenDefinition.normalizeId(screenId));
    }

    void resetRuntime(String screenId) {
        RuntimeState state = states.get(ScreenDefinition.normalizeId(screenId));
        if (state != null) {
            state.nextSwitchMillis = 0;
        }
    }

    String description(String screenId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        ScreenDefinition definition = plugin.screenDefinition(screen);
        RuntimeState state = states.get(screen);
        if (state != null && state.activeEvent != null) {
            return plugin.messages().plain("playback.event-status",
                    "event", state.activeEvent.eventId,
                    "item", state.currentItemLabel());
        }
        if (definition != null && !definition.playlist().isBlank()) {
            return plugin.messages().plain("playback.playlist-status",
                    "playlist", definition.playlist(),
                    "item", state == null ? plugin.messages().plain("common.none")
                            : state.currentItemLabel());
        }
        return plugin.messages().plain("playback.direct");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (String screen : plugin.screenIds()) {
            tickScreen(screen, now);
        }
    }

    private void tickScreen(String screenId, long now) {
        ScreenDefinition definition = plugin.screenDefinition(screenId);
        if (definition == null || !definition.enabled()) {
            return;
        }
        RuntimeState state = states.computeIfAbsent(screenId, ignored -> new RuntimeState());
        if (state.activeEvent != null) {
            if (now >= state.nextSwitchMillis) {
                tickEvent(screenId, definition, state, now);
            }
            return;
        }
        if (definition.playlist().isBlank()) {
            return;
        }
        if (now >= state.nextSwitchMillis) {
            tickPlaylist(screenId, definition, state, now);
        }
    }

    private void tickEvent(
            String screenId, ScreenDefinition definition, RuntimeState state, long now) {
        EventDefinition event = events.get(state.activeEvent.eventId);
        if (event == null || state.activeEvent.index >= event.sequence().size()) {
            finishEvent(screenId, definition, state, now);
            return;
        }
        PlaybackItem item = event.sequence().get(state.activeEvent.index++);
        if (!playItem(screenId, item, state, now)) {
            state.nextSwitchMillis = now + RETRY_MILLIS;
        }
    }

    private void finishEvent(
            String screenId, ScreenDefinition definition, RuntimeState state, long now) {
        state.activeEvent = null;
        state.nextSwitchMillis = 0;
        if (definition.playlist().isBlank()) {
            plugin.restoreConfiguredSource(screenId);
            state.currentItem = plugin.messages().plain("playback.direct");
            state.nextSwitchMillis = Long.MAX_VALUE;
            return;
        }
        tickPlaylist(screenId, definition, state, now);
    }

    private void tickPlaylist(
            String screenId, ScreenDefinition definition, RuntimeState state, long now) {
        PlaylistDefinition playlist = playlists.get(definition.playlist());
        if (playlist == null || playlist.items().isEmpty()) {
            plugin.restoreConfiguredSource(screenId);
            state.currentItem = plugin.messages().plain("playback.fallback");
            state.nextSwitchMillis = now + RETRY_MILLIS;
            return;
        }
        PlaybackItem item = selectItem(screenId, playlist, state, now);
        if (item == null) {
            plugin.restoreConfiguredSource(screenId);
            state.currentItem = plugin.messages().plain("playback.fallback");
            state.nextSwitchMillis = now + RETRY_MILLIS;
            return;
        }
        if (!playItem(screenId, item, state, now)) {
            state.nextSwitchMillis = now + RETRY_MILLIS;
        }
    }

    private PlaybackItem selectItem(
            String screenId, PlaylistDefinition playlist, RuntimeState state, long now) {
        List<PlaybackItem> eligible = playlist.items().stream()
                .filter(item -> item.conditions().matches(plugin, screenId))
                .filter(item -> state.cooldownUntilMillis(item.key()) <= now)
                .toList();
        if (eligible.size() > 1) {
            eligible = eligible.stream()
                    .filter(item -> !item.key().equals(state.currentItem))
                    .toList();
        }
        int totalWeight = eligible.stream().mapToInt(PlaybackItem::weight).sum();
        if (totalWeight < 1) {
            return null;
        }
        int roll = random.nextInt(totalWeight);
        for (PlaybackItem item : eligible) {
            roll -= item.weight();
            if (roll < 0) {
                return item;
            }
        }
        return eligible.getLast();
    }

    private boolean playItem(String screenId, PlaybackItem item, RuntimeState state, long now) {
        Optional<ResolvedPlayback> resolved = item.resolve(plugin, random);
        if (resolved.isEmpty()) {
            return false;
        }
        ResolvedPlayback playback = resolved.get();
        boolean switched = playback.source() == null
                ? plugin.showRuntimeMessage(screenId, playback.message())
                : plugin.switchRuntimeSource(screenId, playback.source());
        if (!switched) {
            return false;
        }
        state.currentItem = item.key();
        state.currentLabel = item.id();
        state.nextSwitchMillis = now + playback.durationMillis();
        if (item.cooldownMillis() > 0) {
            state.cooldowns.put(item.key(), now + item.cooldownMillis());
        }
        return true;
    }

    private Map<String, PlaylistDefinition> readPlaylists() {
        Map<String, PlaylistDefinition> result = new LinkedHashMap<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("playlists");
        if (root == null) {
            return result;
        }
        for (String rawId : root.getKeys(false)) {
            String id = ScreenDefinition.normalizeId(rawId);
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (!ScreenDefinition.isValidId(id) || section == null) {
                continue;
            }
            List<PlaybackItem> items = readItems(id, section, "items");
            if (!items.isEmpty()) {
                result.put(id, new PlaylistDefinition(id, items));
            }
        }
        return result;
    }

    private Map<String, EventDefinition> readEvents() {
        Map<String, EventDefinition> result = new LinkedHashMap<>();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("events");
        if (root == null) {
            return result;
        }
        for (String rawId : root.getKeys(false)) {
            String id = ScreenDefinition.normalizeId(rawId);
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (!ScreenDefinition.isValidId(id) || section == null) {
                continue;
            }
            List<PlaybackItem> sequence = readItems(id, section, "sequence");
            if (!sequence.isEmpty()) {
                result.put(id, new EventDefinition(
                        id, section.getInt("priority", 50), sequence));
            }
        }
        return result;
    }

    private List<PlaybackItem> readItems(
            String ownerId, ConfigurationSection section, String childName) {
        List<PlaybackItem> items = new ArrayList<>();
        ConfigurationSection itemRoot = section.getConfigurationSection(childName);
        if (itemRoot == null) {
            return items;
        }
        long defaultDuration = DurationParser.parseMillis(
                section.get("default-duration"),
                DurationParser.parseMillis(
                        plugin.getConfig().get("playback.default-duration"), 30_000));
        for (String rawItemId : itemRoot.getKeys(false)) {
            ConfigurationSection itemSection = itemRoot.getConfigurationSection(rawItemId);
            if (itemSection == null) {
                continue;
            }
            PlaybackItem item = PlaybackItem.fromSection(
                    ownerId, rawItemId, itemSection, defaultDuration);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private record PlaylistDefinition(String id, List<PlaybackItem> items) {
    }

    private record EventDefinition(String id, int priority, List<PlaybackItem> sequence) {
    }

    private static final class EventPlayback {
        private final String eventId;
        private int index;

        private EventPlayback(String eventId) {
            this.eventId = eventId;
        }
    }

    private static final class RuntimeState {
        private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
        private EventPlayback activeEvent;
        private String currentItem = "";
        private String currentLabel = "";
        private long nextSwitchMillis;

        private long cooldownUntilMillis(String itemKey) {
            return cooldowns.getOrDefault(itemKey, 0L);
        }

        private String currentItemLabel() {
            return currentLabel == null || currentLabel.isBlank() ? "none" : currentLabel;
        }
    }

    private record PlaybackItem(
            String ownerId,
            String id,
            String type,
            ScreenSource source,
            String folder,
            List<SourceType> mediaTypes,
            String message,
            int weight,
            long durationMillis,
            long cooldownMillis,
            PlaybackConditions conditions
    ) {
        private String key() {
            return ownerId + ":" + id;
        }

        private Optional<ResolvedPlayback> resolve(LuigiScreenPlugin plugin, Random random) {
            if (source != null) {
                return Optional.of(new ResolvedPlayback(source, null, durationMillis));
            }
            if (folder != null && !folder.isBlank()) {
                try {
                    List<ScreenSource> options = plugin.mediaFolderSources(folder, mediaTypes);
                    if (options.isEmpty()) {
                        return Optional.empty();
                    }
                    ScreenSource chosen = options.get(random.nextInt(options.size()));
                    return Optional.of(new ResolvedPlayback(chosen, null, durationMillis));
                } catch (IOException ignored) {
                    return Optional.empty();
                }
            }
            if ("text".equals(type) || "countdown".equals(type)) {
                return Optional.of(new ResolvedPlayback(null, message, durationMillis));
            }
            return Optional.empty();
        }

        private static PlaybackItem fromSection(
                String ownerId, String rawId, ConfigurationSection section, long defaultDuration) {
            String id = ScreenDefinition.normalizeId(section.getString("id", rawId));
            if (!ScreenDefinition.isValidId(id)) {
                return null;
            }
            String type = section.getString("type", "image")
                    .trim()
                    .toLowerCase(Locale.ROOT)
                    .replace('_', '-');
            int weight = Math.max(1, section.getInt("weight", 1));
            long duration = Math.max(1_000,
                    DurationParser.parseMillis(section.get("duration"), defaultDuration));
            long cooldown = DurationParser.parseMillis(section.get("cooldown"), 0);
            PlaybackConditions conditions = PlaybackConditions.from(section);
            if ("folder".equals(type)) {
                String folder = section.getString("folder", section.getString("value", ""));
                return new PlaybackItem(ownerId, id, type, null, folder,
                        readMediaTypes(section), "", weight, duration, cooldown, conditions);
            }
            if ("text".equals(type) || "countdown".equals(type)) {
                String text = section.getString("text",
                        section.getString("title", type.equals("countdown")
                                ? "Starting soon" : id));
                return new PlaybackItem(ownerId, id, type, null, null, List.of(),
                        text, weight, duration, cooldown, conditions);
            }
            SourceType sourceType = SourceType.parse(type);
            String value = section.getString("value", section.getString("url", ""));
            ScreenSource source = sourceType == null ? null : new ScreenSource(sourceType, value);
            if (source == null || !source.isValid()) {
                return null;
            }
            return new PlaybackItem(ownerId, id, type, source, null, List.of(),
                    "", weight, duration, cooldown, conditions);
        }

        private static List<SourceType> readMediaTypes(ConfigurationSection section) {
            List<String> configured = section.getStringList("media-types");
            if (configured.isEmpty()) {
                return List.of(SourceType.VIDEO, SourceType.IMAGE, SourceType.GIF);
            }
            List<SourceType> result = new ArrayList<>();
            for (String value : configured) {
                SourceType type = SourceType.parse(value);
                if (type == SourceType.VIDEO || type == SourceType.IMAGE
                        || type == SourceType.GIF) {
                    result.add(type);
                }
            }
            return result.isEmpty()
                    ? List.of(SourceType.VIDEO, SourceType.IMAGE, SourceType.GIF)
                    : List.copyOf(result);
        }
    }

    private record ResolvedPlayback(
            ScreenSource source,
            String message,
            long durationMillis
    ) {
    }

    private record PlaybackConditions(
            int minOnline,
            int minViewers,
            String viewerPermission,
            double tpsAbove
    ) {
        private boolean matches(LuigiScreenPlugin plugin, String screenId) {
            if (minOnline > 0 && Bukkit.getOnlinePlayers().size() < minOnline) {
                return false;
            }
            if (minViewers > 0 && plugin.screenViewerCount(screenId) < minViewers) {
                return false;
            }
            if (viewerPermission != null && !viewerPermission.isBlank()
                    && !plugin.screenHasViewerWithPermission(screenId, viewerPermission)) {
                return false;
            }
            if (tpsAbove > 0 && Bukkit.getTPS()[0] < tpsAbove) {
                return false;
            }
            return true;
        }

        private static PlaybackConditions from(ConfigurationSection item) {
            ConfigurationSection conditions = item.getConfigurationSection("conditions");
            return new PlaybackConditions(
                    getInt(item, conditions, "min-online", 0),
                    getInt(item, conditions, "min-viewers", 0),
                    getString(item, conditions, "viewer-permission", ""),
                    getDouble(item, conditions, "tps-above", 0)
            );
        }

        private static int getInt(
                ConfigurationSection item,
                ConfigurationSection conditions,
                String key,
                int fallback) {
            if (conditions != null && conditions.contains(key)) {
                return conditions.getInt(key, fallback);
            }
            return item.getInt(key, fallback);
        }

        private static double getDouble(
                ConfigurationSection item,
                ConfigurationSection conditions,
                String key,
                double fallback) {
            if (conditions != null && conditions.contains(key)) {
                return conditions.getDouble(key, fallback);
            }
            return item.getDouble(key, fallback);
        }

        private static String getString(
                ConfigurationSection item,
                ConfigurationSection conditions,
                String key,
                String fallback) {
            if (conditions != null && conditions.contains(key)) {
                return conditions.getString(key, fallback);
            }
            return item.getString(key, fallback);
        }
    }
}
