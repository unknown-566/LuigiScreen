package cz.luigismp.screen;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

final class StudioWebServer {

    private static final String COOKIE = "ls_session";
    private static final int MAX_BODY = 1_048_576;
    private static final Set<String> CAPABILITIES = Set.of(
            "dashboard", "screens", "media", "playlists", "events", "automations", "live",
            "groups", "schedules", "templates", "monitoring", "diagnostics", "history",
            "emergency", "control", "configuration", "settings");
    private final LuigiScreenPlugin plugin;
    private final StudioWebSecurity security = new StudioWebSecurity();
    private final StudioWebSnapshot snapshots;
    private final Map<String, LinkedHashMap<String, Object>> drafts = new ConcurrentHashMap<>();
    private final Map<String, Long> draftBaselines = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseClient> sseClients = new CopyOnWriteArrayList<>();
    private volatile HttpServer server;
    private volatile ExecutorService requestExecutor;
    private volatile ScheduledExecutorService liveExecutor;
    private volatile Settings settings;
    private volatile String startError = "";

    StudioWebServer(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
        this.snapshots = new StudioWebSnapshot(plugin);
    }

    synchronized void start() {
        Settings desired = Settings.read(plugin.getConfig());
        settings = desired;
        if (!desired.enabled()) {
            return;
        }
        try {
            HttpServer created = HttpServer.create(
                    new InetSocketAddress(desired.bind(), desired.port()), 32);
            ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
            created.setExecutor(requests);
            created.createContext("/", this::handleRoot);
            created.createContext("/assets/", this::handleAsset);
            created.createContext("/login", this::handleLogin);
            created.createContext("/logout", this::handleLogout);
            created.createContext("/health", this::handleHealth);
            created.createContext("/api/state", this::handleState);
            created.createContext("/api/events", this::handleEvents);
            created.createContext("/api/action", this::handleAction);
            created.createContext("/api/draft", this::handleDraft);
            created.createContext("/api/publish", this::handlePublish);
            created.createContext("/api/thumbnail", this::handleThumbnail);
            created.createContext("/api/preview", this::handlePreview);
            created.start();
            server = created;
            requestExecutor = requests;
            startLiveUpdates(desired.updateMillis());
            startError = "";
            plugin.getLogger().info("LuigiScreen Studio listening on "
                    + desired.bind() + ":" + desired.port());
        } catch (IOException | RuntimeException exception) {
            startError = StreamUrlSanitizer.maskError(exception);
            plugin.getLogger().warning("Could not start LuigiScreen Studio: " + startError);
        }
    }

    synchronized void reload() {
        Settings desired = Settings.read(plugin.getConfig());
        Settings current = settings;
        if (Objects.equals(current, desired) && server != null) {
            return;
        }
        shutdownServer(false);
        settings = desired;
        if (desired.enabled()) {
            start();
        }
    }

    synchronized void shutdown() {
        shutdownServer(true);
        security.revokeAll();
        drafts.clear();
        draftBaselines.clear();
    }

    private void shutdownServer(boolean clearSettings) {
        ScheduledExecutorService live = liveExecutor;
        liveExecutor = null;
        if (live != null) live.shutdownNow();
        for (SseClient client : sseClients) client.close();
        sseClients.clear();
        HttpServer current = server;
        server = null;
        if (current != null) current.stop(0);
        ExecutorService requests = requestExecutor;
        requestExecutor = null;
        if (requests != null) requests.shutdownNow();
        if (clearSettings) settings = null;
    }

    boolean running() {
        return server != null;
    }

    String status() {
        Settings value = settings == null ? Settings.read(plugin.getConfig()) : settings;
        if (!value.enabled()) return "disabled";
        if (server == null) return startError.isBlank() ? "stopped" : "error: " + startError;
        return "online at " + value.bind() + ":" + value.port()
                + " (" + security.activeSessions() + " sessions)";
    }

    synchronized boolean ensureLanAccess() {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        if (!config.getBoolean("web-studio.enabled", true)) {
            config.set("web-studio.enabled", true);
            changed = true;
        }
        String publicUrl = Objects.requireNonNullElse(
                config.getString("web-studio.public-url"), "").trim();
        String bind = Objects.requireNonNullElse(
                config.getString("web-studio.bind"), "127.0.0.1").trim();
        if (publicUrl.isBlank() && StudioWebAccess.isLoopbackBind(bind)) {
            config.set("web-studio.bind", "0.0.0.0");
            changed = true;
        }
        if (changed) {
            plugin.saveConfig();
        }
        if (changed || server == null) {
            reload();
        }
        return changed;
    }

