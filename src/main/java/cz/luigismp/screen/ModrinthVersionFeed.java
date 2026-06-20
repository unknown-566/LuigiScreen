package cz.luigismp.screen;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ModrinthVersionFeed {

    private static final Pattern VERSION_NUMBER = Pattern.compile(
            "\\\"version_number\\\"\\s*:\\s*\\\"([^\\\"\\\\]{1,128})\\\"");

    private ModrinthVersionFeed() {
    }

    static Optional<String> latestNewerVersion(String json, String currentVersion) {
        Optional<PluginVersion> current = PluginVersion.parse(currentVersion);
        if (current.isEmpty() || json == null || json.isBlank()) {
            return Optional.empty();
        }

        String latestText = null;
        PluginVersion latest = null;
        Matcher matcher = VERSION_NUMBER.matcher(json);
        while (matcher.find()) {
            String candidateText = matcher.group(1);
            Optional<PluginVersion> candidate = PluginVersion.parse(candidateText);
            if (candidate.isPresent()
                    && candidate.get().compareTo(current.get()) > 0
                    && (latest == null || candidate.get().compareTo(latest) > 0)) {
                latestText = candidateText;
                latest = candidate.get();
            }
        }
        return Optional.ofNullable(latestText);
    }
}
