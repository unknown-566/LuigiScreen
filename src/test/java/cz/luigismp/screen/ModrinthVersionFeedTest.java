package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModrinthVersionFeedTest {

    @Test
    void selectsTheHighestVersionNewerThanTheRunningPlugin() {
        String response = """
                [
                  {"version_number":"1.1.0-alpha.13","version_type":"alpha"},
                  {"version_number":"1.1.0-alpha.15","version_type":"alpha"},
                  {"version_number":"1.1.0-beta.1","version_type":"beta"},
                  {"version_number":"broken-label","version_type":"release"}
                ]
                """;

        assertEquals("1.1.0-beta.1", ModrinthVersionFeed.latestNewerVersion(
                response, "1.1.0-alpha.14").orElseThrow());
    }

    @Test
    void returnsEmptyWhenModrinthOnlyContainsOlderVersions() {
        String response = "[{\"version_number\":\"1.1.0-alpha.13\"}]";

        assertTrue(ModrinthVersionFeed.latestNewerVersion(
                response, "1.1.0-alpha.14").isEmpty());
    }

    @Test
    void returnsEmptyForAnInvalidResponseOrRunningVersion() {
        assertTrue(ModrinthVersionFeed.latestNewerVersion("not json", "1.1.0-alpha.14")
                .isEmpty());
        assertTrue(ModrinthVersionFeed.latestNewerVersion("[]", "development")
                .isEmpty());
    }
}
