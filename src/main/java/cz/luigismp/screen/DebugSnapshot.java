package cz.luigismp.screen;

record DebugSnapshot(
        String streamState,
        String streamError,
        int sourceWidth,
        int sourceHeight,
        long reconnects,
        long lastFrameAgeMillis,
        int mapWidth,
        int mapHeight,
        int pixelWidth,
        int pixelHeight,
        int viewers,
        long receivedFrames,
        long renderedFrames,
        long replacedFrames,
        boolean frameQueued,
        long lastRenderNanos,
        long averageRenderNanos,
        long estimatedImageBufferBytes,
        double effectiveFps,
        String renderError,
        int screenCount,
        int enabledScreenCount,
        int sourceCount
) {
    int mapCount() {
        return mapWidth * mapHeight;
    }
}
