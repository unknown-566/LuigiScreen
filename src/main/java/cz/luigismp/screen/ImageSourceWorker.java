package cz.luigismp.screen;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class ImageSourceWorker implements SourceWorker {

    private final LuigiScreenPlugin plugin;
    private final SharedMediaSource sharedSource;
    private final ScreenSource source;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean(true);
    private final AtomicReference<String> state = new AtomicReference<>("stopped");
    private final AtomicReference<String> lastError = new AtomicReference<>("none");
    private final AtomicInteger sourceWidth = new AtomicInteger();
    private final AtomicInteger sourceHeight = new AtomicInteger();
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong lastFrameAt = new AtomicLong();
    private ExecutorService executor;

    ImageSourceWorker(LuigiScreenPlugin plugin, SharedMediaSource sharedSource,
                      ScreenSource source) {
        this.plugin = plugin;
        this.sharedSource = sharedSource;
        this.source = source;
    }

    @Override
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        terminated.set(false);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "LuigiScreen-image-"
                            + Integer.toUnsignedString(source.key().hashCode(), 36));
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::run);
        return true;
    }

    private void run() {
        try {
            int retryDelay = Math.max(1, plugin.getConfig().getInt(
                    "stream.reconnect-delay-seconds", 3));
            int retryMax = Math.max(retryDelay, plugin.getConfig().getInt(
                    "stream.reconnect-max-delay-seconds", 30));
            while (running.get()) {
                while (running.get() && !sharedSource.shouldDecode()) {
                    state.set("paused (no viewers)");
                    if (!sleepMillis(250)) {
                        return;
                    }
                }
                if (!running.get()) {
                    return;
                }

                try {
                    state.set("loading");
                    sharedSource.showFrame("screen.loading");
                    loads.incrementAndGet();
                    BufferedImage image = loadImage();
                    if (image == null) {
                        throw new IllegalArgumentException("unsupported or invalid image");
                    }
                    long pixels = (long) image.getWidth() * image.getHeight();
                    long maxPixels = Math.max(1, plugin.getConfig().getLong(
                            "sources.max-image-pixels", 16_777_216L));
                    if (pixels > maxPixels) {
                        throw new IllegalArgumentException("image exceeds max-image-pixels");
                    }
                    BufferedImage converted = copyFrame(image);
                    sourceWidth.set(converted.getWidth());
                    sourceHeight.set(converted.getHeight());
                    lastFrameAt.set(System.currentTimeMillis());
                    lastError.set("none");
                    state.set("live");
                    sharedSource.publish(converted);

                    while (running.get()) {
                        sleepMillis(500);
                    }
                } catch (Exception exception) {
                    if (!running.get()) {
                        return;
                    }
                    lastError.set(compactError(exception));
                    state.set("waiting for source");
                    sharedSource.showFrame("screen.offline");
                    if (source.type() != SourceType.URL_IMAGE) {
                        return;
                    }
                    if (!sleepMillis(TimeUnit.SECONDS.toMillis(retryDelay))) {
                        return;
                    }
                    retryDelay = Math.min(retryMax, retryDelay * 2);
                }
            }
        } finally {
            running.set(false);
            terminated.set(true);
            state.set("stopped");
        }
    }

    private BufferedImage loadImage() throws Exception {
        if (source.type() == SourceType.URL_IMAGE) {
            return loadRemoteImage();
        }
        Path path = Path.of(plugin.resolveSourceInput(source));
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            return ImageIO.read(input);
        }
    }

    private BufferedImage loadRemoteImage() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(source.value())
                .toURL().openConnection();
        int timeout = (int) Duration.ofSeconds(
                Math.max(1, plugin.getConfig().getInt(
                        "sources.http-timeout-seconds", 10))).toMillis();
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "LuigiScreen/1");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status);
        }
        int maxBytes = Math.max(1, plugin.getConfig().getInt(
                "sources.max-image-bytes", 16 * 1024 * 1024));
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("image exceeds max-image-bytes");
                }
                output.write(buffer, 0, read);
            }
            return ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public boolean stop() {
        requestStop();
        ExecutorService current = executor;
        if (current == null) {
            terminated.set(true);
            state.set("stopped");
            return true;
        }
        try {
            boolean stopped = current.awaitTermination(4, TimeUnit.SECONDS);
            if (stopped) {
                executor = null;
                state.set("stopped");
            } else {
                state.set("stopping");
                plugin.getLogger().severe(
                        plugin.messages().plain("logs.decoder-stop-failed"));
            }
            return stopped;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void requestStop() {
        running.set(false);
        ExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
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
        return loads.get();
    }

    @Override
    public long lastFrameAgeMillis() {
        long timestamp = lastFrameAt.get();
        return timestamp == 0 ? -1 : Math.max(0, System.currentTimeMillis() - timestamp);
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
        return StreamUrlSanitizer.mask(message);
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
