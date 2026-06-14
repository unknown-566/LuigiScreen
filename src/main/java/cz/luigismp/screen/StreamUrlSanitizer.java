package cz.luigismp.screen;

import java.util.regex.Pattern;

public final class StreamUrlSanitizer {

    private static final Pattern USER_INFO_PASSWORD =
            Pattern.compile("(?i)(://[^/@\\s:]+:)[^/@\\s]+@");
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile("(?i)([?&](?:pass|password|token|secret|auth|key|stream_key)=)[^&#\\s]*");
    private static final Pattern PATH_STREAM_KEY =
            Pattern.compile("(?i)(rtmps?://[^/?#\\s]+/[^/?#\\s]+/)[^/?#\\s]+");

    private StreamUrlSanitizer() {
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String masked = USER_INFO_PASSWORD.matcher(value).replaceAll("$1***@");
        masked = SENSITIVE_QUERY.matcher(masked).replaceAll("$1***");
        return PATH_STREAM_KEY.matcher(masked).replaceAll("$1***");
    }
}
