package cz.luigismp.screen;

import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

final class SharedMediaSource {

    private final LuigiScreenPlugin plugin;
    private final ScreenSource source;
    private final boolean pauseWithoutViewers;
    private final CopyOnWriteArraySet<ManagedScreen> screens = new CopyOnWriteArraySet<>();
    private volatile SourceWorker worker;

    SharedMediaSource(LuigiScreenPlugin plugin, ScreenSource source) {
        this.plugin = plugin;
        this.source = source;
        this.pauseWithoutViewers = plugin.getConfig().getBoolean(
                "performance.pause-rendering-without-viewers", true);
    }

    ScreenSource source() {
        return source;
    }

    void attach(ManagedScreen screen) {
        screens.add(screen);
    }

    void detach(ManagedScreen screen) {
        screens.remove(screen);
    }

    boolean isUnused() {
        return screens.isEmpty();
    }

    int screenCount() {
        return screens.size();
    }

    List<String> screenIds() {
        return screens.stream().map(ManagedScreen::id).sorted().toList();
    }

    boolean hasEnabledScreens() {
        return screens.stream().anyMatch(ManagedScreen::enabled);
    }

    boolean shouldDecode() {
        if (!hasEnabledScreens()) {
            return false;
        }
        if (!pauseWithoutViewers) {
            return true;
        }
        return screens.stream().anyMatch(screen -> screen.enabled() && screen.hasViewers());
    }

    double targetFps() {
        return screens.stream()
                .filter(screen -> screen.enabled()
                        && (!pauseWithoutViewers || screen.hasViewers()))
                .map(ManagedScreen::effectiveFps)
                .max(Comparator.naturalOrder())
                .orElse(0.1);
    }

    synchronized boolean start() {
        if (!hasEnabledScreens()) {
            return false;
        }
        SourceWorker current = worker;
        if (current != null && !current.isTerminated()) {
            return false;
        }
        worker = createWorker();
        return worker.start();
    }

    private SourceWorker createWorker() {
        if (source.type().usesFfmpeg()) {
            return new FfmpegSourceWorker(
                    plugin,
                    this,
                    source,
                    plugin.getConfig().getInt("stream.reconnect-delay-seconds", 3),
                    plugin.getConfig().getInt("stream.reconnect-max-delay-seconds", 30),
                    plugin.getConfig().getLong("stream.io-timeout-seconds", 5),
                    plugin.getConfig().getLong(
                            "performance.worker-stop-timeout-seconds", 8)
            );
        }
        return new ImageSourceWorker(plugin, this, source);
    }

    synchronized boolean stop() {
        SourceWorker current = worker;
        if (current == null) {
            return true;
        }
        boolean stopped = current.stop();
        if (stopped) {
            worker = null;
        }
        return stopped;
    }

    void requestStop() {
        SourceWorker current = worker;
        if (current != null) {
            current.requestStop();
        }
    }

    boolean isTerminated() {
        SourceWorker current = worker;
        return current == null || current.isTerminated();
    }

    boolean isRunning() {
        SourceWorker current = worker;
        return current != null && current.isRunning();
    }

    String state() {
        SourceWorker current = worker;
        return current == null ? "stopped" : current.state();
    }

    String lastError() {
        SourceWorker current = worker;
        return current == null ? "none" : current.lastError();
    }

    int sourceWidth() {
        SourceWorker current = worker;
        return current == null ? 0 : current.sourceWidth();
    }

    int sourceHeight() {
        SourceWorker current = worker;
        return current == null ? 0 : current.sourceHeight();
    }

    long reconnects() {
        SourceWorker current = worker;
        return current == null ? 0 : current.reconnects();
    }

    long lastFrameAgeMillis() {
        SourceWorker current = worker;
        return current == null ? -1 : current.lastFrameAgeMillis();
    }

    void publish(BufferedImage image) {
        SharedVideoFrame frame = new SharedVideoFrame(image);
        try {
            for (ManagedScreen screen : screens) {
                if (screen.enabled()) {
                    screen.offerFrame(frame);
                }
            }
        } finally {
            frame.release();
        }
    }

    void showFrame(String messageKey) {
        String message = plugin.messages().plain(messageKey);
        for (ManagedScreen screen : screens) {
            if (screen.enabled()) {
                screen.showOfflineFrame(message);
            }
        }
    }
}
