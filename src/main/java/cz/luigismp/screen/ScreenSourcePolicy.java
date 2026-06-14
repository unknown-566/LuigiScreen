package cz.luigismp.screen;

import java.util.Collection;

final class ScreenSourcePolicy {

    private ScreenSourcePolicy() {
    }

    static String key(String url) {
        return url == null ? "" : url.trim();
    }

    static long uniqueSourceCount(Collection<ScreenDefinition> definitions) {
        return definitions.stream()
                .map(ScreenDefinition::url)
                .map(ScreenSourcePolicy::key)
                .distinct()
                .count();
    }
}
