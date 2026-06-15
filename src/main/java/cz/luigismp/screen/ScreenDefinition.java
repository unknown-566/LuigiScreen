package cz.luigismp.screen;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

record ScreenDefinition(
        String id,
        String url,
        double fps,
        double distance,
        String world,
        BlockVector location,
        int width,
        int height,
        BlockFace facing,
        boolean enabled,
        boolean permissionRequired
) {

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    ScreenDefinition {
        id = normalizeId(id);
        url = url == null ? "" : url.trim();
        world = world == null ? "" : world.trim();
        location = location == null ? null : location.clone();
        fps = Math.max(0.1, Math.min(20, fps));
        distance = Math.max(8, distance);
    }

    static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isValidId(String value) {
        return VALID_ID.matcher(normalizeId(value)).matches();
    }

    ScreenDefinition withUrl(String value) {
        return new ScreenDefinition(id, value, fps, distance, world, location,
                width, height, facing, enabled, permissionRequired);
    }

    ScreenDefinition withFps(double value) {
        return new ScreenDefinition(id, url, value, distance, world, location,
                width, height, facing, enabled, permissionRequired);
    }

    ScreenDefinition withDistance(double value) {
        return new ScreenDefinition(id, url, fps, value, world, location,
                width, height, facing, enabled, permissionRequired);
    }

    ScreenDefinition withEnabled(boolean value) {
        return new ScreenDefinition(id, url, fps, distance, world, location,
                width, height, facing, value, permissionRequired);
    }

    ScreenDefinition withPermissionRequired(boolean value) {
        return new ScreenDefinition(id, url, fps, distance, world, location,
                width, height, facing, enabled, value);
    }

    boolean hasSameDisplayGeometry(ScreenDefinition other) {
        return other != null
                && Objects.equals(world, other.world)
                && Objects.equals(location, other.location)
                && width == other.width
                && height == other.height
                && facing == other.facing;
    }

    ScreenDefinition withRuntimeSettingsFrom(ScreenDefinition other) {
        return new ScreenDefinition(
                id,
                other.url,
                other.fps,
                other.distance,
                world,
                location,
                width,
                height,
                facing,
                other.enabled,
                other.permissionRequired
        );
    }

    BlockVector secondCorner() {
        return ScreenPolicy.secondCorner(location, facing, width, height);
    }
}