    String createLoginLink(CommandSender sender) {
        List<StudioWebAccess.Link> links = createLoginLinks(sender);
        return links.isEmpty() ? null : links.getFirst().url();
    }

    List<StudioWebAccess.Link> createLoginLinks(CommandSender sender) {
        Settings value = settings;
        if (server == null || value == null) return List.of();
        Set<String> capabilities = capabilities(sender);
        String token = security.issueLogin(sender.getName(), capabilities,
                Duration.ofMinutes(value.loginMinutes()));
        return StudioWebAccess.loginLinks(value.publicUrl(), value.bind(), value.port(), token);
    }

    int revoke(CommandSender sender) {
        return security.revokeActor(sender.getName());
    }

    Map<String, Object> accessSummary() {
        Settings value = settings == null ? Settings.read(plugin.getConfig()) : settings;
        boolean lanReady = value.enabled()
                && (!StudioWebAccess.isLoopbackBind(value.bind())
                || !value.publicUrl().isBlank());
        return Map.of(
                "enabled", value.enabled(),
                "running", server != null,
                "bind", value.bind(),
                "port", value.port(),
                "publicUrl", value.publicUrl(),
                "lanReady", lanReady,
                "lanHosts", StudioWebAccess.lanHosts(),
                "status", status());
    }

    boolean previewRequested() {
        return running() && !sseClients.isEmpty();
    }

    private Set<String> capabilities(CommandSender sender) {
        if (sender.hasPermission(ScreenPermissions.ADMIN)) return Set.of("*");
        java.util.HashSet<String> result = new java.util.HashSet<>();
        for (String capability : CAPABILITIES) {
            if (sender.hasPermission("luigiscreen.menu." + capability)) {
                result.add(capability);
            }
        }
        result.add("dashboard");
        return Set.copyOf(result);
    }

