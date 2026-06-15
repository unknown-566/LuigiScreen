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

final class FfmpegSourceWorker implements SourceWorker {

    private final LuigiScreenPlugin plugin;
    private final SharedMediaSource sharedSource;
    private final ScreenSource source;
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

    FfmpegSourceWorker(LuigiScreenPlugin plugin, SharedMediaSource sharedSource,
                       ScreenSource source, int reconnectDelaySeconds,
                       int reconnectMaxDelaySeconds) {
        this.plugin = plugin;
        this.sharedSource = sharedSource;
        this.source = source;
        this.reconnectDelaySeconds = Math.max(1, reconnectDelaySeconds);
        this.reconnectMaxDelaySeconds =
                Math.max(this.reconnectDelaySeconds, reconnectMaxDelaySeconds);
    }

    @Override
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        terminated.set(false);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "LuigiScreen-" + source.type().id() + "-"
                            + Integer.toUnsignedString(source.key().hashCode(), 36));
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::runLoop);
        return true;
    }

    @Override
    public boolean stop() {
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
            plugin.getLogger().severe(plugin.messages().plain("logs.decoder-stop-failed"));
            state.set("stopping");
            return false;
        }
        executor = null;
        state.set("stopped");
        return true;
    }

    @Override
    public void requestStop() {
        running.set(false);
        if (!terminated.get()) {
            state.set("stopping");
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
    }

    @Override
    public String state() {
        return state.get();
    }

    @Override
    public String lastError() {
        return lastError.get();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isTerminated() {
        return terminated.get();
    }

    @Override
    public int sourceWidth() {
        return sourceWidth.get();
    }

    @Override
    public int sourceHeight() {
        return sourceHeight.get();
    }

    @Override
    public long reconnects() {
        return reconnects.get();
    }

    @Override
    public long lastFrameAgeMillis() {
        long timestamp = lastFrameAt.get();
        return timestamp == 0 ? -1 : Math.max(0, System.currentTimeMillis() - timestamp);
    }

    private void runLoop() {
        try {
            int retryDelay = reconnectDelaySeconds;
            boolean showConnecting = true;
            while (running.get()) {
                if (!sharedSource.shouldDecode()) {
                    state.set("paused (no viewers)");
                    lastError.set("none");
                    retryDelay = reconnectDelaySeconds;
                    showConnecting = true;
                    if (!sleepMillis(500)) {
                        break;
                    }
                    continue;
                }

                try {
                    if (showConnecting) {
                        sharedSource.showFrame("screen.connecting");
                    }
                    ReceiveResult result = receive();
                    retryDelay = reconnectDelaySeconds;
                    if (result == ReceiveResult.LOOP) {
                        showConnecting = false;
                        continue;
                    }
                    if (result == ReceiveResult.PAUSED) {
                        showConnecting = true;
                        continue;
                    }
                } catch (Exception exception) {
                    if (running.get()) {
                        showConnecting = true;
                        state.set("waiting for source");
                        lastError.set(compactError(exception));
                        sharedSource.showFrame("screen.offline");
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

    private ReceiveResult receive() throws Exception {
        state.set("connecting");
        reconnects.incrementAndGet();
        String input = plugin.resolveSourceInput(source);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            configure(grabber);
            grabber.start();

            if (grabber.getImageWidth() <= 0 || grabber.getImageHeight() <= 0) {
                throw new IllegalStateException("video dimensions are missing");
            }
            sourceWidth.set(grabber.getImageWidth());
            sourceHeight.set(grabber.getImageHeight());
            state.set("live");
            lastError.set("none");
            plugin.getLogger().info(plugin.messages().plain(
                    "logs.source-connected",
                    "type", source.type().id(),
                    "value", source.displayValue(),
                    "width", grabber.getImageWidth(),
                    "height", grabber.getImageHeight()));

            long nextPublish = System.nanoTime();
            long playbackStarted = 0;
            long firstTimestamp = -1;

            while (running.get()) {
                if (!sharedSource.shouldDecode()) {
                    state.set("paused (no viewers)");
                    lastError.set("none");
                    return ReceiveResult.PAUSED;
                }

                Frame frame = grabber.grabImage();
                if (frame == null) {
                    if (source.type().loopsAtEnd()) {
                        return ReceiveResult.LOOP;
                    }
                    throw new IllegalStateException("source ended");
                }
                if (frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0) {
                    continue;
                }

                if (source.type().loopsAtEnd() && frame.timestamp >= 0) {
                    if (firstTimestamp < 0) {
                        firstTimestamp = frame.timestamp;
                        playbackStarted = System.nanoTime();
                    }
                    long target = playbackStarted
                            + Math.max(0, frame.timestamp - firstTimestamp) * 1_000L;
                    if (!sleepUntil(target)) {
                        return ReceiveResult.STOPPED;
                    }
                }

                lastFrameAt.set(System.currentTimeMillis());
                long now = System.nanoTime();
                long interval = (long) (TimeUnit.SECONDS.toNanos(1)
                        / Math.max(0.1, Math.min(20, sharedSource.targetFps())));
                if (now < nextPublish) {
                    continue;
                }
                nextPublish = now + interval;
                BufferedImage image = converter.convert(frame);
                if (image != null) {
                    sharedSource.publish(copyFrame(image));
                }
            }
        }
        return ReceiveResult.STOPPED;
    }

    private void configure(FFmpegFrameGrabber grabber) {
        if (source.type() == SourceType.RTMP) {
            grabber.setFormat("flv");
        }
        if (source.usesRemoteLocation()) {
            grabber.setOption("analyzeduration", "10000000");
            grabber.setOption("probesize", "10000000");
            grabber.setOption("rw_timeout", "5000000");
        }
    }

    private boolean sleepUntil(long targetNanos) {
        while (running.get()) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) {
                return true;
            }
            if (!sleepMillis(Math.min(
                    TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 100))) {
                return false;
            }
        }
        return false;
    }

    private static BufferedImage copyFrame(BufferedImage source) {
        BufferedImage output = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
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
            return "source offline";
        }
        message = StreamUrlSanitizer.mask(message);
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private boolean sleepMillis(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(1, millis));
            return true;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private enum ReceiveResult {
        PAUSED,
        LOOP,
        STOPPED
    }
}
