package cz.luigismp.screen;

import java.nio.file.Path;
import java.util.List;

record MediaEntry(
        String id,
        SourceType type,
        Path path,
        long sizeBytes,
        long modifiedMillis,
        int width,
        int height,
        Path thumbnail,
        boolean valid,
        String problem,
        List<String> references
) {
    ScreenSource source() {
        return new ScreenSource(type, id);
    }
}
