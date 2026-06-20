package cz.luigismp.screen;

record StudioStatistics(
        long plays,
        long plannedSeconds,
        long viewerSeconds,
        long viewerSamples,
        long viewerTotal,
        long skips,
        long failures
) {
    double averageViewers() {
        return viewerSamples < 1 ? 0 : viewerTotal / (double) viewerSamples;
    }
}
