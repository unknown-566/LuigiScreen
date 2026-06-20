package cz.luigismp.screen;

record ScreenHealth(
        String state,
        String error,
        long lastFrameAgeMillis,
        long reconnects,
        long receivedFrames,
        long renderedFrames,
        long droppedFrames,
        double targetFps,
        double effectiveFps,
        long lastRenderNanos,
        long averageRenderNanos,
        int viewers,
        int sharedScreens
) {
}
