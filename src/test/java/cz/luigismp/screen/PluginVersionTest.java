package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginVersionTest {

    @Test
    void ordersAlphaBetaAndReleaseVersions() {
        PluginVersion alpha14 = version("1.1.0-alpha.14");
        PluginVersion alpha15 = version("1.1.0-alpha.15");
        PluginVersion beta1 = version("1.1.0-beta.1");
        PluginVersion release = version("1.1.0");

        assertTrue(alpha14.compareTo(alpha15) < 0);
        assertTrue(alpha15.compareTo(beta1) < 0);
        assertTrue(beta1.compareTo(release) < 0);
    }

    @Test
    void ignoresPrefixTrailingZerosAndBuildMetadata() {
        assertEquals(0, version("v1.2").compareTo(version("1.2.0")));
        assertEquals(0, version("1.2.0+build.9").compareTo(version("1.2.0+build.10")));
    }

    @Test
    void rejectsNonVersionLabels() {
        assertTrue(PluginVersion.parse("latest").isEmpty());
        assertTrue(PluginVersion.parse("").isEmpty());
    }

    private static PluginVersion version(String value) {
        return PluginVersion.parse(value).orElseThrow();
    }
}
