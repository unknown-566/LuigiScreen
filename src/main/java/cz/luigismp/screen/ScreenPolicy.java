package cz.luigismp.screen;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;

final class ScreenPolicy {

    private ScreenPolicy() {
    }

    static int maxDimension(int configured) {
        return Math.max(1, Math.min(50, configured));
    }

    static int maxTotalMaps(int configured) {
        return Math.max(1, Math.min(2500, configured));
    }

    static BlockVector secondCorner(BlockVector first, BlockFace face, int width, int height) {
        BlockVector second = first.clone();
        second.setY(first.getBlockY() - height + 1);

        switch (face) {
            case NORTH -> second.setX(first.getBlockX() - width + 1);
            case SOUTH -> second.setX(first.getBlockX() + width - 1);
            case EAST -> second.setZ(first.getBlockZ() - width + 1);
            case WEST -> second.setZ(first.getBlockZ() + width - 1);
            default -> throw new IllegalArgumentException("Screen must be vertical");
        }
        return second;
    }

    static boolean isSizeAllowed(BlockVector first, BlockVector second, BlockFace face,
                                 int maxWidth, int maxHeight, int maxMaps) {
        int width = switch (face) {
            case NORTH, SOUTH -> Math.abs(first.getBlockX() - second.getBlockX()) + 1;
            case EAST, WEST -> Math.abs(first.getBlockZ() - second.getBlockZ()) + 1;
            default -> 0;
        };
        int height = Math.abs(first.getBlockY() - second.getBlockY()) + 1;
        return width >= 1
                && height >= 1
                && width <= maxWidth
                && height <= maxHeight
                && (long) width * height <= maxMaps;
    }

    static double effectiveFps(double configuredFps, boolean adaptive, int width, int height,
                               double configuredBudget, double configuredMinimum) {
        double requested = Math.max(0.1, Math.min(20, configuredFps));
        if (!adaptive || width <= 0 || height <= 0) {
            return requested;
        }

        long maps = Math.max(1L, (long) width * height);
        double budget = Math.max(1, configuredBudget);
        double minimum = Math.max(0.1, Math.min(requested, configuredMinimum));
        return Math.min(requested, Math.max(minimum, budget / maps));
    }
}
