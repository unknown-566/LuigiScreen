package cz.luigismp.screen;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.time.DayOfWeek;
import java.time.LocalDateTime;

final class PlaybackController {

    private static final long RETRY_MILLIS = 5_000;

    private final LuigiScreenPlugin plugin;
    private final Random random = new Random();
    private final Map<String, RuntimeState> states = new HashMap<>();
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

    void activate() {
        tick();
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

    PlaybackSnapshot snapshot(String screenId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        ScreenDefinition definition = plugin.screenDefinition(screen);
        RuntimeState state = states.get(screen);
        if (definition == null || state == null) {
            return PlaybackSnapshot.direct(reason("direct"));
        }
        long remaining = state.nextSwitchMillis == Long.MAX_VALUE ? -1
                : Math.max(0, state.nextSwitchMillis - System.currentTimeMillis());
        String mode = state.activeEvent != null ? "event"
                : definition.playlist().isBlank() ? "direct" : "playlist";
        String controller = state.activeEvent != null ? state.activeEvent.eventId
                : definition.playlist().isBlank() ? "screen" : definition.playlist();
        return new PlaybackSnapshot(mode, state.currentItemLabel(), state.nextLabel(),
                controller, state.reason.isBlank() ? reason("waiting") : state.reason,
                remaining, state.paused, state.repeat,
                state.queue.stream().map(QueuedPlayback::label).toList(),
                List.copyOf(state.history));
    }

    List<PlaybackItemView> playlistItems(String playlistId) {
        PlaylistDefinition playlist = playlists.get(ScreenDefinition.normalizeId(playlistId));
        if (playlist == null) {
            return List.of();
        }
        long total = playlist.items().stream().filter(PlaybackItem::enabled)
                .mapToLong(PlaybackItem::weight).sum();
        return playlist.items().stream().map(item -> item.view(total)).toList();
    }

    List<PlaybackItemView> eventItems(String eventId) {
        EventDefinition event = events.get(ScreenDefinition.normalizeId(eventId));
        if (event == null) {
            return List.of();
        }
        return event.sequence().stream().map(item -> item.view(0)).toList();
    }

    Map<String, Integer> simulate(String playlistId, int selections) {
        PlaylistDefinition playlist = playlists.get(ScreenDefinition.normalizeId(playlistId));
        if (playlist == null || selections < 1) {
            return Map.of();
        }
        List<PlaybackItem> eligible = playlist.items().stream()
                .filter(PlaybackItem::enabled).toList();
        long total = eligible.stream().mapToLong(PlaybackItem::weight).sum();
        if (total < 1) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        eligible.forEach(item -> result.put(item.id(), 0));
        for (int iteration = 0; iteration < selections; iteration++) {
            long roll = random.nextLong(total);
            for (PlaybackItem item : eligible) {
                roll -= item.weight();
                if (roll < 0) {
                    result.compute(item.id(), (ignored, count) -> count + 1);
                    break;
                }
            }
        }
        return Map.copyOf(result);
    }

    String explainEligibility(String screenId, String playlistId, String itemId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        PlaylistDefinition playlist = playlists.get(ScreenDefinition.normalizeId(playlistId));
        if (playlist == null) {
            return "Playlist is not loaded.";
        }
        PlaybackItem item = playlist.items().stream()
                .filter(candidate -> candidate.id().equals(ScreenDefinition.normalizeId(itemId)))
                .findFirst().orElse(null);
        if (item == null) {
            return "Item is not loaded.";
        }
        if (!item.enabled()) {
            return "Blocked: the item is disabled.";
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        long cooldown = state.cooldownUntilMillis(item.key()) - System.currentTimeMillis();
        if (cooldown > 0) {
            return "Blocked: cooldown has " + Math.max(1, cooldown / 1000) + "s remaining.";
        }
        List<String> failures = item.conditions().failures(plugin, screen);
        if (!failures.isEmpty()) {
            return "Blocked: " + String.join("; ", failures);
        }
        if (state.recentKeys(playlist.historyWindow()).contains(item.key())) {
            return "Temporarily excluded by the last-" + playlist.historyWindow()
                    + " anti-repeat window.";
        }
        return "Eligible now. It has weight " + item.weight()
                + " and may be selected on the next weighted roll.";
    }

    boolean pause(String screenId, boolean paused) {
        String screen = ScreenDefinition.normalizeId(screenId);
        if (plugin.screenDefinition(screen) == null) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        state.paused = paused;
        state.reason = reason(paused ? "held" : "active");
        return true;
    }

    boolean toggleRepeat(String screenId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        if (plugin.screenDefinition(screen) == null) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        state.repeat = !state.repeat;
        state.reason = reason(state.repeat ? "repeat-on" : "repeat-off");
        return state.repeat;
    }

    boolean skip(String screenId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        ScreenDefinition definition = plugin.screenDefinition(screen);
        if (definition == null) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        state.repeat = false;
        state.nextSwitchMillis = 0;
        state.reason = reason("skipped");
        if (plugin.studio() != null) {
            plugin.studio().recordSkip(screen);
        }
        tickScreen(screen, System.currentTimeMillis());
        return true;
    }

    boolean queue(String screenId, ScreenSource source, String label, long durationMillis,
                  boolean playNow) {
        String screen = ScreenDefinition.normalizeId(screenId);
        if (plugin.screenDefinition(screen) == null || source == null || !source.isValid()) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        QueuedPlayback queued = new QueuedPlayback(source,
                label == null || label.isBlank() ? source.displayValue() : label,
                Math.max(1_000, durationMillis));
        if (playNow) {
            state.queue.addFirst(queued);
            state.nextSwitchMillis = 0;
            tickScreen(screen, System.currentTimeMillis());
        } else {
            state.queue.addLast(queued);
        }
        return true;
    }

    boolean clearQueue(String screenId) {
        RuntimeState state = states.get(ScreenDefinition.normalizeId(screenId));
        if (state == null) {
            return false;
        }
        state.queue.clear();
        return true;
    }

    boolean removeQueued(String screenId, int index) {
        RuntimeState state = states.get(ScreenDefinition.normalizeId(screenId));
        if (state == null || index < 0 || index >= state.queue.size()) return false;
        List<QueuedPlayback> values = new ArrayList<>(state.queue);
        values.remove(index);
        state.queue.clear();
        state.queue.addAll(values);
        return true;
    }

    boolean moveQueued(String screenId, int index, int direction) {
        RuntimeState state = states.get(ScreenDefinition.normalizeId(screenId));
        if (state == null) return false;
        List<QueuedPlayback> values = new ArrayList<>(state.queue);
        int target = index + direction;
        if (index < 0 || index >= values.size() || target < 0 || target >= values.size()) {
            return false;
        }
        QueuedPlayback item = values.remove(index);
        values.add(target, item);
        state.queue.clear();
        state.queue.addAll(values);
        return true;
    }

    boolean playQueuedNow(String screenId, int index) {
        String screen = ScreenDefinition.normalizeId(screenId);
        RuntimeState state = states.get(screen);
        if (state == null) return false;
        List<QueuedPlayback> values = new ArrayList<>(state.queue);
        if (index < 0 || index >= values.size()) return false;
        QueuedPlayback item = values.remove(index);
        values.addFirst(item);
        state.queue.clear();
        state.queue.addAll(values);
        state.paused = false;
        state.nextSwitchMillis = 0;
        tickScreen(screen, System.currentTimeMillis());
        return true;
    }

    boolean returnToAutomation(String screenId) {
        String screen = ScreenDefinition.normalizeId(screenId);
        ScreenDefinition definition = plugin.screenDefinition(screen);
        if (definition == null) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        state.activeEvent = null;
        state.queue.clear();
        state.paused = false;
        state.repeat = false;
        state.nextSwitchMillis = 0;
        state.reason = reason("returned");
        if (definition.playlist().isBlank()) {
            return plugin.restoreConfiguredSource(screen);
        }
        tickScreen(screen, System.currentTimeMillis());
        return true;
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
        EventDefinition requested = events.get(event);
        if (plugin.screenDefinition(screen) == null
                || requested == null || requested.sequence().isEmpty()) {
            return false;
        }
        RuntimeState state = states.computeIfAbsent(screen, ignored -> new RuntimeState());
        if (state.activeEvent != null) {
            EventDefinition current = events.get(state.activeEvent.eventId);
            if (current != null && current.priority() > requested.priority()) {
                state.reason = reason("event-rejected",
                        "event", requested.id(), "current", current.id());
                state.addHistory("REJECTED EVENT " + requested.id() + " - " + state.reason);
                return false;
            }
        }
        state.activeEvent = new EventPlayback(event);
        state.paused = false;
        state.reason = reason("event-control",
                "event", event, "priority", requested.priority());
        state.addHistory("START EVENT " + event);
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

    private String reason(String key, Object... placeholders) {
        return plugin.messages().plain("studio.reason." + key, placeholders);
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
        if (state.paused) {
            return;
        }
        if (state.repeat && state.currentSource != null && now >= state.nextSwitchMillis) {
            plugin.switchRuntimeSource(screenId, state.currentSource);
            state.nextSwitchMillis = now + state.currentDurationMillis;
            state.reason = reason("repeat-selected");
            return;
        }
        if (state.activeEvent != null) {
            if (now >= state.nextSwitchMillis) {
                tickEvent(screenId, definition, state, now);
            }
            return;
        }
        if (!state.queue.isEmpty() && now >= state.nextSwitchMillis) {
            playQueued(screenId, state, now);
            return;
        }
        if (definition.playlist().isBlank()) {
            return;
        }
        if (now >= state.nextSwitchMillis) {
            tickPlaylist(screenId, definition, state, now);
        }
    }

    private void playQueued(String screenId, RuntimeState state, long now) {
        QueuedPlayback queued = state.queue.pollFirst();
        if (queued == null || !plugin.switchRuntimeSource(screenId, queued.source())) {
            state.reason = reason("queue-failed");
            state.nextSwitchMillis = now + RETRY_MILLIS;
            return;
        }
        state.currentItem = "queue:" + queued.label();
        state.currentLabel = queued.label();
        state.currentSource = queued.source();
        state.currentDurationMillis = queued.durationMillis();
        state.nextSwitchMillis = now + queued.durationMillis();
        state.reason = reason("queued");
        state.addHistory("QUEUE " + queued.label());
        if (plugin.studio() != null) {
            plugin.studio().recordPlayback(screenId, queued.label(),
                    queued.durationMillis(), plugin.screenViewerCount(screenId));
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
        if ("branch".equals(item.type())) {
            boolean passed = item.conditions().matches(plugin, screenId);
            String targetEvent = passed ? item.target() : item.actionValue();
            if (events.containsKey(targetEvent)) {
                state.activeEvent = new EventPlayback(targetEvent);
                state.nextSwitchMillis = now;
                state.reason = reason("branch-selected", "branch", item.id(),
                        "event", targetEvent, "result", passed ? "passed" : "failed");
                state.addHistory("BRANCH " + item.id() + " -> " + targetEvent);
            } else {
                state.reason = reason("branch-missing",
                        "branch", item.id(), "event", targetEvent);
                state.nextSwitchMillis = now;
            }
            return;
        }
        if ("wait-stream".equals(item.type())) {
            ScreenHealth health = plugin.screenHealth(screenId);
            if (health == null || !"live".equalsIgnoreCase(health.state())) {
                state.activeEvent.index--;
                state.reason = reason("wait-stream");
                state.nextSwitchMillis = now + 1_000;
                return;
            }
        }
        if (!item.enabled() || !item.conditions().matches(plugin, screenId)) {
            if (item.type().startsWith("wait-")) {
                state.activeEvent.index--;
                state.reason = reason("wait-conditions", "item", item.id());
                state.nextSwitchMillis = now + 1_000;
                return;
            }
            state.reason = reason("step-skipped", "item", item.id());
            state.addHistory("SKIP " + item.id() + " - conditions");
            state.nextSwitchMillis = now;
            return;
        }
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
        List<PlaybackItem> eligible = new ArrayList<>(playlist.items().size());
        for (PlaybackItem item : playlist.items()) {
            if (item.enabled() && item.conditions().matches(plugin, screenId)
                    && state.cooldownUntilMillis(item.key()) <= now) {
                eligible.add(item);
            }
        }
        if (eligible.size() > 1 && playlist.historyWindow() > 0) {
            List<String> recent = state.recentKeys(playlist.historyWindow());
            eligible.removeIf(item -> recent.contains(item.key()));
        }
        if (eligible.size() > 1 && playlist.categoryHistoryWindow() > 0) {
            List<String> recentCategories = state.recentCategories.stream()
                    .limit(playlist.categoryHistoryWindow()).toList();
            List<PlaybackItem> categoryFiltered = eligible.stream()
                    .filter(item -> item.category() == null || item.category().isBlank()
                            || !recentCategories.contains(item.category()))
                    .toList();
            if (!categoryFiltered.isEmpty()) {
                eligible = new ArrayList<>(categoryFiltered);
            }
        }
        if (eligible.isEmpty()) {
            for (PlaybackItem item : playlist.items()) {
                if (item.enabled() && item.conditions().matches(plugin, screenId)
                        && state.cooldownUntilMillis(item.key()) <= now) {
                    eligible.add(item);
                }
            }
        }
        if (eligible.size() > 1) {
            eligible.removeIf(item -> item.key().equals(state.currentItem));
        }
        PlaybackItem overdue = eligible.stream()
                .filter(item -> item.guaranteedAfterMillis() > 0)
                .filter(item -> now - state.lastPlayedMillis.getOrDefault(
                        item.key(), state.createdAtMillis) >= item.guaranteedAfterMillis())
                .max(java.util.Comparator.comparingLong(item -> now
                        - state.lastPlayedMillis.getOrDefault(
                        item.key(), state.createdAtMillis)))
                .orElse(null);
        if (overdue != null) {
            return overdue;
        }
        long totalWeight = 0;
        for (PlaybackItem item : eligible) {
            totalWeight += item.weight();
        }
        if (totalWeight < 1) {
            return null;
        }
        long roll = random.nextLong(totalWeight);
        for (PlaybackItem item : eligible) {
            roll -= item.weight();
            if (roll < 0) {
                return item;
            }
        }
        return eligible.getLast();
    }

    private boolean playItem(String screenId, PlaybackItem item, RuntimeState state, long now) {
        Optional<ResolvedPlayback> resolved = item.resolve(random);
        if (resolved.isEmpty()) {
            return false;
        }
        ResolvedPlayback playback = resolved.get();
        boolean switched = switch (item.type()) {
            case "wait", "wait-viewers", "wait-stream", "branch" -> true;
            case "manual", "wait-manual" -> {
                state.paused = true;
                yield true;
            }
            case "command" -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), playback.message());
            case "broadcast" -> {
                Bukkit.broadcast(Component.text(playback.message()));
                yield true;
            }
            case "sound" -> {
                for (var player : Bukkit.getOnlinePlayers()) {
                    player.playSound(player.getLocation(), playback.message(), 1.0f, 1.0f);
                }
                yield true;
            }
            case "title" -> {
                Title title = Title.title(Component.text(playback.message()), Component.empty());
                Bukkit.getOnlinePlayers().forEach(player -> player.showTitle(title));
                yield true;
            }
            case "group" -> plugin.studio() != null
                    && plugin.studio().applyGroup(null, item.target(),
                    playback.message(), item.actionValue()) > 0;
            default -> playback.source() == null
                    ? plugin.showRuntimeMessage(screenId, playback.message())
                    : plugin.switchRuntimeSource(screenId, playback.source());
        };
        if (!switched) {
            if (plugin.studio() != null) {
                plugin.studio().recordFailure(screenId);
            }
            return false;
        }
        state.currentItem = item.key();
        state.currentLabel = item.id();
        state.currentSource = playback.source();
        state.currentDurationMillis = playback.durationMillis();
        state.nextSwitchMillis = now + playback.durationMillis();
        state.reason = state.activeEvent == null
                ? reason("playlist-selected", "playlist", item.ownerId(),
                "weight", item.weight())
                : reason("event-selected", "event", state.activeEvent.eventId);
        state.addHistory((state.activeEvent == null ? "PLAYLIST " : "EVENT ")
                + item.id() + " - " + state.reason);
        state.played(item, now);
        if (plugin.studio() != null) {
            plugin.studio().recordPlayback(screenId, item.id(),
                    playback.durationMillis(), plugin.screenViewerCount(screenId));
        }
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
            result.put(id, new PlaylistDefinition(id, items,
                    Math.max(0, section.getInt("history-window", 1)),
                    Math.max(0, section.getInt("category-history-window", 1))));
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
            result.put(id, new EventDefinition(
                    id, section.getInt("priority", 50), sequence));
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
                items.add(cacheFolderSources(item));
            }
        }
        return List.copyOf(items);
    }

