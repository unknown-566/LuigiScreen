package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaMtxConfigGeneratorTest {

    private static final String TEMPLATE = """
            authInternalUsers:
            @@AUTH_USERS@@
            rtmpAddress: :@@RTMP_PORT@@
            paths:
              screen:
                source: publisher
            """;

    @Test
    void generatesLocalInternetSetupWithSeparatePublicPort() {
        MediaMtxConfigGenerator.Output output = MediaMtxConfigGenerator.generate(
                TEMPLATE,
                new MediaMtxConfigGenerator.Request(
                        MediaMtxSituation.INTERNET,
                        "stream.example.net",
                        33356,
                        "127.0.0.1",
                        55556,
                        "192.168.1.64",
                        "publish-secret",
                        "",
                        false
                )
        );

        assertEquals(
                "rtmp://stream.example.net:33356/screen?user=streamer&pass=publish-secret",
                output.obsUrl()
        );
        assertEquals("rtmp://127.0.0.1:55556/screen", output.pluginUrl());
        assertTrue(output.config().contains("rtmpAddress: :55556"));
        assertTrue(output.config().contains("ips: [\"127.0.0.1\", \"::1\"]"));
        assertFalse(output.config().contains("@@"));
    }

    @Test
    void generatesHostingSetupWithReadOnlyCredentials() {
        MediaMtxConfigGenerator.Output output = MediaMtxConfigGenerator.generate(
                TEMPLATE,
                new MediaMtxConfigGenerator.Request(
                        MediaMtxSituation.HOSTING,
                        "media.example.net",
                        1935,
                        "media.example.net",
                        1935,
                        "",
                        "publish-secret",
                        "read-secret",
                        true
                )
        );

        assertEquals(
                "rtmp://media.example.net:1935/screen?user=luigiscreen&pass=read-secret",
                output.pluginUrl()
        );
        assertTrue(output.config().contains("- user: luigiscreen"));
        assertTrue(output.config().contains("- action: read"));
        assertTrue(output.config().contains("pass: \"read-secret\""));
        assertFalse(output.config().contains("@@"));
    }

    @Test
    void parsesSituationAliases() {
        assertEquals(MediaMtxSituation.SAME_PC, MediaMtxSituation.parse("localhost"));
        assertEquals(MediaMtxSituation.VPN, MediaMtxSituation.parse("cgnat"));
        assertEquals(MediaMtxSituation.HOSTING, MediaMtxSituation.parse("external"));
    }

    @Test
    void rendersTheBundledFullTemplateWithoutLeakingOldCredentials() throws Exception {
        String template;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("mediamtx-template.yml")) {
            assertTrue(input != null);
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        MediaMtxConfigGenerator.Output output = MediaMtxConfigGenerator.generate(
                template,
                new MediaMtxConfigGenerator.Request(
                        MediaMtxSituation.SAME_PC,
                        "127.0.0.1",
                        55556,
                        "127.0.0.1",
                        55556,
                        "",
                        "new-publish-secret",
                        "",
                        false
                )
        );

        assertTrue(template.contains("@@AUTH_USERS@@"));
        assertFalse(output.config().contains("@@"));
        assertTrue(output.config().contains("rtmp: true"));
        assertTrue(output.config().contains("rtmpAddress: :55556"));
        assertTrue(output.config().contains("rtsp: false"));
        assertTrue(output.config().contains("hls: false"));
        assertTrue(output.config().contains("webrtc: false"));
        assertTrue(output.config().contains("srt: false"));
        assertTrue(output.config().contains("moq: false"));
    }
}
