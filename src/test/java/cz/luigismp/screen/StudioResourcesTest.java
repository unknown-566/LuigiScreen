package cz.luigismp.screen;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioResourcesTest {

    @Test
    void pluginDescriptorContainsStudioRolesAndVoting() {
        var stream = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(stream);
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertNotNull(plugin.getConfigurationSection("permissions.luigiscreen.menu.*"));
        assertNotNull(plugin.getConfigurationSection(
                "permissions.luigiscreen.menu.emergency"));
        assertNotNull(plugin.getConfigurationSection(
                "permissions.luigiscreen.menu.configuration"));
        assertNotNull(plugin.getConfigurationSection(
                "permissions.luigiscreen.menu.monitoring"));
        assertNotNull(plugin.getConfigurationSection("permissions.luigiscreen.web"));
        assertNotNull(plugin.getConfigurationSection("permissions.luigiscreen.vote"));
        assertTrue(plugin.getBoolean(
                "permissions.luigiscreen.admin.children.luigiscreen.menu.*"));
        assertTrue(plugin.getBoolean(
                "permissions.luigiscreen.admin.children.luigiscreen.vote"));
    }

    @Test
    void playbackSnapshotUsesImmutableEmptyCollectionsForDirectMode() {
        PlaybackSnapshot snapshot = PlaybackSnapshot.direct("configured source");
        assertTrue(snapshot.queue().isEmpty());
        assertTrue(snapshot.history().isEmpty());
    }
}