    private PlaybackItem cacheFolderSources(PlaybackItem item) {
        if (item.folder() == null || item.folder().isBlank()) {
            return item;
        }
        try {
            return item.withFolderSources(
                    plugin.mediaFolderSources(item.folder(), item.mediaTypes()));
        } catch (IOException ignored) {
            return item.withFolderSources(List.of());
        }
    }

    private record PlaylistDefinition(
            String id,
            List<PlaybackItem> items,
            int historyWindow,
            int categoryHistoryWindow
    ) {
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
        private final Map<String, Long> cooldowns = new HashMap<>();
        private final Deque<QueuedPlayback> queue = new ArrayDeque<>();
        private final Deque<String> history = new ArrayDeque<>();
        private final Deque<String> recentItemKeys = new ArrayDeque<>();
        private final Deque<String> recentCategories = new ArrayDeque<>();
        private final Map<String, Long> lastPlayedMillis = new HashMap<>();
        private final long createdAtMillis = System.currentTimeMillis();
        private EventPlayback activeEvent;
        private String currentItem = "";
        private String currentLabel = "";
        private String reason = "";
        private ScreenSource currentSource;
        private long currentDurationMillis = 30_000;
        private long nextSwitchMillis;
        private boolean paused;
        private boolean repeat;

        private long cooldownUntilMillis(String itemKey) {
            return cooldowns.getOrDefault(itemKey, 0L);
        }

        private String currentItemLabel() {
            return currentLabel == null || currentLabel.isBlank() ? "none" : currentLabel;
        }

        private String nextLabel() {
            QueuedPlayback queued = queue.peekFirst();
            return queued == null ? "automatic selection" : queued.label();
        }

        private void addHistory(String value) {
            history.addFirst(System.currentTimeMillis() + " " + value);
            while (history.size() > 20) {
                history.removeLast();
            }
            if (currentItem != null && !currentItem.isBlank()) {
                recentItemKeys.addFirst(currentItem);
                while (recentItemKeys.size() > 20) {
                    recentItemKeys.removeLast();
                }
            }
        }

        private void played(PlaybackItem item, long now) {
            lastPlayedMillis.put(item.key(), now);
            if (item.category() != null && !item.category().isBlank()) {
                recentCategories.addFirst(item.category());
                while (recentCategories.size() > 20) recentCategories.removeLast();
            }
        }

        private List<String> recentKeys(int count) {
            return recentItemKeys.stream().limit(count).toList();
        }
    }

