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

        assertEquals("rtmp://example/screen", screen.url());
        assertEquals(8, screen.fps());
        assertEquals(64, screen.distance());
        assertEquals("world", screen.world());
        assertEquals(new BlockVector(10, 70, 20), screen.location());
        assertEquals(7, screen.width());
        assertEquals(4, screen.height());
        assertEquals(BlockFace.SOUTH, screen.facing());
        assertTrue(screen.enabled());
        assertEquals(new BlockVector(16, 67, 20), screen.secondCorner());
    }

    @Test
    void cloneStyleCopiesShareAUrlButKeepIndependentSettings() {
        ScreenDefinition original = screen("main", " rtmp://example/screen ", 8, 64);
        ScreenDefinition clone = new ScreenDefinition(
                "lobby",
                original.url(),
                4,
                24,
                "world_nether",
                new BlockVector(5, 80, 5),
                original.width(),
                original.height(),
                BlockFace.WEST,
                false
        );

        assertEquals(ScreenSourcePolicy.key(original.url()),
                ScreenSourcePolicy.key(clone.url()));
        assertEquals(1, ScreenSourcePolicy.uniqueSourceCount(List.of(original, clone)));
        assertEquals(8, original.fps());
        assertEquals(4, clone.fps());
        assertEquals(64, original.distance());
        assertEquals(24, clone.distance());
        assertTrue(original.enabled());
        assertFalse(clone.enabled());
    }

    @Test
    void changingAUrlCreatesAnotherSourceGroup() {
        ScreenDefinition first = screen("one", "rtmp://example/one", 8, 64);
        ScreenDefinition clone = screen("two", "rtmp://example/one", 8, 64);
        ScreenDefinition changed = clone.withUrl("rtmp://example/two");

        assertEquals(1, ScreenSourcePolicy.uniqueSourceCount(List.of(first, clone)));
        assertEquals(2, ScreenSourcePolicy.uniqueSourceCount(List.of(first, changed)));
    }

    @Test
    void sourceKeysIgnoreOnlyAccidentalOuterWhitespace() {
        assertEquals(
                ScreenSourcePolicy.key(" rtmp://example/screen "),
                ScreenSourcePolicy.key("rtmp://example/screen")
        );
        assertFalse(ScreenSourcePolicy.key("rtmp://example/one")
                .equals(ScreenSourcePolicy.key("rtmp://example/two")));
    }

    private static ScreenDefinition screen(
            String id, String url, double fps, double distance) {
        return new ScreenDefinition(
                id,
                url,
                fps,
                distance,
                "world",
                new BlockVector(10, 70, 20),
                7,
                4,
                BlockFace.SOUTH,
                true
        );
    }
}
