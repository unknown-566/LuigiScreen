package cz.luigismp.screen;

import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

final class SharedStreamSource {

    private final LuigiScreenPlugin plugin;
    private final String url;
    private final CopyOnWriteArraySet<ManagedScreen> screens = new CopyOnWriteArraySet<>();
    private volatile RtmpStreamWorker worker;

    SharedStreamSource(LuigiScreenPlugin plugin, String url) {
        this.plugin = plugin;
        this.url = url;
    }

    String url() {
        return url;
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
        if (!plugin.getConfig().getBoolean(
                "performance.pause-rendering-without-viewers", true)) {
            return true;
        }
        return screens.stream().anyMatch(screen -> screen.enabled() && screen.hasViewers());
    }

    double targetFps() {
        boolean pauseWithoutViewers = plugin.getConfig().getBoolean(
                "performance.pause-rendering-without-viewers", true);
        return screens.stream()
                .filter(screen -> screen.enabled()
                        && (!pauseWithoutViewers || screen.hasViewers()))
                .map(ManagedScreen::effectiveFps)
                .max(Comparator.naturalOrder())
                .orElse(0.1);
    }

    boolean start() {
        if (!hasEnabledScreens()) {
            return false;
        }
        RtmpStreamWorker current = worker;
        if (current != null && !current.isTerminated()) {
            return false;
        }
        worker = new RtmpStreamWorker(
                plugin,
                this,
                url,
                plugin.getConfig().getInt("stream.reconnect-delay-seconds", 3),
                plugin.getConfig().getInt("stream.reconnect-max-delay-seconds", 30)
        );
        return worker.start();
    }

    boolean stop() {
        RtmpStreamWorker current = worker;
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
        RtmpStreamWorker current = worker;
        if (current != null) {
            current.requestStop();
        }
    }

    boolean isTerminated() {
        RtmpStreamWorker current = worker;
        return current == null || current.isTerminated();
    }

    boolean isRunning() {
        RtmpStreamWorker current = worker;
        return current != null && current.isRunning();
    }

    String state() {
        RtmpStreamWorker current = worker;
        return current == null ? "stopped" : current.state();
    }

    String lastError() {
        RtmpStreamWorker current = worker;
        return current == null ? "none" : current.lastError();
    }

    int sourceWidth() {
        RtmpStreamWorker current = worker;
        return current == null ? 0 : current.sourceWidth();
    }

    int sourceHeight() {
        RtmpStreamWorker current = worker;
        return current == null ? 0 : current.sourceHeight();
    }

    long reconnects() {
        RtmpStreamWorker current = worker;
        return current == null ? 0 : current.reconnects();
    }

    long lastFrameAgeMillis() {
        RtmpStreamWorker current = worker;
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
