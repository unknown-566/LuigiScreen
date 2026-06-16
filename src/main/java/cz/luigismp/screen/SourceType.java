package cz.luigismp.screen;

import java.util.List;
import java.util.Locale;

public enum SourceType {
    RTMP("rtmp", List.of("rtmps")),
    MJPEG("mjpeg", List.of("mjpg")),
    VIDEO("video", List.of("local-video")),
    IMAGE("image", List.of("local-image")),
    URL_IMAGE("url-image", List.of("image-url", "remote-image")),
    GIF("gif", List.of());

    private final String id;
    private final List<String> aliases;

    SourceType(String id, List<String> aliases) {
        this.id = id;
        this.aliases = aliases;
    }

    String id() {
        return id;
    }

    boolean isRemote() {
        return this == RTMP || this == MJPEG || this == URL_IMAGE;
    }

    boolean usesFfmpeg() {
        return this == RTMP || this == MJPEG || this == VIDEO || this == GIF;
    }

    boolean loopsAtEnd() {
        return this == VIDEO || this == GIF;
    }

    static SourceType parse(String value) {
        String normalized = value == null ? ""
                : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (SourceType type : values()) {
            if (type.id.equals(normalized) || type.aliases.contains(normalized)) {
                return type;
            }
        }
        return null;
    }

    static List<String> commandNames() {
        return java.util.Arrays.stream(values()).map(SourceType::id).toList();
    }
}
