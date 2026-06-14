package cz.luigismp.screen;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenPolicyTest {

    private static final double EPSILON = 0.0001;

    @Test
    void calculatesSecondCornerForEveryVerticalFace() {
        BlockVector first = new BlockVector(10, 20, 30);

        assertEquals(new BlockVector(4, 17, 30),
                ScreenPolicy.secondCorner(first, BlockFace.NORTH, 7, 4));
        assertEquals(new BlockVector(16, 17, 30),
                ScreenPolicy.secondCorner(first, BlockFace.SOUTH, 7, 4));
        assertEquals(new BlockVector(10, 17, 24),
                ScreenPolicy.secondCorner(first, BlockFace.EAST, 7, 4));
        assertEquals(new BlockVector(10, 17, 36),
                ScreenPolicy.secondCorner(first, BlockFace.WEST, 7, 4));
    }

    @Test
    void rejectsHorizontalScreenFaces() {
        BlockVector first = new BlockVector(10, 20, 30);
        assertThrows(IllegalArgumentException.class,
                () -> ScreenPolicy.secondCorner(first, BlockFace.UP, 7, 4));
    }

    @Test
    void validatesDimensionsAndTotalMapLimit() {
        BlockVector first = new BlockVector(10, 20, 30);
        BlockVector valid = ScreenPolicy.secondCorner(first, BlockFace.SOUTH, 10, 6);
        BlockVector tooWide = ScreenPolicy.secondCorner(first, BlockFace.SOUTH, 11, 6);
        BlockVector tooManyMaps = ScreenPolicy.secondCorner(first, BlockFace.SOUTH, 10, 6);

        assertTrue(ScreenPolicy.isSizeAllowed(
                first, valid, BlockFace.SOUTH, 10, 6, 60));
        assertFalse(ScreenPolicy.isSizeAllowed(
                first, tooWide, BlockFace.SOUTH, 10, 6, 60));
        assertFalse(ScreenPolicy.isSizeAllowed(
                first, tooManyMaps, BlockFace.SOUTH, 10, 6, 59));
        assertFalse(ScreenPolicy.isSizeAllowed(
                first, valid, BlockFace.UP, 10, 6, 60));
    }

    @Test
    void clampsInvalidConfiguredLimits() {
        assertEquals(1, ScreenPolicy.maxDimension(-20));
        assertEquals(10, ScreenPolicy.maxDimension(10));
        assertEquals(50, ScreenPolicy.maxDimension(500));

        assertEquals(1, ScreenPolicy.maxTotalMaps(0));
        assertEquals(60, ScreenPolicy.maxTotalMaps(60));
        assertEquals(2500, ScreenPolicy.maxTotalMaps(10_000));
    }

    @Test
    void capsConfiguredFpsToSafeRange() {
        assertEquals(0.1,
                ScreenPolicy.effectiveFps(-5, false, 7, 4, 400, 0.2), EPSILON);
        assertEquals(20,
                ScreenPolicy.effectiveFps(200, false, 7, 4, 400, 0.2), EPSILON);
    }

    @Test
    void limitsAdaptiveFpsByMapUpdateBudget() {
        assertEquals(400.0 / 60.0,
                ScreenPolicy.effectiveFps(8, true, 10, 6, 400, 0.2), EPSILON);
    }

    @Test
    void respectsMinimumFpsForVeryLargeScreens() {
        assertEquals(0.2,
                ScreenPolicy.effectiveFps(8, true, 50, 50, 1, 0.2), EPSILON);
    }

    @Test
    void keepsRequestedFpsWithoutDisplayOrAdaptiveMode() {
        assertEquals(8,
                ScreenPolicy.effectiveFps(8, true, 0, 0, 400, 0.2), EPSILON);
        assertEquals(8,
                ScreenPolicy.effectiveFps(8, false, 50, 50, 1, 0.2), EPSILON);
    }
}
