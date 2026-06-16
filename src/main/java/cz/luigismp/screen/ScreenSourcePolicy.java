package cz.luigismp.screen;

import java.util.Collection;

public final class ScreenSourcePolicy {

    private ScreenSourcePolicy() {
    }

    static String key(ScreenSource source) {
        return source == null ? "" : source.key();
    }

    static long uniqueSourceCount(Collection<ScreenDefinition> definitions) {
        return definitions.stream()
                .map(ScreenDefinition::source)
                .map(ScreenSourcePolicy::key)
                .distinct()
                .count();
    }
}
