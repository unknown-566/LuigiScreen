package cz.luigismp.screen;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenDefinitionTest {

    @Test
    void normalizesAndValidatesScreenNames() {
        assertEquals("spawn_tv", ScreenDefinition.normalizeId(" Spawn_TV "));
        assertTrue(ScreenDefinition.isValidId("cinema-2"));
        assertFalse(ScreenDefinition.isValidId("Cinema TV"));
        assertFalse(ScreenDefinition.isValidId(""));
    }

    @Test
    void storesEveryPerScreenSettingAndCalculatesTheSecondCorner() {
        ScreenDefinition screen = screen("main", "rtmp://example/screen", 8, 64);

        assertEquals(ScreenSource.rtmp("rtmp://example/screen"), screen.source());
        assertEquals(8, screen.fps());
        assertEquals(64, screen.distance());
        assertEquals("world", screen.world());
        assertEquals(new BlockVector(10, 70, 20), screen.location());
        assertEquals(7, screen.width());
        assertEquals(4, screen.height());
        assertEquals(BlockFace.SOUTH, screen.facing());
        assertTrue(screen.enabled());
        assertFalse(screen.permissionRequired());
        assertEquals(new BlockVector(16, 67, 20), screen.secondCorner());
    }

    @Test
    void cloneStyleCopiesShareAUrlButKeepIndependentSettings() {
        ScreenDefinition original = screen("main", " rtmp://example/screen ", 8, 64);
        ScreenDefinition clone = new ScreenDefinition(
                "lobby",
                original.source(),
                4,
                24,
                "world_nether",
                new BlockVector(5, 80, 5),
                original.width(),
                original.height(),
                BlockFace.WEST,
                false,
                true
        );

        assertEquals(ScreenSourcePolicy.key(original.source()),
                ScreenSourcePolicy.key(clone.source()));
        assertEquals(1, ScreenSourcePolicy.uniqueSourceCount(List.of(original, clone)));
        assertEquals(8, original.fps());
        assertEquals(4, clone.fps());
        assertEquals(64, original.distance());
        assertEquals(24, clone.distance());
        assertTrue(original.enabled());
        assertFalse(clone.enabled());
        assertFalse(original.permissionRequired());
        assertTrue(clone.permissionRequired());
    }

    @Test
    void changingAUrlCreatesAnotherSourceGroup() {
        ScreenDefinition first = screen("one", "rtmp://example/one", 8, 64);
        ScreenDefinition clone = screen("two", "rtmp://example/one", 8, 64);
        ScreenDefinition changed = clone.withSource(
                ScreenSource.rtmp("rtmp://example/two"));

        assertEquals(1, ScreenSourcePolicy.uniqueSourceCount(List.of(first, clone)));
        assertEquals(2, ScreenSourcePolicy.uniqueSourceCount(List.of(first, changed)));
    }

    @Test
    void sourceKeysIgnoreOnlyAccidentalOuterWhitespace() {
        assertEquals(
                ScreenSourcePolicy.key(ScreenSource.rtmp(" rtmp://example/screen ")),
                ScreenSourcePolicy.key(ScreenSource.rtmp("rtmp://example/screen"))
        );
        assertFalse(ScreenSourcePolicy.key(ScreenSource.rtmp("rtmp://example/one"))
                .equals(ScreenSourcePolicy.key(ScreenSource.rtmp("rtmp://example/two"))));
    }

    @Test
    void reloadKeepsDisplayWhenOnlyRuntimeSettingsChange() {
        ScreenDefinition original = screen("main", "rtmp://example/one", 8, 64);
        ScreenDefinition reloaded = new ScreenDefinition(
                "main",
                ScreenSource.rtmp("rtmp://example/two"),
                4,
                128,
                original.world(),
                original.location(),
                original.width(),
                original.height(),
                original.facing(),
                false,
                true
        );

        assertTrue(original.hasSameDisplayGeometry(reloaded));
    }

    @Test
    void reloadRecreatesDisplayWhenItsGeometryChanges() {
        ScreenDefinition original = screen("main", "rtmp://example/one", 8, 64);
        ScreenDefinition moved = new ScreenDefinition(
                "main",
                original.source(),
                original.fps(),
                original.distance(),
                original.world(),
                new BlockVector(11, 70, 20),
                original.width(),
                original.height(),
                original.facing(),
                original.enabled(),
                original.permissionRequired()
        );

        assertFalse(original.hasSameDisplayGeometry(moved));
    }

    @Test
    void reloadCanApplyRuntimeSettingsWithoutChangingGeometry() {
        ScreenDefinition original = screen("main", "rtmp://example/one", 8, 64);
        ScreenDefinition edited = new ScreenDefinition(
                "main",
                ScreenSource.rtmp("rtmp://example/two"),
                4,
                128,
                "another_world",
                new BlockVector(100, 90, 100),
                3,
                2,
                BlockFace.NORTH,
                false,
                true
        );

        ScreenDefinition merged = original.withRuntimeSettingsFrom(edited);

        assertTrue(original.hasSameDisplayGeometry(merged));
        assertEquals(edited.source(), merged.source());
        assertEquals(edited.fps(), merged.fps());
        assertEquals(edited.distance(), merged.distance());
        assertEquals(edited.enabled(), merged.enabled());
        assertEquals(edited.permissionRequired(), merged.permissionRequired());
    }

    private static ScreenDefinition screen(
            String id, String url, double fps, double distance) {
        return new ScreenDefinition(
                id,
                ScreenSource.rtmp(url),
                fps,
                distance,
                "world",
                new BlockVector(10, 70, 20),
                7,
                4,
                BlockFace.SOUTH,
                true,
                false
        );
    }
}
