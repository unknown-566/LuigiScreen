package cz.luigismp.screen;

record PlaybackItemView(
        String id,
        String type,
        String value,
        int weight,
        double probability,
        long durationMillis,
        long cooldownMillis,
        boolean enabled,
        String conditions
) {
}
