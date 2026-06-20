package cz.luigismp.screen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ModrinthUpdateChecker {

    static final String NOTIFY_PERMISSION = "luigiscreen.update";

    private final LuigiScreenPlugin plugin;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final Set<UUID> notifiedPlayers = new HashSet<>();

    private volatile CompletableFuture<HttpResponse<String>> request;
    private volatile UpdateInfo availableUpdate;
    private volatile boolean notifyPlayers;
    private BukkitTask task;
    private String announcedVersion = "";

    ModrinthUpdateChecker(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        stop();
        Settings settings = Settings.read(plugin);
        if (!settings.enabled()) {
            return;
        }

        notifyPlayers = settings.notifyPlayers();
        long token = generation.incrementAndGet();
        long periodTicks = Math.max(20L,
                Math.round(settings.intervalHours() * 60 * 60 * 20));
        task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> check(token, settings),
                40L,
                periodTicks);
    }

    void stop() {
        generation.incrementAndGet();
        BukkitTask currentTask = task;
        task = null;
        if (currentTask != null) {
            currentTask.cancel();
        }
        CompletableFuture<HttpResponse<String>> currentRequest = request;
        request = null;
        if (currentRequest != null) {
            currentRequest.cancel(true);
        }
        checking.set(false);
        availableUpdate = null;
        notifyPlayers = false;
        notifiedPlayers.clear();
        announcedVersion = "";
    }

    void notifyPlayer(Player player) {
        UpdateInfo update = availableUpdate;
        if (!notifyPlayers || update == null
                || !player.hasPermission(NOTIFY_PERMISSION)
                || !notifiedPlayers.add(player.getUniqueId())) {
            return;
        }

        Component message = plugin.messages().prefixed(
                        "updates.available",
                        "current", update.currentVersion(),
                        "latest", update.latestVersion())
                .clickEvent(ClickEvent.openUrl(update.pageUrl()))
                .hoverEvent(HoverEvent.showText(
                        plugin.messages().component("updates.open-modrinth")));
        player.sendMessage(message);
    }

    private void check(long token, Settings settings) {
        if (generation.get() != token || !checking.compareAndSet(false, true)) {
            return;
        }

        String encodedProject = URLEncoder.encode(
                        settings.project(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        URI endpoint = URI.create(
                "https://api.modrinth.com/v2/project/" + encodedProject + "/version");
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", "unknown-566/LuigiScreen/"
                        + settings.currentVersion()
                        + " (https://github.com/unknown-566/LuigiScreen)")
                .GET()
                .build();

        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        request = future;
        future.whenComplete((response, throwable) -> {
            if (generation.get() != token) {
                return;
            }
            if (request == future) {
                request = null;
            }
            checking.set(false);
            if (throwable != null) {
                reportFailure(token, settings, throwable.getClass().getSimpleName());
                return;
            }
            if (response.statusCode() == 404) {
                applyResult(token, settings, null);
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                reportFailure(token, settings, "HTTP " + response.statusCode());
                return;
            }
            String latest = ModrinthVersionFeed.latestNewerVersion(
                    response.body(), settings.currentVersion()).orElse(null);
            applyResult(token, settings, latest);
        });
    }

    private void applyResult(long token, Settings settings, String latestVersion) {
        runSync(token, () -> {
            if (latestVersion == null) {
                availableUpdate = null;
                notifiedPlayers.clear();
                announcedVersion = "";
                return;
            }

            boolean changed = !latestVersion.equals(announcedVersion);
            availableUpdate = new UpdateInfo(
                    settings.currentVersion(), latestVersion, settings.pageUrl());
            if (changed) {
                notifiedPlayers.clear();
                if (settings.notifyConsole()) {
                    plugin.getLogger().warning(plugin.messages().plain(
                            "logs.update-available",
                            "current", settings.currentVersion(),
                            "latest", latestVersion,
                            "url", settings.pageUrl()));
                }
                announcedVersion = latestVersion;
            }
            if (settings.notifyPlayers()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    notifyPlayer(player);
                }
            }
        });
    }

    private void reportFailure(long token, Settings settings, String error) {
        if (!settings.logFailures()) {
            return;
        }
        runSync(token, () -> plugin.getLogger().warning(plugin.messages().plain(
                "logs.update-check-failed", "error", error)));
    }

    private void runSync(long token, Runnable action) {
        if (generation.get() != token || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (generation.get() == token && plugin.isEnabled()) {
                action.run();
            }
        });
    }

    private record UpdateInfo(String currentVersion, String latestVersion, String pageUrl) {
    }

    private record Settings(
            boolean enabled,
            String project,
            String pageUrl,
            double intervalHours,
            int timeoutSeconds,
            boolean notifyConsole,
            boolean notifyPlayers,
            boolean logFailures,
            String currentVersion
    ) {
        private static Settings read(LuigiScreenPlugin plugin) {
            String project = plugin.getConfig().getString("updates.project", "luigiscreen");
            if (project == null || project.isBlank()) {
                project = "luigiscreen";
            }
            String pageUrl = plugin.getConfig().getString(
                    "updates.modrinth-url", "https://modrinth.com/plugin/luigiscreen");
            if (!validHttpsUrl(pageUrl)) {
                pageUrl = "https://modrinth.com/plugin/luigiscreen";
            }
            double interval = Math.max(1, Math.min(168,
                    plugin.getConfig().getDouble("updates.check-interval-hours", 6)));
            int timeout = Math.max(2, Math.min(60,
                    plugin.getConfig().getInt("updates.timeout-seconds", 10)));
            return new Settings(
                    plugin.getConfig().getBoolean("updates.enabled", true),
                    project.trim(),
                    pageUrl.trim(),
                    interval,
                    timeout,
                    plugin.getConfig().getBoolean("updates.notify-console", true),
                    plugin.getConfig().getBoolean("updates.notify-players", true),
                    plugin.getConfig().getBoolean("updates.log-failures", false),
                    plugin.getPluginMeta().getVersion());
        }

        private static boolean validHttpsUrl(String value) {
            if (value == null) {
                return false;
            }
            try {
                URI parsed = URI.create(value.trim());
                return "https".equalsIgnoreCase(parsed.getScheme())
                        && parsed.getHost() != null;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
    }
}
