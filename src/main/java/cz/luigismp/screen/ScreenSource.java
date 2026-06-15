package cz.luigismp.screen;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

record ScreenSource(SourceType type, String value) {

    ScreenSource {
        type = type == null ? SourceType.RTMP : type;
        value = value == null ? "" : value.trim();
    }

    static ScreenSource rtmp(String value) {
        return new ScreenSource(SourceType.RTMP, value);
    }

    static ScreenSource parse(String type, String value) {
        SourceType parsed = SourceType.parse(type);
        return parsed == null ? null : new ScreenSource(parsed, value);
    }

    boolean isValid() {
        if (value.isBlank()) {
            return false;
        }
        return switch (type) {
            case RTMP -> hasScheme("rtmp", "rtmps");
            case MJPEG, URL_IMAGE -> hasScheme("http", "https");
            case VIDEO, IMAGE -> !looksLikeUrl();
            case GIF -> !looksLikeUrl() || hasScheme("http", "https");
        };
    }

    boolean usesRemoteLocation() {
        return type.isRemote() || (type == SourceType.GIF && looksLikeRemoteUrl());
    }

    String key() {
        String normalized = usesRemoteLocation() ? value : normalizedLocalPath();
        return type.id() + "\u0000" + normalized;
    }

    String displayValue() {
        if (usesRemoteLocation()) {
            return StreamUrlSanitizer.mask(value);
        }
        try {
            Path path = Path.of(value);
            Path fileName = path.getFileName();
            return fileName == null ? value : fileName.toString();
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private boolean hasScheme(String... allowed) {
        try {
            String scheme = URI.create(value).getScheme();
            if (scheme == null) {
                return false;
            }
            String normalized = scheme.toLowerCase(Locale.ROOT);
            return java.util.Arrays.stream(allowed).anyMatch(normalized::equals);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean looksLikeRemoteUrl() {
        return hasScheme("http", "https");
    }

    private boolean looksLikeUrl() {
        return value.contains("://");
    }

    private String normalizedLocalPath() {
        try {
            return Path.of(value).normalize().toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            return value.replace('\\', '/');
        }
    }
}
