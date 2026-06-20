package cz.luigismp.screen;

import java.util.List;

record PlaybackSnapshot(
        String mode,
        String current,
        String next,
        String controller,
        String reason,
        long remainingMillis,
        boolean paused,
        boolean repeat,
        List<String> queue,
        List<String> history
) {
    static PlaybackSnapshot direct(String reason) {
        return new PlaybackSnapshot("direct", "direct source", "automatic",
                "screen", reason, -1, false, false, List.of(), List.of());
    }
}
