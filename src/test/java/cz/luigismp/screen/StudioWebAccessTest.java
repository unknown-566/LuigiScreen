package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioWebAccessTest {

    @Test
    void recognizesLocalAndWildcardBinds() {
        assertTrue(StudioWebAccess.isLoopbackBind("127.0.0.1"));
        assertTrue(StudioWebAccess.isLoopbackBind("localhost"));
        assertFalse(StudioWebAccess.isLoopbackBind("0.0.0.0"));
        assertTrue(StudioWebAccess.isWildcardBind("0.0.0.0"));
        assertTrue(StudioWebAccess.isWildcardBind("::"));
    }

    @Test
    void localhostBindOnlyCreatesServerPcLink() {
        List<StudioWebAccess.Link> links = StudioWebAccess.loginLinks(
                "", "127.0.0.1", 8765, "abc");

        assertEquals(1, links.size());
        assertEquals("Server PC", links.getFirst().label());
        assertEquals("http://127.0.0.1:8765/login?token=abc", links.getFirst().url());
        assertTrue(links.getFirst().primary());
    }

    @Test
    void wildcardBindIncludesServerPcFallback() {
        List<StudioWebAccess.Link> links = StudioWebAccess.loginLinks(
                "", "0.0.0.0", 8765, "abc");

        assertFalse(links.isEmpty());
        assertTrue(links.stream().anyMatch(link -> link.url()
                .equals("http://127.0.0.1:8765/login?token=abc")));
        assertTrue(links.getFirst().primary());
    }

    @Test
    void publicUrlWinsOverDetectedHosts() {
        List<StudioWebAccess.Link> links = StudioWebAccess.loginLinks(
                "https://studio.example.com", "0.0.0.0", 8765, "abc");

        assertEquals(1, links.size());
        assertEquals("Public", links.getFirst().label());
        assertEquals("https://studio.example.com/login?token=abc", links.getFirst().url());
    }
}