    private record QueuedPlayback(ScreenSource source, String label, long durationMillis) {
    }

    private record PlaybackItem(
            String ownerId,
            String id,
            String type,
            ScreenSource source,
            String folder,
            List<SourceType> mediaTypes,
            List<ScreenSource> folderSources,
            String message,
            String actionValue,
            String target,
            String category,
            long guaranteedAfterMillis,
            int weight,
            long durationMillis,
            long cooldownMillis,
            boolean enabled,
            PlaybackConditions conditions
    ) {
        private String key() {
            return ownerId + ":" + id;
        }

        private PlaybackItemView view(long totalWeight) {
            String value = source != null ? source.displayValue()
                    : folder != null ? folder : message;
            double probability = totalWeight < 1 ? 0 : weight * 100.0 / totalWeight;
            return new PlaybackItemView(id, type, value, weight, probability,
                    durationMillis, cooldownMillis, enabled, conditions.describe());
        }

        private Optional<ResolvedPlayback> resolve(Random random) {
            if (source != null) {
                return Optional.of(new ResolvedPlayback(source, null, durationMillis));
            }
            if (folder != null && !folder.isBlank()) {
                if (folderSources.isEmpty()) {
                    return Optional.empty();
                }
                ScreenSource chosen = folderSources.get(random.nextInt(folderSources.size()));
                return Optional.of(new ResolvedPlayback(chosen, null, durationMillis));
            }
            if ("text".equals(type) || "countdown".equals(type)) {
                return Optional.of(new ResolvedPlayback(null, message, durationMillis));
            }
            if (List.of("wait", "manual", "wait-manual", "wait-viewers",
                    "wait-stream", "command", "broadcast", "sound", "title",
                    "group", "branch").contains(type)) {
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
            boolean enabled = section.getBoolean("enabled", true);
            long duration = Math.max(1_000,
                    DurationParser.parseMillis(section.get("duration"), defaultDuration));
            long cooldown = DurationParser.parseMillis(section.get("cooldown"), 0);
            PlaybackConditions conditions = PlaybackConditions.from(section);
            if ("folder".equals(type)) {
                String folder = section.getString("folder", section.getString("value", ""));
                return new PlaybackItem(ownerId, id, type, null, folder,
                        readMediaTypes(section), List.of(), "", "", "",
                        section.getString("category", ""),
                        DurationParser.parseMillis(section.get("guaranteed-after"), 0),
                        weight, duration, cooldown, enabled, conditions);
            }
            if ("text".equals(type) || "countdown".equals(type)) {
                String text = section.getString("text",
                        section.getString("title", type.equals("countdown")
                                ? "Starting soon" : id));
                return new PlaybackItem(ownerId, id, type, null, null, List.of(), List.of(),
                        text, "", "", section.getString("category", ""),
                        DurationParser.parseMillis(section.get("guaranteed-after"), 0),
                        weight, duration, cooldown, enabled, conditions);
            }
            if (List.of("wait", "manual", "wait-manual", "wait-viewers",
                    "wait-stream", "command", "broadcast", "sound", "title",
                    "group", "branch").contains(type)) {
                String action = switch (type) {
                    case "command" -> section.getString("command", "");
                    case "sound" -> section.getString("sound", "block.note_block.pling");
                    case "group" -> section.getString("action", "return");
                    default -> section.getString("text",
                            section.getString("message", id));
                };
                String target = "branch".equals(type)
                        ? section.getString("then-event", "")
                        : section.getString("target", "");
                String actionValue = "branch".equals(type)
                        ? section.getString("else-event", "")
                        : section.getString("value", "");
                return new PlaybackItem(ownerId, id, type, null, null, List.of(), List.of(),
                        action, ScreenDefinition.normalizeId(actionValue),
                        ScreenDefinition.normalizeId(target),
                        section.getString("category", ""),
                        DurationParser.parseMillis(section.get("guaranteed-after"), 0),
                        weight, duration, cooldown, enabled, conditions);
            }
            SourceType sourceType = SourceType.parse(type);
            String value = section.getString("value", section.getString("url", ""));
            ScreenSource source = sourceType == null ? null : new ScreenSource(sourceType, value);
            if (source == null || !source.isValid()) {
                return null;
            }
            return new PlaybackItem(ownerId, id, type, source, null, List.of(), List.of(),
                    "", "", "", section.getString("category", ""),
                    DurationParser.parseMillis(section.get("guaranteed-after"), 0),
                    weight, duration, cooldown, enabled, conditions);
        }

        private PlaybackItem withFolderSources(List<ScreenSource> sources) {
            return new PlaybackItem(ownerId, id, type, source, folder, mediaTypes,
                    List.copyOf(sources), message, actionValue, target, category,
                    guaranteedAfterMillis, weight, durationMillis,
                    cooldownMillis, enabled, conditions);
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
            int maxOnline,
            int minViewers,
            int maxViewers,
            String viewerPermission,
            String allViewersPermission,
            double tpsAbove,
            double tpsBelow,
            String world,
            List<DayOfWeek> days
    ) {
        private String describe() {
            List<String> values = new ArrayList<>();
            if (minOnline > 0) values.add("online >= " + minOnline);
            if (maxOnline > 0) values.add("online <= " + maxOnline);
            if (minViewers > 0) values.add("viewers >= " + minViewers);
            if (maxViewers > 0) values.add("viewers <= " + maxViewers);
            if (viewerPermission != null && !viewerPermission.isBlank()) {
                values.add("a viewer has " + viewerPermission);
            }
            if (allViewersPermission != null && !allViewersPermission.isBlank()) {
                values.add("all viewers have " + allViewersPermission);
            }
            if (tpsAbove > 0) values.add("TPS > " + tpsAbove);
            if (tpsBelow > 0) values.add("TPS < " + tpsBelow);
            if (world != null && !world.isBlank()) values.add("world = " + world);
            if (days != null && !days.isEmpty()) values.add("day in " + days);
            return values.isEmpty() ? "always" : String.join(" AND ", values);
        }

        private List<String> failures(LuigiScreenPlugin plugin, String screenId) {
            List<String> result = new ArrayList<>();
            int online = Bukkit.getOnlinePlayers().size();
            int viewers = plugin.screenViewerCount(screenId);
            double tps = Bukkit.getTPS()[0];
            if (minOnline > 0 && online < minOnline) {
                result.add("online players " + online + " < " + minOnline);
            }
            if (maxOnline > 0 && online > maxOnline) {
                result.add("online players " + online + " > " + maxOnline);
            }
            if (minViewers > 0 && viewers < minViewers) {
                result.add("viewers " + viewers + " < " + minViewers);
            }
            if (maxViewers > 0 && viewers > maxViewers) {
                result.add("viewers " + viewers + " > " + maxViewers);
            }
            if (viewerPermission != null && !viewerPermission.isBlank()
                    && !plugin.screenHasViewerWithPermission(screenId, viewerPermission)) {
                result.add("no nearby viewer has " + viewerPermission);
            }
            if (allViewersPermission != null && !allViewersPermission.isBlank()
                    && !plugin.allScreenViewersHavePermission(
                    screenId, allViewersPermission)) {
                result.add("not every nearby viewer has " + allViewersPermission);
            }
            if (tpsAbove > 0 && tps < tpsAbove) {
                result.add(String.format(Locale.ROOT, "TPS %.2f < %.2f", tps, tpsAbove));
            }
            if (tpsBelow > 0 && tps > tpsBelow) {
                result.add(String.format(Locale.ROOT, "TPS %.2f > %.2f", tps, tpsBelow));
            }
            ScreenDefinition definition = plugin.screenDefinition(screenId);
            if (world != null && !world.isBlank()
                    && (definition == null || !definition.world().equalsIgnoreCase(world))) {
                result.add("screen world is not " + world);
            }
            if (days != null && !days.isEmpty()
                    && !days.contains(LocalDateTime.now().getDayOfWeek())) {
                result.add("today is not in " + days);
            }
            return result;
        }

        private boolean matches(LuigiScreenPlugin plugin, String screenId) {
            return failures(plugin, screenId).isEmpty();
        }

        private static PlaybackConditions from(ConfigurationSection item) {
            ConfigurationSection conditions = item.getConfigurationSection("conditions");
            return new PlaybackConditions(
                    getInt(item, conditions, "min-online", 0),
                    getInt(item, conditions, "max-online", 0),
                    getInt(item, conditions, "min-viewers", 0),
                    getInt(item, conditions, "max-viewers", 0),
                    getString(item, conditions, "viewer-permission", ""),
                    getString(item, conditions, "all-viewers-permission", ""),
                    getDouble(item, conditions, "tps-above", 0),
                    getDouble(item, conditions, "tps-below", 0),
                    getString(item, conditions, "world", ""),
                    readDays(item, conditions)
            );
        }

        private static List<DayOfWeek> readDays(
                ConfigurationSection item, ConfigurationSection conditions) {
            List<String> values = conditions != null && conditions.contains("days")
                    ? conditions.getStringList("days") : item.getStringList("days");
            List<DayOfWeek> result = new ArrayList<>();
            for (String value : values) {
                try {
                    result.add(DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return List.copyOf(result);
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
