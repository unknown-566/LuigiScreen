package cz.luigismp.screen;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class RtmpStreamWorker {

    private final LuigiScreenPlugin plugin;
    private final SharedStreamSource source;
    private final String streamUrl;
    private final int reconnectDelaySeconds;
    private final int reconnectMaxDelaySeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean(true);
    private final AtomicReference<String> state = new AtomicReference<>("stopped");
    private final AtomicReference<String> lastError = new AtomicReference<>("none");
    private final AtomicInteger sourceWidth = new AtomicInteger();
    private final AtomicInteger sourceHeight = new AtomicInteger();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong lastFrameAt = new AtomicLong();

    private ExecutorService executor;

    RtmpStreamWorker(LuigiScreenPlugin plugin, SharedStreamSource source, String streamUrl,
                     int reconnectDelaySeconds, int reconnectMaxDelaySeconds) {
        this.plugin = plugin;
        this.source = source;
        this.streamUrl = streamUrl;
        this.reconnectDelaySeconds = Math.max(1, reconnectDelaySeconds);
        this.reconnectMaxDelaySeconds = Math.max(this.reconnectDelaySeconds, reconnectMaxDelaySeconds);
    }

    boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        terminated.set(false);

        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "LuigiScreen-RTMP-" + Integer.toUnsignedString(streamUrl.hashCode(), 36));
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::runLoop);
        return true;
    }

    boolean stop() {
        requestStop();
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null) {
            state.set("stopped");
            terminated.set(true);
            return true;
        }

        boolean stopped = false;
        try {
            stopped = currentExecutor.awaitTermination(4, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (!stopped) {
            plugin.getLogger().severe(
                    plugin.messages().plain("logs.decoder-stop-failed"));
            state.set("stopping");
            return false;
        }

        executor = null;
        state.set("stopped");
        return true;
    }

    void requestStop() {
        running.set(false);
        if (!terminated.get()) {
            state.set("stopping");
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            // Closing FFmpegFrameGrabber from another thread can crash the JVM.
            // Interrupt the owner thread and let try-with-resources close it.
            currentExecutor.shutdownNow();
        }
    }

    String state() {
        return state.get();
    }

    String lastError() {
        return lastError.get();
    }

    boolean isRunning() {
        return running.get();
    }

    boolean isTerminated() {
        return terminated.get();
    }

    int sourceWidth() {
        return sourceWidth.get();
    }

    int sourceHeight() {
        return sourceHeight.get();
    }

    long reconnects() {
        return reconnects.get();
    }

    long lastFrameAgeMillis() {
        long timestamp = lastFrameAt.get();
        return timestamp == 0 ? -1 : Math.max(0, System.currentTimeMillis() - timestamp);
    }

    private void runLoop() {
        try {
            int retryDelay = reconnectDelaySeconds;
            while (running.get()) {
                if (!source.shouldDecode()) {
                    state.set("paused (no viewers)");
                    lastError.set("none");
                    retryDelay = reconnectDelaySeconds;
                    if (!sleepMillis(500)) {
                        break;
                    }
                    continue;
                }

                try {
                    source.showFrame("screen.connecting");
                    boolean paused = receiveStream();
                    retryDelay = reconnectDelaySeconds;
                    if (paused) {
                        continue;
                    }
                } catch (Exception exception) {
                    if (running.get()) {
                        state.set("waiting for stream");
                        lastError.set(compactError(exception));
                        source.showFrame("screen.offline");
                    }
                }

                if (running.get()) {
                    if (!sleepMillis(TimeUnit.SECONDS.toMillis(retryDelay))) {
                        break;
                    }
                    retryDelay = Math.min(reconnectMaxDelaySeconds, retryDelay * 2);
                }
            }
        } finally {
            running.set(false);
            terminated.set(true);
            state.set("stopped");
        }
    }

    private boolean receiveStream() throws Exception {
        state.set("connecting");
        reconnects.incrementAndGet();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(streamUrl);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.setFormat("flv");
            grabber.setOption("analyzeduration", "10000000");
            grabber.setOption("probesize", "10000000");
            grabber.setOption("rw_timeout", "3000000");
            grabber.start();

            if (grabber.getImageWidth() <= 0 || grabber.getImageHeight() <= 0) {
                throw new IllegalStateException("video dimensions are missing");
            }

            sourceWidth.set(grabber.getImageWidth());
            sourceHeight.set(grabber.getImageHeight());
            state.set("live");
            lastError.set("none");
            plugin.getLogger().info(plugin.messages().plain(
                    "logs.stream-connected",
                    "url", StreamUrlSanitizer.mask(streamUrl),
                    "width", grabber.getImageWidth(),
                    "height", grabber.getImageHeight()));

            long nextRender = System.nanoTime();

            while (running.get()) {
                if (!source.shouldDecode()) {
                    state.set("paused (no viewers)");
                    lastError.set("none");
                    return true;
                }

                Frame frame = grabber.grabImage();
                if (frame == null) {
                    throw new IllegalStateException("stream ended");
                }
                if (frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0) {
                    continue;
                }
                lastFrameAt.set(System.currentTimeMillis());

                long now = System.nanoTime();
                long frameInterval = (long) (TimeUnit.SECONDS.toNanos(1)
                        / Math.max(0.1, Math.min(20, source.targetFps())));
                nextRender = Math.min(nextRender, now + frameInterval);
                if (now < nextRender) {
                    continue;
                }
                nextRender = now + frameInterval;
                BufferedImage source = converter.convert(frame);
                if (source != null) {
                    this.source.publish(copyFrame(source));
                }
            }
        }
        return false;
    }

    private static BufferedImage copyFrame(BufferedImage source) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static String compactError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        if (message.contains("Could not open input")) {
            return "stream offline";
        }
        message = StreamUrlSanitizer.mask(message);
        return message.length() > 80 ? message.substring(0, 80) : message;
    }

    private boolean sleepMillis(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
            return true;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
