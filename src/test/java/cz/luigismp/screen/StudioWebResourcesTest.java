package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioWebResourcesTest {

    @Test
    void bundlesCompleteWebStudioShell() throws IOException {
        String html = resource("/web/index.html");
        String css = resource("/web/app.css");
        String javascript = resource("/web/app.js");

        for (String section : new String[]{"dashboard", "screens", "media", "playlists",
                "events", "automations", "live", "schedule", "monitoring",
                "diagnostics", "configuration", "settings"}) {
            assertTrue(html.contains("data-view=\"" + section + "\"")
                    || javascript.contains(section + ":"), section);
        }
        assertTrue(html.contains("id=\"inspector\""));
        assertTrue(html.contains("id=\"mobileControl\""));
        assertTrue(javascript.contains("new EventSource(\"/api/events\")"));
        assertTrue(javascript.contains("data-help"));
        assertTrue(javascript.contains("class=\"help-copy\""));
        assertTrue(javascript.contains("function renderLaunchpad"));
        assertTrue(javascript.contains("data-jump"));
        assertTrue(javascript.contains("data-screen-tab"));
        assertTrue(javascript.contains("function renderScreenAutomation"));
        assertTrue(javascript.contains("data-assign-playlist"));
        assertTrue(javascript.contains("data-clear-playlist"));
        assertTrue(javascript.contains("playlist.clear"));
        assertTrue(javascript.contains("data-add-media-to-playlist"));
        assertTrue(javascript.contains("data-delete-playlist"));
        assertTrue(javascript.contains("data-duplicate-playlist"));
        assertTrue(javascript.contains("data-delete-playlist-item"));
        assertTrue(javascript.contains("data-assign-selected-playlist"));
        assertTrue(javascript.contains("playlist.item.delete"));
        assertTrue(javascript.contains("playlist.delete"));
        assertTrue(javascript.contains("playlist.duplicate"));
        assertTrue(javascript.contains("data-start-event-detail"));
        assertTrue(css.contains(".launchpad"));
        assertTrue(css.contains(".launch-step.active"));
        assertTrue(css.contains(".screen-tabs"));
        assertTrue(css.contains(".nested-panel"));
        assertTrue(css.contains(".builder-hero"));
        assertTrue(css.contains(".playlist-workspace"));
        assertTrue(css.contains(".playlist-item-card"));
        assertTrue(css.contains(".card-actions"));
        assertTrue(javascript.contains("function attachHelp"));
        assertTrue(css.contains(".help-copy { display: none"));
        assertTrue(css.contains(".app-shell.inspector-active"));
        assertTrue(css.contains(".sidebar nav button b { display: none; }"));
        assertFalse(javascript.contains(">i</span>"));
        assertFalse(css.contains(".info:hover::after"));
        assertTrue(css.contains("@media (max-width: 900px)"));
        assertFalse(javascript.contains("rtmp://"));
    }

    @Test
    void defaultConfigKeepsStudioLanReadyAndSessionProtected() throws IOException {
        String config = resource("/config.yml");
        assertTrue(config.contains("language: en"));
        assertTrue(config.contains("web-studio:"));
        assertTrue(config.contains("bind: \"0.0.0.0\""));
        assertTrue(config.contains("login-token-minutes: 5"));
        assertTrue(config.contains("session-hours: 8"));
        assertTrue(config.contains("preview-refresh-millis: 1000"));
        assertTrue(config.contains("preview-max-width: 640"));
    }

    private String resource(String path) throws IOException {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
