package cz.luigismp.screen;

import com.sun.management.OperatingSystemMXBean;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class DebugBossBarManager {

    private static final int PAGE_COUNT = 9;
    private static final int SIDEBAR_LINES = 15;
    private static final String[] SIDEBAR_ENTRIES = {
            "\u00A70", "\u00A71", "\u00A72", "\u00A73", "\u00A74",
            "\u00A75", "\u00A76", "\u00A77", "\u00A78", "\u00A79",
            "\u00A7a", "\u00A7b", "\u00A7c", "\u00A7d", "\u00A7e"
    };

    private final LuigiScreenPlugin plugin;
    private final Map<UUID, DebugView> views = new HashMap<>();
    private BukkitTask task;
    private long lastSampleNanos;
    private long lastReceived;
    private long lastRendered;
    private long lastReplaced;
    private double receivedFps;
    private double renderedFps;
    private double replacedFps;

    DebugBossBarManager(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        stopTask();
        long updateTicks = Math.max(5,
                plugin.getConfig().getLong("debug.bossbar-update-ticks", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 1L, updateTicks);
    }

    void stop() {
        stopTask();
        for (Map.Entry<UUID, DebugView> entry : views.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                removeView(player, entry.getValue());
            }
        }
        views.clear();
    }

    boolean toggle(Player player) {
        DebugView existing = views.remove(player.getUniqueId());
        if (existing != null) {
            removeView(player, existing);
            return false;
        }

        BossBar bar = BossBar.bossBar(
                plugin.messages().component("debug.starting"),
                0,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS
        );
        player.showBossBar(bar);
        DebugView view = createView(player, bar);
        views.put(player.getUniqueId(), view);
        update();
        return true;
    }

    void remove(Player player) {
        DebugView view = views.remove(player.getUniqueId());
        if (view != null) {
            removeView(player, view);
        }
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void update() {
        if (views.isEmpty()) {
            resetRateSample();
            return;
        }

        views.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                if (player != null) {
                    removeView(player, entry.getValue());
                }
                return true;
            }
            return false;
        });
        if (views.isEmpty()) {
            resetRateSample();
            return;
        }

        DebugSnapshot screen = plugin.debugSnapshot();
        updateRates(screen);
        RuntimeSnapshot runtime = runtimeSnapshot();
        long pageDuration = Math.max(1,
                plugin.getConfig().getLong("debug.page-duration-seconds", 2));
        int page = (int) ((System.currentTimeMillis() / (pageDuration * 1000L)) % PAGE_COUNT);
        Page content = page(page, screen, runtime);

        for (DebugView view : views.values()) {
            view.bossBar().name(content.title());
            view.bossBar().color(content.color());
            view.bossBar().progress((float) clamp(content.progress()));
            updateSidebar(view, screen, runtime);
        }
    }

    private DebugView createView(Player player, BossBar bossBar) {
        Scoreboard previous = player.getScoreboard();
        if (!plugin.getConfig().getBoolean("debug.sidebar-enabled", true)) {
            return new DebugView(bossBar, previous, null, null, new Team[0]);
        }

        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(
                "lsdebug",
                Criteria.DUMMY,
                plugin.messages().component("debug.sidebar-title")
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        Team[] teams = new Team[SIDEBAR_LINES];
        for (int index = 0; index < SIDEBAR_LINES; index++) {
            Team team = scoreboard.registerNewTeam("lsd" + index);
            team.addEntry(SIDEBAR_ENTRIES[index]);
            objective.getScore(SIDEBAR_ENTRIES[index]).setScore(SIDEBAR_LINES - index);
            teams[index] = team;
        }
        player.setScoreboard(scoreboard);
        return new DebugView(bossBar, previous, scoreboard, objective, teams);
    }

    private void removeView(Player player, DebugView view) {
        player.hideBossBar(view.bossBar());
        if (view.scoreboard() != null && player.getScoreboard() == view.scoreboard()) {
            player.setScoreboard(view.previousScoreboard());
        }
    }

    private void updateSidebar(DebugView view, DebugSnapshot screen, RuntimeSnapshot runtime) {
        if (view.scoreboard() == null) {
            return;
        }
        view.objective().displayName(plugin.messages().component("debug.sidebar-title"));
        String resolution = screen.sourceWidth() > 0
                ? screen.sourceWidth() + "x" + screen.sourceHeight()
                : plugin.messages().plain("common.unknown");
        String none = plugin.messages().plain("common.none");
        String errors = sidebarError(screen, none);
        double lastRenderMs = screen.lastRenderNanos() / 1_000_000.0;
        double averageRenderMs = screen.averageRenderNanos() / 1_000_000.0;

        Component[] lines = {
                plugin.messages().component("debug.sidebar-stream",
                        "state", plugin.messages().state(screen.streamState())),
                plugin.messages().component("debug.sidebar-source",
                        "resolution", resolution,
                        "age", DebugText.age(screen.lastFrameAgeMillis(),
                                plugin.messages().plain("common.never"))),
                plugin.messages().component("debug.sidebar-screen",
                        "width", screen.mapWidth(), "height", screen.mapHeight(),
                        "maps", screen.mapCount()),
                plugin.messages().component("debug.sidebar-output",
                        "width", screen.pixelWidth(), "height", screen.pixelHeight()),
                plugin.messages().component("debug.sidebar-viewers",
                        "viewers", screen.viewers()),
                plugin.messages().component("debug.sidebar-fps",
                        "input", DebugText.decimal(receivedFps),
                        "output", DebugText.decimal(renderedFps)),
                plugin.messages().component("debug.sidebar-frames",
                        "received", screen.receivedFrames(),
                        "rendered", screen.renderedFrames()),
                plugin.messages().component("debug.sidebar-queue",
                        "queue", screen.frameQueued() ? "1/1" : "0/1",
                        "replaced", screen.replacedFrames()),
                plugin.messages().component("debug.sidebar-render",
                        "last", DebugText.decimal(lastRenderMs),
                        "average", DebugText.decimal(averageRenderMs)),
                plugin.messages().component("debug.sidebar-heap",
                        "used", DebugText.bytes(runtime.heapUsed()),
                        "max", DebugText.bytes(runtime.heapMax())),
                plugin.messages().component("debug.sidebar-buffers",
                        "buffers", DebugText.bytes(screen.estimatedImageBufferBytes())),
                plugin.messages().component("debug.sidebar-cpu",
                        "process", percentage(runtime.processCpu()),
                        "system", percentage(runtime.systemCpu())),
                plugin.messages().component("debug.sidebar-server",
                        "tps", DebugText.decimal(runtime.tps()),
                        "mspt", DebugText.decimal(runtime.mspt())),
                plugin.messages().component("debug.sidebar-runtime",
                        "uptime", DebugText.duration(runtime.uptimeMillis()),
                        "threads", runtime.threadCount()),
                plugin.messages().component("debug.sidebar-health",
                        "reconnects", screen.reconnects(),
                        "errors", errors)
        };
        for (int index = 0; index < lines.length; index++) {
            view.teams()[index].prefix(lines[index]);
        }
    }

    private String sidebarError(DebugSnapshot screen, String none) {
        if (!"none".equalsIgnoreCase(screen.streamError())) {
            return DebugText.shortError(
                    plugin.messages().error(screen.streamError()), none);
        }
        if (!"none".equalsIgnoreCase(screen.renderError())) {
            return DebugText.shortError(
                    plugin.messages().error(screen.renderError()), none);
        }
        return none;
    }

    private Page page(int page, DebugSnapshot screen, RuntimeSnapshot runtime) {
        return switch (page) {
            case 0 -> streamPage(screen);
            case 1 -> outputPage(screen);
            case 2 -> framePage(screen);
            case 3 -> timingPage(screen);
            case 4 -> memoryPage(runtime);
            case 5 -> bufferPage(screen);
            case 6 -> cpuPage(runtime);
            case 7 -> serverPage(runtime);
            default -> errorPage(screen);
        };
    }

    private Page streamPage(DebugSnapshot screen) {
        String resolution = screen.sourceWidth() > 0
                ? screen.sourceWidth() + "x" + screen.sourceHeight()
                : plugin.messages().plain("common.unknown");
        Component title = plugin.messages().component("debug.stream",
                "state", plugin.messages().state(screen.streamState()),
                "resolution", resolution,
                "age", DebugText.age(screen.lastFrameAgeMillis(),
                        plugin.messages().plain("common.never")));
        double progress = switch (screen.streamState()) {
            case "live" -> 1;
            case "connecting" -> 0.65;
            case "paused (no viewers)" -> 0.35;
            case "waiting for stream" -> 0.15;
            default -> 0;
        };
        BossBar.Color color = "live".equals(screen.streamState()) ? BossBar.Color.GREEN
                : screen.streamState().startsWith("paused")
                ? BossBar.Color.YELLOW : BossBar.Color.RED;
        return new Page(title, color, progress);
    }

    private Page outputPage(DebugSnapshot screen) {
        Component title = plugin.messages().component("debug.output",
                "width", screen.mapWidth(),
                "height", screen.mapHeight(),
                "maps", screen.mapCount(),
                "pixels_width", screen.pixelWidth(),
                "pixels_height", screen.pixelHeight(),
                "viewers", screen.viewers(),
                "fps", DebugText.decimal(renderedFps));
        return new Page(title, BossBar.Color.GREEN,
                screen.effectiveFps() <= 0 ? 0 : renderedFps / screen.effectiveFps());
    }

    private Page framePage(DebugSnapshot screen) {
        Component title = plugin.messages().component("debug.frames",
                "received", screen.receivedFrames(),
                "received_fps", DebugText.decimal(receivedFps),
                "rendered", screen.renderedFrames(),
                "rendered_fps", DebugText.decimal(renderedFps),
                "replaced", screen.replacedFrames(),
                "replaced_fps", DebugText.decimal(replacedFps),
                "queue", screen.frameQueued() ? "1/1" : "0/1");
        double progress = receivedFps <= 0 ? 1 : renderedFps / receivedFps;
        return new Page(title, screen.frameQueued()
                ? BossBar.Color.YELLOW : BossBar.Color.GREEN, progress);
    }

    private Page timingPage(DebugSnapshot screen) {
        double lastMs = screen.lastRenderNanos() / 1_000_000.0;
        double averageMs = screen.averageRenderNanos() / 1_000_000.0;
        double frameBudget = screen.effectiveFps() <= 0 ? 0 : 1000.0 / screen.effectiveFps();
        Component title = plugin.messages().component("debug.render",
                "last", DebugText.decimal(lastMs),
                "average", DebugText.decimal(averageMs),
                "budget", DebugText.decimal(frameBudget));
        return new Page(title, lastMs > frameBudget && frameBudget > 0
                ? BossBar.Color.RED : BossBar.Color.BLUE,
                frameBudget <= 0 ? 0 : 1 - lastMs / frameBudget);
    }

    private Page memoryPage(RuntimeSnapshot runtime) {
        Component title = plugin.messages().component("debug.memory",
                "heap_used", DebugText.bytes(runtime.heapUsed()),
                "heap_max", DebugText.bytes(runtime.heapMax()),
                "non_heap", DebugText.bytes(runtime.nonHeapUsed()),
                "system_used", DebugText.bytes(runtime.physicalUsed()),
                "system_total", DebugText.bytes(runtime.physicalTotal()));
        return new Page(title, BossBar.Color.PURPLE,
                runtime.heapMax() <= 0 ? 0 : (double) runtime.heapUsed() / runtime.heapMax());
    }

    private Page bufferPage(DebugSnapshot screen) {
        Component title = plugin.messages().component("debug.buffers",
                "buffers", DebugText.bytes(screen.estimatedImageBufferBytes()));
        return new Page(title, BossBar.Color.PURPLE,
                screen.mapCount() <= 0 ? 0 : Math.min(1, screen.mapCount() / 60.0));
    }

    private Page cpuPage(RuntimeSnapshot runtime) {
        Component title = plugin.messages().component("debug.cpu",
                "process", percentage(runtime.processCpu()),
                "system", percentage(runtime.systemCpu()),
                "threads", runtime.threadCount());
        return new Page(title, runtime.processCpu() >= 0.8
                ? BossBar.Color.RED : BossBar.Color.YELLOW,
                runtime.processCpu());
    }

    private Page serverPage(RuntimeSnapshot runtime) {
        Component title = plugin.messages().component("debug.server",
                "tps", DebugText.decimal(runtime.tps()),
                "mspt", DebugText.decimal(runtime.mspt()),
                "uptime", DebugText.duration(runtime.uptimeMillis()),
                "collections", runtime.gcCollections(),
                "gc_time", runtime.gcTimeMillis());
        return new Page(title, runtime.tps() < 18
                ? BossBar.Color.RED : BossBar.Color.BLUE,
                runtime.tps() / 20.0);
    }

    private Page errorPage(DebugSnapshot screen) {
        boolean healthy = "none".equalsIgnoreCase(screen.streamError())
                && "none".equalsIgnoreCase(screen.renderError());
        String none = plugin.messages().plain("common.none");
        Component title = plugin.messages().component("debug.health",
                "reconnects", screen.reconnects(),
                "stream_error", DebugText.shortError(
                        plugin.messages().error(screen.streamError()), none),
                "render_error", DebugText.shortError(
                        plugin.messages().error(screen.renderError()), none));
        return new Page(title, healthy
                ? BossBar.Color.GREEN : BossBar.Color.RED, healthy ? 1 : 0);
    }

    private void updateRates(DebugSnapshot snapshot) {
        long now = System.nanoTime();
        if (lastSampleNanos != 0) {
            double seconds = (now - lastSampleNanos) / 1_000_000_000.0;
            if (seconds > 0) {
                receivedFps = Math.max(0, snapshot.receivedFrames() - lastReceived) / seconds;
                renderedFps = Math.max(0, snapshot.renderedFrames() - lastRendered) / seconds;
                replacedFps = Math.max(0, snapshot.replacedFrames() - lastReplaced) / seconds;
            }
        }
        lastSampleNanos = now;
        lastReceived = snapshot.receivedFrames();
        lastRendered = snapshot.renderedFrames();
        lastReplaced = snapshot.replacedFrames();
    }

    private void resetRateSample() {
        lastSampleNanos = 0;
        receivedFps = 0;
        renderedFps = 0;
        replacedFps = 0;
    }

    private static RuntimeSnapshot runtimeSnapshot() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        java.lang.management.OperatingSystemMXBean baseOs =
                ManagementFactory.getOperatingSystemMXBean();
        double processCpu = -1;
        double systemCpu = -1;
        long physicalTotal = -1;
        long physicalFree = -1;
        if (baseOs instanceof OperatingSystemMXBean os) {
            processCpu = os.getProcessCpuLoad();
            systemCpu = os.getCpuLoad();
            physicalTotal = os.getTotalMemorySize();
            physicalFree = os.getFreeMemorySize();
        }

        long gcCollections = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCollections += Math.max(0, gc.getCollectionCount());
            gcTime += Math.max(0, gc.getCollectionTime());
        }

        double[] tpsValues = Bukkit.getTPS();
        return new RuntimeSnapshot(
                heap.getUsed(),
                heap.getMax(),
                nonHeap.getUsed(),
                physicalTotal < 0 || physicalFree < 0 ? -1 : physicalTotal - physicalFree,
                physicalTotal,
                normalizeLoad(processCpu),
                normalizeLoad(systemCpu),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                gcCollections,
                gcTime,
                tpsValues.length == 0 ? 20 : Math.min(20, tpsValues[0]),
                Bukkit.getAverageTickTime()
        );
    }

    private String percentage(double value) {
        return value < 0 ? plugin.messages().plain("common.not-available")
                : DebugText.decimal(value * 100) + "%";
    }

    private static double normalizeLoad(double value) {
        return value < 0 ? -1 : clamp(value);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record Page(Component title, BossBar.Color color, double progress) {
    }

    private record DebugView(
            BossBar bossBar,
            Scoreboard previousScoreboard,
            Scoreboard scoreboard,
            Objective objective,
            Team[] teams
    ) {
    }

    private record RuntimeSnapshot(
            long heapUsed,
            long heapMax,
            long nonHeapUsed,
            long physicalUsed,
            long physicalTotal,
            double processCpu,
            double systemCpu,
            int threadCount,
            long uptimeMillis,
            long gcCollections,
            long gcTimeMillis,
            double tps,
            double mspt
    ) {
    }
}