    private void startLiveUpdates(long intervalMillis) {
        ScheduledExecutorService live = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LuigiScreen-Web-Live");
            thread.setDaemon(true);
            return thread;
        });
        liveExecutor = live;
        live.scheduleAtFixedRate(this::broadcastState,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void broadcastState() {
        if (sseClients.isEmpty()) return;
        Map<String, String> payloads = new java.util.HashMap<>();
        for (SseClient client : sseClients) {
            StudioWebSecurity.Session session = security.authenticate(client.sessionId());
            if (session == null) {
                client.close();
                sseClients.remove(client);
                continue;
            }
            try {
                String payload = payloads.computeIfAbsent(session.id(), ignored -> {
                    try {
                        return StudioJson.write(sync(() -> snapshots.buildLive(
                                session, draftSize(session.id()), draftChangedExternally(session.id()))));
                    } catch (Exception exception) {
                        return "{}";
                    }
                });
                client.send("state", payload);
            } catch (IOException exception) {
                client.close();
                sseClients.remove(client);
            }
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        StudioWebSecurity.Session session = authenticate(exchange);
        if (session == null) {
            sendHtml(exchange, 401, loginPage());
            return;
        }
        sendResource(exchange, "/web/index.html", "text/html; charset=utf-8");
    }

    private void handleAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String name = path.substring("/assets/".length());
        if (!List.of("app.css", "app.js").contains(name)) {
            sendText(exchange, 404, "Not found");
            return;
        }
        sendResource(exchange, "/web/" + name,
                name.endsWith(".css") ? "text/css; charset=utf-8"
                        : "application/javascript; charset=utf-8");
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String token = query(exchange.getRequestURI()).get("token");
        Settings value = settings;
        StudioWebSecurity.Session session = value == null ? null
                : security.consumeLogin(token, Duration.ofHours(value.sessionHours()));
        if (session == null) {
            sendHtml(exchange, 401, "<!doctype html><title>LuigiScreen Studio</title>"
                    + "<h1>This login link is invalid or expired.</h1>"
                    + "<p>Run <code>/screen web</code> to create a new one.</p>");
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.add("Set-Cookie", COOKIE + "=" + session.id()
                + "; Path=/; HttpOnly; SameSite=Strict" + secureCookie());
        headers.add("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        StudioWebSecurity.Session session = authenticate(exchange);
        if (session != null) security.revoke(session.id());
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
                + secureCookie());
        headers.add("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, server == null ? 503 : 200, Map.of(
                "status", server == null ? "offline" : "online",
                "version", plugin.getPluginMeta().getVersion()));
    }

    private void handleState(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        StudioWebSecurity.Session session = requireSession(exchange);
        if (session == null) return;
        try {
            sendJson(exchange, 200, sync(() -> snapshots.build(
                    session, draftSize(session.id()), draftChangedExternally(session.id()))));
        } catch (Exception exception) {
            apiError(exchange, exception);
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        StudioWebSecurity.Session session = requireSession(exchange);
        if (session == null) return;
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-store");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(session.id(), exchange,
                exchange.getResponseBody());
        sseClients.add(client);
        try {
            client.send("connected", "{\"ok\":true}");
        } catch (IOException exception) {
            sseClients.remove(client);
            client.close();
        }
    }

    private void handleAction(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        StudioWebSecurity.Session session = requireMutationSession(exchange);
        if (session == null) return;
        try {
            Map<String, String> form = form(exchange);
            ActionResult result = sync(() -> performAction(session, form));
            sendJson(exchange, result.ok() ? 200 : 400,
                    Map.of("ok", result.ok(), "message", result.message()));
        } catch (Exception exception) {
            apiError(exchange, exception);
        }
    }

    private void handleDraft(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        StudioWebSecurity.Session session = requireMutationSession(exchange);
        if (session == null) return;
        Map<String, String> form = form(exchange);
        String path = form.getOrDefault("path", "");
        Object value = typedValue(form.get("value"), form.get("type"));
        if (!allowedDraftPath(path) || !canEditPath(session, path)
                || value == null || !validDraftValue(path, value)) {
            sendJson(exchange, 400, Map.of("ok", false,
                    "message", "This setting cannot be staged from the web panel."));
            return;
        }
        draftBaselines.putIfAbsent(session.id(), configModified());
        drafts.computeIfAbsent(session.id(), ignored -> new LinkedHashMap<>())
                .put(path, value);
        sendJson(exchange, 200, Map.of("ok", true, "message", "Change staged.",
                "draftChanges", draftSize(session.id())));
    }

    private void handlePublish(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        StudioWebSecurity.Session session = requireMutationSession(exchange);
        if (session == null) return;
        Map<String, String> form = form(exchange);
        if ("discard".equals(form.get("action"))) {
            drafts.remove(session.id());
            draftBaselines.remove(session.id());
            sendJson(exchange, 200, Map.of("ok", true, "message", "Draft discarded."));
            return;
        }
        LinkedHashMap<String, Object> changes = drafts.get(session.id());
        if (changes == null || changes.isEmpty()) {
            sendJson(exchange, 400, Map.of("ok", false, "message", "There are no changes."));
            return;
        }
        if (changes.keySet().stream().anyMatch(path -> !canEditPath(session, path))) {
            forbidden(exchange);
            return;
        }
        if (draftChangedExternally(session.id())) {
            sendJson(exchange, 409, Map.of("ok", false, "message",
                    "config.yml changed outside this Studio draft. Discard or review the draft before publishing."));
            return;
        }
        try {
            boolean published = sync(() -> plugin.studio().publishWebDraft(
                    session.actor(), new LinkedHashMap<>(changes)));
            if (published) {
                drafts.remove(session.id());
                draftBaselines.remove(session.id());
            }
            sendJson(exchange, published ? 200 : 500, Map.of(
                    "ok", published,
                    "message", published ? "Draft published and configuration reloaded."
                            : "Publishing failed; the previous configuration is still available."));
        } catch (Exception exception) {
            apiError(exchange, exception);
        }
    }

    private void handleThumbnail(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (requireSession(exchange) == null) return;
        String id = query(exchange.getRequestURI()).getOrDefault("id", "");
        MediaEntry entry;
        try {
            entry = sync(() -> plugin.studio().media(id));
        } catch (Exception exception) {
            apiError(exchange, exception);
            return;
        }
        Path path = entry == null ? null : entry.thumbnail();
        if (path == null && entry != null && entry.type() == SourceType.IMAGE) path = entry.path();
        if (path == null || !Files.isRegularFile(path) || Files.size(path) > 8_388_608) {
            sendText(exchange, 404, "No thumbnail");
            return;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        byte[] bytes = Files.readAllBytes(path);
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.set("Content-Type", name.endsWith(".png") ? "image/png" : "image/jpeg");
        headers.set("Cache-Control", "private, max-age=60");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void handlePreview(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (requireSession(exchange) == null) return;
        String screen = ScreenDefinition.normalizeId(
                query(exchange.getRequestURI()).get("screen"));
        java.awt.image.BufferedImage image;
        try {
            image = sync(() -> plugin.screenPreview(screen));
        } catch (Exception exception) {
            apiError(exchange, exception);
            return;
        }
        if (image == null) {
            sendText(exchange, 404, "No live preview");
            return;
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(96_000);
        if (!ImageIO.write(image, "jpg", encoded)) {
            sendText(exchange, 500, "Preview encoder unavailable");
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.set("Content-Type", "image/jpeg");
        headers.set("Cache-Control", "private, max-age=1");
        byte[] bytes = encoded.toByteArray();
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private ActionResult performAction(StudioWebSecurity.Session session,
                                       Map<String, String> form) {
        String action = form.getOrDefault("action", "");
        String screen = ScreenDefinition.normalizeId(form.get("screen"));
        boolean needsScreen = action.startsWith("screen.") || action.startsWith("playback.")
                || action.equals("playlist.assign") || action.equals("playlist.clear")
                || action.startsWith("event.play")
                || action.startsWith("event.stop") || action.equals("conditions.test");
        if (needsScreen && !plugin.hasScreen(screen)) {
            return ActionResult.fail("Screen not found.");
        }
        return switch (action) {
            case "playlist.create" -> {
                if (!session.can("playlists")) yield ActionResult.denied();
                boolean ok = plugin.studio().createPlaylistNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("name")));
                yield result(ok, "Playlist created. Add the first media item next.");
            }
            case "playlist.duplicate" -> {
                if (!session.can("playlists")) yield ActionResult.denied();
                boolean ok = plugin.studio().duplicatePlaylistNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("playlist")),
                        ScreenDefinition.normalizeId(form.get("name")));
                yield result(ok, "Playlist duplicated.");
            }
            case "playlist.delete" -> {
                if (!session.can("playlists")) yield ActionResult.denied();
                boolean ok = plugin.studio().deletePlaylistNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("playlist")));
                yield result(ok, "Playlist deleted.");
            }
            case "playlist.item.add" -> {
                if (!session.can("playlists")) yield ActionResult.denied();
                String playlist = ScreenDefinition.normalizeId(form.get("playlist"));
                MediaEntry media = plugin.studio().media(form.get("media"));
                if (!plugin.playlistIds().contains(playlist) || media == null || !media.valid()) {
                    yield ActionResult.fail("Playlist, item name or media is invalid.");
                }
                boolean ok = plugin.studio().addPlaylistMediaItemNamed(session.actor(),
                        playlist,
                        form.get("item"),
                        media,
                        (int) Math.max(1, parseLong(form.get("weight"), 1)),
                        form.getOrDefault("duration", "30s"));
                yield result(ok, "Media added to playlist.");
            }
            case "playlist.item.delete" -> {
                if (!session.can("playlists")) yield ActionResult.denied();
                boolean ok = plugin.studio().deletePlaylistItemNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("playlist")),
                        ScreenDefinition.normalizeId(form.get("item")));
                yield result(ok, "Playlist item deleted.");
            }
            case "conditions.test" -> {
                if (!session.can("playlists")) yield ActionResult.denied();
                String explanation = plugin.explainPlaylistItem(screen,
                        ScreenDefinition.normalizeId(form.get("playlist")),
                        ScreenDefinition.normalizeId(form.get("item")));
                yield ActionResult.ok(explanation);
            }
            case "event.create" -> {
                if (!session.can("events")) yield ActionResult.denied();
                boolean ok = plugin.studio().createEventNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("name")));
                yield result(ok, "Event created. Add or remove steps in the builder.");
            }
            case "event.duplicate" -> {
                if (!session.can("events")) yield ActionResult.denied();
                boolean ok = plugin.studio().duplicateEventNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("event")),
                        ScreenDefinition.normalizeId(form.get("name")));
                yield result(ok, "Event duplicated.");
            }
            case "event.delete" -> {
                if (!session.can("events")) yield ActionResult.denied();
                boolean ok = plugin.studio().deleteEventNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("event")));
                yield result(ok, "Event deleted.");
            }
            case "event.step.add" -> {
                if (!session.can("events")) yield ActionResult.denied();
                String event = ScreenDefinition.normalizeId(form.get("event"));
                String step = ScreenDefinition.normalizeId(form.get("step"));
                MediaEntry media = plugin.studio().media(form.get("media"));
                if (!plugin.eventIds().contains(event)
                        || (form.getOrDefault("stepType", "media").equalsIgnoreCase("media")
                        && (media == null || !media.valid()))
                        || (!step.isBlank() && !ScreenDefinition.isValidId(step))) {
                    yield ActionResult.fail("Event, step name or media is invalid.");
                }
                boolean ok = plugin.studio().addEventStepNamed(session.actor(),
                        event,
                        step,
                        form.getOrDefault("stepType", "media"),
                        media,
                        form.getOrDefault("text", ""),
                        form.getOrDefault("duration", "30s"));
                yield result(ok, "Event step added.");
            }
            case "event.step.delete" -> {
                if (!session.can("events")) yield ActionResult.denied();
                boolean ok = plugin.studio().deleteEventStepNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("event")),
                        ScreenDefinition.normalizeId(form.get("step")));
                yield result(ok, "Event step deleted.");
            }
            case "group.create" -> {
                if (!session.can("groups")) yield ActionResult.denied();
                List<String> members = List.of(form.getOrDefault("screens", "").split(","));
                boolean ok = plugin.studio().createGroupNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("name")), members);
                yield result(ok, "Screen group created.");
            }
            case "schedule.create" -> {
                if (!session.can("schedules")) yield ActionResult.denied();
                boolean ok = plugin.studio().createScheduleNamed(session.actor(),
                        ScreenDefinition.normalizeId(form.get("name")),
                        form.getOrDefault("time", "20:00"),
                        form.getOrDefault("target", "main"),
                        form.getOrDefault("scheduleAction", "event"),
                        form.getOrDefault("value", ""));
                yield result(ok, "Schedule created.");
            }
            case "screen.start" -> control(session, () -> plugin.startScreen(screen), "Screen started.");
            case "screen.stop" -> control(session, () -> plugin.stopScreen(screen), "Screen stopped.");
            case "screen.resync" -> {
                if (!session.can("control")) yield ActionResult.denied();
                plugin.resyncScreen(screen);
                yield ActionResult.ok("Screen frames resynchronized.");
            }
            case "playback.pause" -> control(session,
                    () -> plugin.pausePlayback(screen, true), "Playback paused.");
            case "playback.resume" -> control(session,
                    () -> plugin.pausePlayback(screen, false), "Playback resumed.");
            case "playback.skip" -> control(session,
                    () -> plugin.skipPlayback(screen), "Current item skipped.");
            case "playback.repeat" -> control(session,
                    () -> plugin.togglePlaybackRepeat(screen), "Repeat toggled.");
            case "playback.return" -> control(session,
                    () -> plugin.returnToAutomation(screen), "Returned to automation.");
            case "playlist.assign" -> {
                if (!session.can("playlists") && !session.can("control")) {
                    yield ActionResult.denied();
                }
                boolean ok = plugin.setScreenPlaylist(screen,
                        ScreenDefinition.normalizeId(form.get("playlist")));
                yield result(ok, "Playlist assigned and started.");
            }
            case "playlist.clear" -> {
                if (!session.can("playlists") && !session.can("control")) {
                    yield ActionResult.denied();
                }
                yield result(plugin.clearScreenPlaylist(screen), "Playlist cleared.");
            }
            case "event.play" -> {
                if (!session.can("events") && !session.can("control")) {
                    yield ActionResult.denied();
                }
                boolean ok = plugin.playScreenEvent(screen,
                        ScreenDefinition.normalizeId(form.get("event")));
                yield result(ok, "Event started.");
            }
            case "event.stop" -> {
                if (!session.can("events") && !session.can("control")) {
                    yield ActionResult.denied();
                }
                yield result(plugin.stopScreenEvent(screen), "Event stopped.");
            }
            case "media.play", "media.queue" -> {
                if (!session.can("live") && !session.can("media")) {
                    yield ActionResult.denied();
                }
                MediaEntry media = plugin.studio().media(form.get("media"));
                if (media == null || !media.valid()) yield ActionResult.fail("Media is unavailable.");
                long duration = Math.max(1_000, parseLong(form.get("duration"), 30_000));
                List<String> targets = plugin.hasScreen(screen) ? List.of(screen)
                        : plugin.studio().groupScreens(screen);
                if (targets.isEmpty()) yield ActionResult.fail("Screen or group not found.");
                boolean ok = targets.stream().map(target -> plugin.queuePlayback(
                                target, media.source(), media.id(), duration,
                                action.equals("media.play")))
                        .reduce(true, (left, right) -> left && right);
                yield result(ok, action.equals("media.play")
                        ? "Media taken live." : "Media added to queue.");
            }
            case "group.start", "group.stop", "group.return" -> {
                if (!session.can("groups") && !session.can("control")) {
                    yield ActionResult.denied();
                }
                String operation = action.substring("group.".length());
                int changed = plugin.studio().applyGroup(null,
                        ScreenDefinition.normalizeId(form.get("group")), operation, "");
                yield result(changed > 0, "Group action applied to " + changed + " screens.");
            }
            case "emergency.enable", "emergency.disable" -> {
                if (!session.can("emergency")) yield ActionResult.denied();
                boolean enabled = action.endsWith("enable");
                plugin.studio().setEmergencyNamed(session.actor(), enabled);
                yield ActionResult.ok(enabled ? "Emergency mode enabled."
                        : "Emergency mode disabled.");
            }
            default -> ActionResult.fail("Unknown action.");
        };
    }

    private ActionResult control(StudioWebSecurity.Session session, Supplier<Boolean> operation,
                                 String message) {
        return session.can("control") ? result(operation.get(), message) : ActionResult.denied();
    }

    private ActionResult result(boolean success, String message) {
        return success ? ActionResult.ok(message) : ActionResult.fail("The action could not be applied.");
    }

    private int draftSize(String sessionId) {
        Map<String, Object> value = drafts.get(sessionId);
        return value == null ? 0 : value.size();
    }

    private void stageDraft(StudioWebSecurity.Session session, Map<String, Object> values) {
        draftBaselines.putIfAbsent(session.id(), configModified());
        drafts.computeIfAbsent(session.id(), ignored -> new LinkedHashMap<>()).putAll(values);
    }

    private boolean draftChangedExternally(String sessionId) {
        Long baseline = draftBaselines.get(sessionId);
        return baseline != null && configModified() != baseline;
    }

    private long configModified() {
        try {
            return Files.getLastModifiedTime(plugin.getDataFolder().toPath()
                    .resolve("config.yml")).toMillis();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private boolean allowedDraftPath(String path) {
        if (path == null || path.length() > 180 || path.contains("..")) return false;
        return path.matches("screens\\.[a-z0-9_-]{1,32}\\.(fps|distance|enabled|permission-required|playlist)")
                || path.matches("playlists\\.[a-z0-9_-]{1,32}\\.(default-duration|history-window|category-history-window)")
                || path.matches("playlists\\.[a-z0-9_-]{1,32}\\.items\\.[a-z0-9_-]{1,32}\\.(type|value|text|weight|duration|cooldown|enabled|guaranteed-after|category)")
                || path.matches("playlists\\.[a-z0-9_-]{1,32}\\.items\\.[a-z0-9_-]{1,32}\\.conditions\\.(min-online|max-online|min-viewers|max-viewers|any-viewer-permission|all-viewers-permission|tps-above|tps-below|world|chance)")
                || path.matches("events\\.[a-z0-9_-]{1,32}\\.(priority)")
                || path.matches("events\\.[a-z0-9_-]{1,32}\\.sequence\\.[a-z0-9_-]{1,32}\\.(type|value|text|duration|enabled|skippable)")
                || path.matches("events\\.[a-z0-9_-]{1,32}\\.sequence\\.[a-z0-9_-]{1,32}\\.conditions\\.(min-online|max-online|min-viewers|max-viewers|any-viewer-permission|all-viewers-permission|tps-above|tps-below|world|chance)")
                || Set.of("performance.adaptive-fps",
                "performance.max-map-updates-per-second",
                "performance.minimum-fps",
                "performance.pause-rendering-without-viewers",
                "screen.glowing-frames", "screen.dithering").contains(path);
    }

    private boolean canEditPath(StudioWebSecurity.Session session, String path) {
        if (session.can("configuration")) return true;
        if (path.startsWith("screens.")) return session.can("screens");
        if (path.startsWith("playlists.")) return session.can("playlists");
        if (path.startsWith("events.")) return session.can("events");
        return false;
    }

    private Object typedValue(String value, String type) {
        if (value == null || value.length() > 512) return null;
        try {
            return switch (type == null ? "string" : type) {
                case "boolean" -> switch (value.toLowerCase(Locale.ROOT)) {
                    case "true", "1", "on" -> true;
                    case "false", "0", "off" -> false;
                    default -> null;
                };
                case "integer" -> Integer.parseInt(value);
                case "double" -> {
                    double parsed = Double.parseDouble(value);
                    yield Double.isFinite(parsed) ? parsed : null;
                }
                case "duration", "string" -> value;
                default -> null;
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean validDraftValue(String path, Object value) {
        if (value instanceof String text && (text.indexOf('\0') >= 0
                || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)) {
            return false;
        }
        if (path.endsWith(".fps")) return numberBetween(value, 0.1, 20);
        if (path.endsWith(".distance")) return numberBetween(value, 8, 1024);
        if (path.equals("performance.max-map-updates-per-second")) {
            return numberBetween(value, 1, 100_000);
        }
        if (path.equals("performance.minimum-fps")) return numberBetween(value, 0.1, 20);
        if (path.endsWith(".weight") || path.endsWith(".priority")
                || path.endsWith(".history-window")
                || path.endsWith(".category-history-window")) {
            return numberBetween(value, 0, 1_000_000);
        }
        if (path.matches(".*\\.conditions\\.(min-online|max-online|min-viewers|max-viewers)$")) {
            return numberBetween(value, 0, 100_000);
        }
        if (path.matches(".*\\.conditions\\.(tps-above|tps-below)$")) {
            return numberBetween(value, 0, 20);
        }
        if (path.endsWith(".conditions.chance")) return numberBetween(value, 0, 1);
        if (path.endsWith(".duration") || path.endsWith(".cooldown")
                || path.endsWith(".guaranteed-after") || path.endsWith("default-duration")) {
            long duration = DurationParser.parseMillis(value, -1);
            return duration >= 0 && duration <= Duration.ofDays(7).toMillis();
        }
        if (path.endsWith(".playlist")) {
            String text = String.valueOf(value);
            return text.isBlank() || ScreenDefinition.isValidId(text);
        }
        return !(value instanceof String text) || text.length() <= 256;
    }

    private boolean numberBetween(Object value, double minimum, double maximum) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= minimum && number.doubleValue() <= maximum;
    }

    private StudioWebSecurity.Session requireSession(HttpExchange exchange) throws IOException {
        StudioWebSecurity.Session session = authenticate(exchange);
        if (session == null) sendJson(exchange, 401, Map.of("ok", false, "message", "Session expired."));
        return session;
    }

    private StudioWebSecurity.Session requireMutationSession(HttpExchange exchange)
            throws IOException {
        StudioWebSecurity.Session session = requireSession(exchange);
        if (session == null) return null;
        String csrf = exchange.getRequestHeaders().getFirst("X-LuigiScreen-CSRF");
        if (!constantEquals(session.csrf(), csrf) || !validOrigin(exchange)) {
            sendJson(exchange, 403, Map.of("ok", false, "message", "Request verification failed."));
            return null;
        }
        return session;
    }

    private StudioWebSecurity.Session authenticate(HttpExchange exchange) {
        return security.authenticate(cookie(exchange, COOKIE));
    }

    private boolean validOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) return true;
        Settings value = settings;
        if (value != null && !value.publicUrl().isBlank()) {
            return origin.equalsIgnoreCase(value.publicOrigin());
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        return host != null && (origin.equalsIgnoreCase("http://" + host)
                || origin.equalsIgnoreCase("https://" + host));
    }

    private void forbidden(HttpExchange exchange) throws IOException {
        sendJson(exchange, 403, Map.of("ok", false, "message", "Missing Studio permission."));
    }

    private void apiError(HttpExchange exchange, Exception exception) throws IOException {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        String message = cause instanceof TimeoutException ? "Server did not answer in time."
                : "Studio request failed: " + StreamUrlSanitizer.maskError(cause);
        sendJson(exchange, 500, Map.of("ok", false, "message", message));
    }

    private <T> T sync(Callable<T> callable) throws Exception {
        if (Bukkit.isPrimaryThread()) return callable.call();
        return Bukkit.getScheduler().callSyncMethod(plugin, callable).get(5, TimeUnit.SECONDS);
    }

    private Map<String, String> form(HttpExchange exchange) throws IOException {
        int declared = parseInt(exchange.getRequestHeaders().getFirst("Content-Length"), -1);
        if (declared > MAX_BODY) throw new IOException("Request body is too large");
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) throw new IOException("Request body is too large");
        return parseParameters(new String(body, StandardCharsets.UTF_8));
    }

    private Map<String, String> query(URI uri) {
        return parseParameters(uri.getRawQuery());
    }

    private Map<String, String> parseParameters(String encoded) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return result;
        for (String pair : encoded.split("&", 128)) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().getOrDefault("Cookie", List.of());
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) return pair[1];
            }
        }
        return null;
    }

    private void sendResource(HttpExchange exchange, String resource, String contentType)
            throws IOException {
        try (InputStream input = StudioWebServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendText(exchange, 404, "Resource not found");
                return;
            }
            byte[] bytes = input.readAllBytes();
            sendBytes(exchange, 200, contentType, bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8",
                StudioJson.write(value).getBytes(StandardCharsets.UTF_8));
    }

    private void sendText(HttpExchange exchange, int status, String value) throws IOException {
        sendBytes(exchange, status, "text/plain; charset=utf-8",
                value.getBytes(StandardCharsets.UTF_8));
    }

    private void sendHtml(HttpExchange exchange, int status, String value) throws IOException {
        sendBytes(exchange, status, "text/html; charset=utf-8",
                value.getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendText(exchange, 405, "Method not allowed");
    }

    private void securityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; "
                + "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; "
                + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    }

    private String secureCookie() {
        Settings value = settings;
        return value != null && value.baseUrl().startsWith("https://") ? "; Secure" : "";
    }

    private String loginPage() {
        return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>LuigiScreen Studio</title><style>body{font:16px sans-serif;"
                + "background:#0d1117;color:#e8edf3;display:grid;place-items:center;min-height:90vh}"
                + "main{max-width:560px;padding:32px;background:#151b24;border:1px solid #2a3545;"
                + "border-radius:18px}code{color:#65d99a}</style><main><h1>LuigiScreen Studio</h1>"
                + "<p>This panel needs a temporary administrator session.</p>"
                + "<p>Run <code>/screen web</code> in Minecraft or the server console, then open "
                + "the generated one-time link.</p></main></html>";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean constantEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private record ActionResult(boolean ok, String message) {
        static ActionResult ok(String message) {
            return new ActionResult(true, message);
        }

        static ActionResult fail(String message) {
            return new ActionResult(false, message);
        }

        static ActionResult denied() {
            return fail("Your Studio role does not allow this action.");
        }
    }

    private record SseClient(String sessionId, HttpExchange exchange, OutputStream output) {
        synchronized void send(String event, String data) throws IOException {
            output.write(("event: " + event + "\ndata: " + data + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        void close() {
            try {
                output.close();
            } catch (IOException ignored) {
            }
            exchange.close();
        }
    }

    private record Settings(boolean enabled, String bind, int port, String publicUrl,
                            int sessionHours, int loginMinutes, long updateMillis) {
        static Settings read(FileConfiguration config) {
            String bind = Objects.requireNonNullElse(
                    config.getString("web-studio.bind"), "127.0.0.1").trim();
            int port = Math.max(1, Math.min(65535,
                    config.getInt("web-studio.port", 8765)));
            String publicUrl = Objects.requireNonNullElse(
                    config.getString("web-studio.public-url"), "").trim();
            while (publicUrl.endsWith("/")) {
                publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
            }
            if (!publicUrl.isBlank() && !publicUrl.matches("https?://[^\\s]+")) {
                publicUrl = "";
            }
            return new Settings(config.getBoolean("web-studio.enabled", true),
                    bind.isBlank() ? "127.0.0.1" : bind, port, publicUrl,
                    Math.max(1, Math.min(168,
                            config.getInt("web-studio.session-hours", 8))),
                    Math.max(1, Math.min(30,
                            config.getInt("web-studio.login-token-minutes", 5))),
                    Math.max(500, Math.min(10_000,
                            config.getLong("web-studio.live-update-millis", 1000))));
        }

        String baseUrl() {
            if (!publicUrl.isBlank()) return publicUrl;
            String host = StudioWebAccess.isWildcardBind(bind) ? "127.0.0.1" : bind;
            return StudioWebAccess.url(host, port);
        }

        String publicOrigin() {
            if (publicUrl.isBlank()) return "";
            try {
                URI uri = URI.create(publicUrl);
                return uri.getScheme() + "://" + uri.getRawAuthority();
            } catch (IllegalArgumentException ignored) {
                return publicUrl;
            }
        }
    }
}
