package cz.luigismp.screen;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.scheduler.BukkitTask;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class StudioService {

    private static final DateTimeFormatter AUDIT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final LuigiScreenPlugin plugin;
    private final File file;
    private final AtomicBoolean watching = new AtomicBoolean();
    private final Map<UUID, List<String>> favorites = new HashMap<>();
    private final Map<UUID, LinkedHashMap<String, Object>> drafts = new HashMap<>();
    private final Map<String, VoteSession> votes = new HashMap<>();
    private final Map<String, Long> thumbnailVersions = new HashMap<>();
    private volatile List<MediaEntry> media = List.of();
    private volatile List<StudioSchedule> schedules = List.of();
    private volatile YamlConfiguration config;
    private volatile Map<String, Object> referenceValues = Map.of();
    private ExecutorService watcherExecutor;
    private WatchService watchService;
    private BukkitTask scheduleTask;
    private String lastScheduleMinute = "";
    private boolean emergency;
    private boolean statsDirty;
    private long lastStatsSaveMillis;

    StudioService(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "studio.yml");
    }

    void start() {
        reload();
        startWatcher();
        refreshMediaAsync();
        scheduleTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tickSchedules, 20L, 20L);
    }

    void shutdown() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
        watching.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
        if (watcherExecutor != null) {
            watcherExecutor.shutdownNow();
        }
        save();
    }

    synchronized void reload() {
        config = YamlConfiguration.loadConfiguration(file);
        referenceValues = Map.copyOf(plugin.getConfig().getValues(true));
        addDefaults();
        loadFavorites();
        schedules = readSchedules();
        emergency = config.getBoolean("runtime.emergency", false);
        if (emergency && plugin.hasScreens()) {
            applyEmergencyState(true);
        }
        save();
    }

    List<MediaEntry> media() {
        return media;
    }

    MediaEntry media(String id) {
        return media.stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
    }

    List<String> groupIds() {
        ConfigurationSection root = config.getConfigurationSection("groups");
        return root == null ? List.of() : root.getKeys(false).stream().sorted().toList();
    }

    List<String> groupScreens(String groupId) {
        return config.getStringList("groups." + ScreenDefinition.normalizeId(groupId) + ".screens")
                .stream().map(ScreenDefinition::normalizeId)
                .filter(plugin::hasScreen).distinct().toList();
    }

    synchronized boolean createGroup(Player actor, String id, List<String> screens) {
        String normalized = ScreenDefinition.normalizeId(id);
        List<String> valid = screens.stream().map(ScreenDefinition::normalizeId)
                .filter(plugin::hasScreen).distinct().toList();
        if (!ScreenDefinition.isValidId(normalized) || valid.isEmpty()
                || config.isConfigurationSection("groups." + normalized)) {
            return false;
        }
        config.set("groups." + normalized + ".screens", valid);
        audit(actor, "created group " + normalized);
        save();
        return true;
    }

    List<StudioSchedule> schedules() {
        return schedules;
    }

    List<String> scheduleConflicts(String scheduleId) {
        StudioSchedule selected = schedules.stream()
                .filter(value -> value.id().equals(scheduleId)).findFirst().orElse(null);
        if (selected == null) return List.of();
        return schedules.stream().filter(other -> !other.id().equals(selected.id())
                        && other.enabled() && selected.enabled()
                        && other.time().equals(selected.time())
                        && other.target().equals(selected.target())
                        && other.days().stream().anyMatch(selected.days()::contains))
                .map(StudioSchedule::id).sorted().toList();
    }

    synchronized boolean createSchedule(Player actor, String id, String time,
                                        String target, String action, String value) {
        String normalized = ScreenDefinition.normalizeId(id);
        String normalizedTarget = ScreenDefinition.normalizeId(target);
        String normalizedAction = action.toLowerCase(Locale.ROOT);
        try {
            LocalTime.parse(time);
        } catch (RuntimeException exception) {
            return false;
        }
        if (!ScreenDefinition.isValidId(normalized)
                || config.isConfigurationSection("schedules." + normalized)
                || (!plugin.hasScreen(normalizedTarget)
                && !groupIds().contains(normalizedTarget))
                || !List.of("event", "playlist", "start", "stop", "return")
                .contains(normalizedAction)) {
            return false;
        }
        String path = "schedules." + normalized;
        config.set(path + ".enabled", true);
        config.set(path + ".days", List.of("MONDAY", "TUESDAY", "WEDNESDAY",
                "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        config.set(path + ".time", time);
        config.set(path + ".target", normalizedTarget);
        config.set(path + ".action", normalizedAction);
        config.set(path + ".value", ScreenDefinition.normalizeId(value));
        config.set(path + ".priority", 50);
        config.set(path + ".conflict", "priority");
        schedules = readSchedules();
        audit(actor, "created schedule " + normalized);
        save();
        return true;
    }

    synchronized boolean createPlaylist(Player actor, String id) {
        String normalized = ScreenDefinition.normalizeId(id);
        String path = "playlists." + normalized;
        if (!ScreenDefinition.isValidId(normalized)
                || plugin.getConfig().isConfigurationSection(path)) {
            return false;
        }
        plugin.getConfig().set(path + ".history-window", 3);
        plugin.getConfig().set(path + ".default-duration", "30s");
        plugin.getConfig().set(path + ".items.first.type", "text");
        plugin.getConfig().set(path + ".items.first.text", "Edit this first item");
        plugin.getConfig().set(path + ".items.first.weight", 1);
        plugin.getConfig().set(path + ".items.first.duration", "10s");
        plugin.saveConfig();
        plugin.reloadPlaybackDefinitions();
        audit(actor, "created playlist " + normalized);
        return true;
    }

    synchronized boolean createEvent(Player actor, String id) {
        String normalized = ScreenDefinition.normalizeId(id);
        String path = "events." + normalized;
        if (!ScreenDefinition.isValidId(normalized)
                || plugin.getConfig().isConfigurationSection(path)) {
            return false;
        }
        plugin.getConfig().set(path + ".priority", 50);
        plugin.getConfig().set(path + ".sequence.first.type", "text");
        plugin.getConfig().set(path + ".sequence.first.text", "Event is starting");
        plugin.getConfig().set(path + ".sequence.first.duration", "5s");
        plugin.getConfig().set(path + ".sequence.wait.type", "wait-manual");
        plugin.getConfig().set(path + ".sequence.wait.text", "Waiting for operator");
        plugin.getConfig().set(path + ".sequence.wait.duration", "1s");
        plugin.saveConfig();
        plugin.reloadPlaybackDefinitions();
        audit(actor, "created event " + normalized);
        return true;
    }

    List<String> templateIds() {
        ConfigurationSection root = config.getConfigurationSection("templates");
        return root == null ? List.of() : root.getKeys(false).stream().sorted().toList();
    }

    String templateDescription(String id) {
        return config.getString("templates." + id + ".description", "Studio template");
    }

    synchronized boolean installTemplate(Player actor, String id) {
        String normalized = ScreenDefinition.normalizeId(id);
        String type = config.getString("templates." + normalized + ".type", "");
        if ("playlist".equalsIgnoreCase(type)) {
            String path = "playlists." + normalized;
            if (plugin.getConfig().isConfigurationSection(path)) {
                return false;
            }
            plugin.getConfig().set(path + ".history-window", 3);
            plugin.getConfig().set(path + ".default-duration", "30s");
            plugin.getConfig().set(path + ".items.welcome.type", "text");
            plugin.getConfig().set(path + ".items.welcome.text", "Welcome to cinema night");
            plugin.getConfig().set(path + ".items.welcome.weight", 3);
            plugin.getConfig().set(path + ".items.welcome.duration", "20s");
            plugin.getConfig().set(path + ".items.feature.type", "text");
            plugin.getConfig().set(path + ".items.feature.text", "Add your feature video here");
            plugin.getConfig().set(path + ".items.feature.weight", 1);
            plugin.getConfig().set(path + ".items.feature.duration", "60s");
        } else if ("event".equalsIgnoreCase(type)) {
            String path = "events." + normalized;
            if (plugin.getConfig().isConfigurationSection(path)) {
                return false;
            }
            plugin.getConfig().set(path + ".priority", 50);
            plugin.getConfig().set(path + ".sequence.countdown.type", "countdown");
            plugin.getConfig().set(path + ".sequence.countdown.text", "Starting soon");
            plugin.getConfig().set(path + ".sequence.countdown.duration", "10s");
            plugin.getConfig().set(path + ".sequence.feature.type", "video");
            plugin.getConfig().set(path + ".sequence.feature.value", "replace-me.mp4");
            plugin.getConfig().set(path + ".sequence.feature.duration", "30s");
        } else {
            return false;
        }
        plugin.saveConfig();
        plugin.reloadPlaybackDefinitions();
        audit(actor, "installed template " + normalized);
        return true;
    }

    List<String> audit() {
        return config.getStringList("audit").reversed();
    }

    boolean emergency() {
        return emergency;
    }

    synchronized void setEmergency(Player actor, boolean enabled) {
        emergency = enabled;
        config.set("runtime.emergency", enabled);
        applyEmergencyState(enabled);
        audit(actor, enabled ? "enabled emergency mode" : "disabled emergency mode");
        save();
    }

    private void applyEmergencyState(boolean enabled) {
        for (String screen : plugin.screenIds()) {
            plugin.stopScreenEvent(screen);
            plugin.pausePlayback(screen, enabled);
            if (enabled) {
                plugin.showRuntimeMessage(screen, "MAINTENANCE");
            } else {
                plugin.restoreConfiguredSource(screen);
                plugin.resetPlayback(screen);
            }
        }
    }

    int applyGroup(Player actor, String groupId, String action, String value) {
        int changed = 0;
        for (String screen : groupScreens(groupId)) {
            boolean success = switch (action) {
                case "start" -> plugin.startScreen(screen);
                case "stop" -> plugin.stopScreen(screen);
                case "return" -> plugin.returnToAutomation(screen);
                case "playlist" -> plugin.setScreenPlaylist(screen, value);
                case "event" -> plugin.playScreenEvent(screen, value);
                default -> false;
            };
            if (success) {
                changed++;
            }
        }
        if (changed > 0) {
            audit(actor, action + " group " + groupId + " (" + changed + " screens)");
        }
        return changed;
    }

    synchronized boolean toggleFavorite(Player player, String action) {
        List<String> current = new ArrayList<>(favorites.getOrDefault(
                player.getUniqueId(), List.of()));
        boolean added;
        if (current.remove(action)) {
            added = false;
        } else {
            current.add(action);
            added = true;
        }
        favorites.put(player.getUniqueId(), List.copyOf(current.stream().limit(7).toList()));
        config.set("favorites." + player.getUniqueId(), current);
        save();
        return added;
    }

    List<String> favorites(Player player) {
        return favorites.getOrDefault(player.getUniqueId(), List.of());
    }

    synchronized void stage(Player player, String path, Object value) {
        if (player == null || path == null || !(path.startsWith("playlists.")
                || path.startsWith("events.") || path.startsWith("schedules.")
                || path.startsWith("groups."))) {
            return;
        }
        drafts.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>())
                .put(path, value);
    }

    Object draftValue(Player player, String path, Object fallback) {
        Map<String, Object> draft = drafts.get(player.getUniqueId());
        return draft == null ? fallback : draft.getOrDefault(path, fallback);
    }

    int draftSize(Player player) {
        return drafts.getOrDefault(player.getUniqueId(), new LinkedHashMap<>()).size();
    }

    synchronized void discardDraft(Player player) {
        drafts.remove(player.getUniqueId());
    }

    synchronized boolean publishDraft(Player player) {
        LinkedHashMap<String, Object> changes = drafts.get(player.getUniqueId());
        if (changes == null || changes.isEmpty() || !snapshotConfig()) {
            return false;
        }
        changes.forEach(plugin.getConfig()::set);
        plugin.saveConfig();
        plugin.reloadPlaybackDefinitions();
        drafts.remove(player.getUniqueId());
        audit(player, "published " + changes.size() + " studio changes");
        trimSnapshots();
        return true;
    }

    synchronized boolean undoLastPublish(Player player) {
        Path history = plugin.getDataFolder().toPath().resolve("history");
        try {
            if (!Files.isDirectory(history)) {
                return false;
            }
            Path newest;
            try (var files = Files.list(history)) {
                newest = files.filter(path -> path.getFileName().toString().endsWith(".yml"))
                        .max(Comparator.comparingLong(this::modified)).orElse(null);
            }
            if (newest == null) {
                return false;
            }
            Files.copy(newest, new File(plugin.getDataFolder(), "config.yml").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            boolean reloaded = plugin.reloadScreenConfig();
            if (reloaded) {
                Files.deleteIfExists(newest);
                audit(player, "restored config snapshot " + newest.getFileName());
            }
            return reloaded;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not restore config snapshot: "
                    + exception.getMessage());
            return false;
        }
    }

    private boolean snapshotConfig() {
        Path history = plugin.getDataFolder().toPath().resolve("history");
        try {
            Files.createDirectories(history);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .format(LocalDateTime.now());
            Files.copy(new File(plugin.getDataFolder(), "config.yml").toPath(),
                    history.resolve("config-" + stamp + ".yml"),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create config snapshot: "
                    + exception.getMessage());
            return false;
        }
    }

    private void trimSnapshots() {
        Path history = plugin.getDataFolder().toPath().resolve("history");
        int maximum = config.getInt("history.max-entries", 20);
        try (var files = Files.list(history)) {
            List<Path> ordered = files
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted(Comparator.comparingLong(this::modified).reversed())
                    .toList();
            for (int index = maximum; index < ordered.size(); index++) {
                Files.deleteIfExists(ordered.get(index));
            }
        } catch (IOException ignored) {
        }
    }

    private long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0;
        }
    }

    synchronized void audit(Player actor, String action) {
        String name = actor == null ? "CONSOLE" : actor.getName();
        List<String> entries = new ArrayList<>(config.getStringList("audit"));
        entries.add(AUDIT_TIME.format(LocalDateTime.now()) + " | " + name + " | " + action);
        while (entries.size() > config.getInt("history.max-entries", 20)) {
            entries.removeFirst();
        }
        config.set("audit", entries);
        save();
    }

    void refreshMediaAsync() {
        referenceValues = Map.copyOf(plugin.getConfig().getValues(true));
        ExecutorService executor = watcherExecutor;
        if (executor == null) {
            media = scanMedia();
            return;
        }
        executor.submit(() -> media = scanMedia());
    }

    private List<MediaEntry> scanMedia() {
        Path root = plugin.mediaDirectoryPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<MediaEntry> result = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve(".thumbnails")))
                    .forEach(path -> result.add(inspect(root, path)));
        } catch (IOException exception) {
            plugin.getLogger().warning("Media Library scan failed: " + exception.getMessage());
        }
        return result.stream().sorted(Comparator.comparing(MediaEntry::id)).toList();
    }

    private MediaEntry inspect(Path root, Path path) {
        String id = root.relativize(path).toString().replace(File.separatorChar, '/');
        SourceType type = typeFor(id);
        long size = 0;
        long modified = 0;
        int width = 0;
        int height = 0;
        String problem = "";
        try {
            size = Files.size(path);
            modified = Files.getLastModifiedTime(path).toMillis();
            if (type == SourceType.IMAGE || type == SourceType.GIF) {
                BufferedImage image = ImageIO.read(path.toFile());
                if (image == null) {
                    problem = "The image decoder rejected this file.";
                } else {
                    width = image.getWidth();
                    height = image.getHeight();
                }
            }
        } catch (IOException exception) {
            problem = exception.getMessage();
        }
        if (type == null) {
            problem = "Unsupported file extension.";
        } else if (size < 1 && problem.isBlank()) {
            problem = "The file is empty.";
        }
        Path thumbnail = problem.isBlank()
                ? MediaThumbnailer.create(root, path, type, id) : null;
        return new MediaEntry(id, type, path, size, modified, width, height, thumbnail,
                problem.isBlank(), problem, references(id));
    }

    synchronized ItemStack thumbnailItem(MediaEntry entry) {
        if (entry == null || entry.thumbnail() == null
                || !Files.isRegularFile(entry.thumbnail())
                || Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        String key = safeStatId(entry.id());
        int configuredId = config.getInt("thumbnail-map-ids." + key, -1);
        MapView view = configuredId < 0 ? null : Bukkit.getMap(configuredId);
        if (view == null) {
            view = Bukkit.createMap(Bukkit.getWorlds().getFirst());
            config.set("thumbnail-map-ids." + key, view.getId());
            save();
        }
        long version = modified(entry.thumbnail());
        if (!Long.valueOf(version).equals(thumbnailVersions.get(key))) {
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new ThumbnailMapRenderer(entry.thumbnail()));
            thumbnailVersions.put(key, version);
        }
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        ItemStack stack = new ItemStack(Material.FILLED_MAP);
        if (stack.getItemMeta() instanceof MapMeta meta) {
            meta.setMapView(view);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<String> references(String id) {
        List<String> result = new ArrayList<>();
        collectReferences(referenceValues, id, result);
        return result.stream().distinct().sorted().toList();
    }

    private static void collectReferences(Map<String, Object> values, String id,
                                          List<String> result) {
        values.forEach((key, value) -> {
            if (value instanceof String text
                    && text.replace('\\', '/').equalsIgnoreCase(id)) {
                result.add(key);
            }
        });
    }

    private void startWatcher() {
        if (!watching.compareAndSet(false, true)) {
            return;
        }
        watcherExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "LuigiScreen-MediaLibrary");
            thread.setDaemon(true);
            return thread;
        });
        watcherExecutor.submit(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                watchService = watcher;
                registerDirectories(plugin.mediaDirectoryPath(), watcher);
                while (watching.get()) {
                    WatchKey key = watcher.take();
                    boolean changed = !key.pollEvents().isEmpty();
                    if (!key.reset()) {
                        registerDirectories(plugin.mediaDirectoryPath(), watcher);
                    }
                    if (changed) {
                        Thread.sleep(300);
                        media = scanMedia();
                        registerDirectories(plugin.mediaDirectoryPath(), watcher);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                if (watching.get()) {
                    plugin.getLogger().warning(
                            "Media Library watcher stopped: " + exception.getMessage());
                }
            }
        });
    }

    private static void registerDirectories(Path root, WatchService watcher) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path directory : paths.filter(Files::isDirectory).toList()) {
                directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
            }
        }
    }

    private void tickSchedules() {
        LocalDateTime now = LocalDateTime.now();
        finishExpiredVotes();
        if (statsDirty && System.currentTimeMillis() - lastStatsSaveMillis >= 60_000) {
            save();
            statsDirty = false;
            lastStatsSaveMillis = System.currentTimeMillis();
        }
        String minute = now.getDayOfYear() + ":" + now.getHour() + ":" + now.getMinute();
        if (minute.equals(lastScheduleMinute)) {
            return;
        }
        lastScheduleMinute = minute;
        List<StudioSchedule> due = schedules.stream()
                .filter(schedule -> schedule.enabled()
                        && schedule.days().contains(now.getDayOfWeek())
                        && schedule.time().getHour() == now.getHour()
                        && schedule.time().getMinute() == now.getMinute())
                .sorted(Comparator.comparingInt(StudioSchedule::priority).reversed())
                .toList();
        Set<String> claimedTargets = new java.util.HashSet<>();
        for (StudioSchedule schedule : due) {
            if (!claimedTargets.add(schedule.target())
                    && !schedule.conflict().equalsIgnoreCase("allow")) {
                audit(null, "schedule " + schedule.id()
                        + " skipped due to a higher priority conflict");
                continue;
            }
            runSchedule(schedule);
        }
    }

    synchronized boolean startVote(Player actor, String screenId, List<String> options,
                                   long durationMillis) {
        String screen = ScreenDefinition.normalizeId(screenId);
        List<String> valid = options.stream().map(StudioService::safeStatId)
                .filter(id -> media.stream().anyMatch(entry ->
                        safeStatId(entry.id()).equals(id) && entry.valid()))
                .distinct().limit(9).toList();
        if (!plugin.hasScreen(screen) || valid.size() < 2 || votes.containsKey(screen)) {
            return false;
        }
        LinkedHashMap<String, Integer> results = new LinkedHashMap<>();
        valid.forEach(option -> results.put(option, 0));
        votes.put(screen, new VoteSession(screen,
                System.currentTimeMillis() + Math.max(10_000, durationMillis),
                results, new HashMap<>()));
        audit(actor, "started vote on " + screen + " with " + valid.size() + " options");
        return true;
    }

    synchronized boolean castVote(Player player, String screenId, String option) {
        VoteSession vote = votes.get(ScreenDefinition.normalizeId(screenId));
        String normalized = safeStatId(option);
        if (vote == null || !vote.results.containsKey(normalized)
                || !canVote(player, vote.screen)) {
            return false;
        }
        String previous = vote.voters.put(player.getUniqueId(), normalized);
        if (previous != null) {
            vote.results.computeIfPresent(previous, (ignored, count) -> Math.max(0, count - 1));
        }
        vote.results.computeIfPresent(normalized, (ignored, count) -> count + 1);
        return true;
    }

    synchronized VoteStatus voteStatus(String screenId) {
        VoteSession vote = votes.get(ScreenDefinition.normalizeId(screenId));
        if (vote == null) return null;
        return new VoteStatus(vote.screen,
                Math.max(0, vote.endsAtMillis - System.currentTimeMillis()),
                Map.copyOf(vote.results), vote.voters.size());
    }

    List<String> defaultVoteOptions() {
        return media.stream().filter(MediaEntry::valid)
                .map(entry -> safeStatId(entry.id())).distinct().limit(3).toList();
    }

    String voteMediaName(String option) {
        return media.stream().filter(entry -> safeStatId(entry.id()).equals(option))
                .map(MediaEntry::id).findFirst().orElse(option);
    }

    synchronized boolean finishVote(Player actor, String screenId) {
        VoteSession vote = votes.remove(ScreenDefinition.normalizeId(screenId));
        if (vote == null) return false;
        String winner = vote.results.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElse(null);
        MediaEntry entry = winner == null ? null : media.stream()
                .filter(candidate -> safeStatId(candidate.id()).equals(winner))
                .findFirst().orElse(null);
        if (entry != null) {
            plugin.queuePlayback(vote.screen, entry.source(), entry.id(), 30_000, false);
        }
        audit(actor, "finished vote on " + vote.screen
                + (winner == null ? "" : "; winner " + winner));
        return true;
    }

    private synchronized void finishExpiredVotes() {
        List<String> expired = votes.values().stream()
                .filter(vote -> vote.endsAtMillis <= System.currentTimeMillis())
                .map(vote -> vote.screen).toList();
        expired.forEach(screen -> finishVote(null, screen));
    }

    private boolean canVote(Player player, String screenId) {
        String permission = config.getString("voting.permission", "luigiscreen.vote");
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            return false;
        }
        ScreenDefinition definition = plugin.screenDefinition(screenId);
        if (definition == null || !player.getWorld().getName().equals(definition.world())) {
            return false;
        }
        double distance = config.getDouble("voting.distance", 64);
        return player.getLocation().toVector().distanceSquared(definition.location())
                <= distance * distance;
    }

    synchronized void recordPlayback(String screenId, String mediaId, long durationMillis,
                                     int viewers) {
        increment("statistics.screens." + screenId + ".plays", 1);
        increment("statistics.screens." + screenId + ".planned-seconds",
                durationMillis / 1000);
        increment("statistics.screens." + screenId + ".viewer-seconds",
                durationMillis / 1000 * Math.max(0, viewers));
        increment("statistics.screens." + screenId + ".viewer-samples", 1);
        increment("statistics.screens." + screenId + ".viewer-total", Math.max(0, viewers));
        String mediaIdSafe = safeStatId(mediaId);
        increment("statistics.media." + mediaIdSafe + ".plays", 1);
        increment("statistics.media." + mediaIdSafe + ".planned-seconds",
                durationMillis / 1000);
        statsDirty = true;
    }

    synchronized void recordSkip(String screenId) {
        increment("statistics.screens." + screenId + ".skips", 1);
        statsDirty = true;
    }

    synchronized void recordFailure(String screenId) {
        increment("statistics.screens." + screenId + ".failures", 1);
        statsDirty = true;
    }

    StudioStatistics screenStatistics(String screenId) {
        return statistics("statistics.screens." + safeStatId(screenId));
    }

    StudioStatistics mediaStatistics(String mediaId) {
        return statistics("statistics.media." + safeStatId(mediaId));
    }

    private StudioStatistics statistics(String path) {
        return new StudioStatistics(
                config.getLong(path + ".plays"),
                config.getLong(path + ".planned-seconds"),
                config.getLong(path + ".viewer-seconds"),
                config.getLong(path + ".viewer-samples"),
                config.getLong(path + ".viewer-total"),
                config.getLong(path + ".skips"),
                config.getLong(path + ".failures"));
    }

    private void increment(String path, long amount) {
        config.set(path, config.getLong(path) + amount);
    }

    private static String safeStatId(String value) {
        String normalized = ScreenDefinition.normalizeId(value == null ? "unknown"
                : value.replace('/', '_').replace('.', '_'));
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private void runSchedule(StudioSchedule schedule) {
        List<String> targets = groupIds().contains(schedule.target())
                ? groupScreens(schedule.target()) : List.of(schedule.target());
        for (String target : targets) {
            switch (schedule.action()) {
                case "event" -> plugin.playScreenEvent(target, schedule.value());
                case "playlist" -> plugin.setScreenPlaylist(target, schedule.value());
                case "start" -> plugin.startScreen(target);
                case "stop" -> plugin.stopScreen(target);
                case "return" -> plugin.returnToAutomation(target);
                default -> {
                }
            }
        }
        audit(null, "schedule " + schedule.id() + " executed");
    }

    private List<StudioSchedule> readSchedules() {
        ConfigurationSection root = config.getConfigurationSection("schedules");
        if (root == null) {
            return List.of();
        }
        List<StudioSchedule> result = new ArrayList<>();
        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            try {
                Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                List<String> configuredDays = section.getStringList("days");
                if (configuredDays.isEmpty()) {
                    days.addAll(EnumSet.allOf(DayOfWeek.class));
                } else {
                    for (String day : configuredDays) {
                        days.add(DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)));
                    }
                }
                result.add(new StudioSchedule(ScreenDefinition.normalizeId(rawId),
                        section.getBoolean("enabled", true), Set.copyOf(days),
                        LocalTime.parse(section.getString("time", "20:00")),
                        ScreenDefinition.normalizeId(section.getString("target", "main")),
                        section.getString("action", "event").toLowerCase(Locale.ROOT),
                        ScreenDefinition.normalizeId(section.getString("value", "")),
                        section.getInt("priority", 50),
                        section.getString("conflict", "priority")));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(
                        "Invalid studio schedule " + rawId + ": " + exception.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private void loadFavorites() {
        favorites.clear();
        ConfigurationSection root = config.getConfigurationSection("favorites");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                favorites.put(UUID.fromString(key), List.copyOf(root.getStringList(key)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void addDefaults() {
        config.addDefault("history.max-entries", 20);
        config.addDefault("media.auto-watch", true);
        config.addDefault("voting.permission", "luigiscreen.vote");
        config.addDefault("voting.distance", 64);
        config.addDefault("groups.global.screens", List.of("main"));
        config.addDefault("templates.update_reveal.type", "event");
        config.addDefault("templates.update_reveal.description",
                "Countdown, trailer, live source and return to automation.");
        config.addDefault("templates.cinema_night.type", "playlist");
        config.addDefault("templates.cinema_night.description",
                "A weighted cinema rotation with anti-repeat.");
        config.addDefault("templates.restart_warning.type", "event");
        config.addDefault("templates.restart_warning.description",
                "Timed warning, title and maintenance fallback.");
        config.options().copyDefaults(true);
    }

    private synchronized void save() {
        try {
            config.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save studio.yml: " + exception.getMessage());
        }
    }

    private static SourceType typeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gif")) return SourceType.GIF;
        if (lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
            return SourceType.IMAGE;
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".mov")
                || lower.endsWith(".avi")) {
            return SourceType.VIDEO;
        }
        return null;
    }

    private static final class VoteSession {
        private final String screen;
        private final long endsAtMillis;
        private final LinkedHashMap<String, Integer> results;
        private final Map<UUID, String> voters;

        private VoteSession(String screen, long endsAtMillis,
                            LinkedHashMap<String, Integer> results,
                            Map<UUID, String> voters) {
            this.screen = screen;
            this.endsAtMillis = endsAtMillis;
            this.results = results;
            this.voters = voters;
        }
    }
}
