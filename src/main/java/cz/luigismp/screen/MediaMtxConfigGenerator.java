package cz.luigismp.screen;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class MediaMtxConfigGenerator {

    private MediaMtxConfigGenerator() {
    }

    static Output generate(String template, Request request) {
        String authUsers = request.remoteReader()
                ? remoteAuthUsers(request.publisherPassword(), request.readerPassword())
                : localAuthUsers(request.publisherPassword());
        String config = template
                .replace("@@AUTH_USERS@@", authUsers)
                .replace("@@RTMP_PORT@@", Integer.toString(request.mediaMtxPort()));

        String publishQuery = "?user=streamer&pass=" + encode(request.publisherPassword());
        String obsUrl = "rtmp://" + urlHost(request.obsHost()) + ":" + request.obsPort()
                + "/screen" + publishQuery;
        String pluginUrl;
        if (request.remoteReader()) {
            pluginUrl = "rtmp://" + urlHost(request.pluginHost()) + ":"
                    + request.mediaMtxPort() + "/screen?user=luigiscreen&pass="
                    + encode(request.readerPassword());
        } else {
            pluginUrl = "rtmp://127.0.0.1:" + request.mediaMtxPort() + "/screen";
        }

        return new Output(config, obsUrl, pluginUrl);
    }

    private static String localAuthUsers(String publisherPassword) {
        return """
                  # OBS can only publish to the screen path.
                  - user: streamer
                    pass: "%s"
                    ips: []
                    permissions:
                      - action: publish
                        path: screen

                  # LuigiScreen can read only from the MediaMTX machine itself.
                  - user: any
                    pass:
                    ips: ["127.0.0.1", "::1"]
                    permissions:
                      - action: read
                        path: screen
                      - action: api
                      - action: metrics
                      - action: pprof
                """.formatted(publisherPassword).stripTrailing();
    }

    private static String remoteAuthUsers(String publisherPassword, String readerPassword) {
        return """
                  # OBS can only publish to the screen path.
                  - user: streamer
                    pass: "%s"
                    ips: []
                    permissions:
                      - action: publish
                        path: screen

                  # LuigiScreen reads from another machine with separate credentials.
                  - user: luigiscreen
                    pass: "%s"
                    ips: []
                    permissions:
                      - action: read
                        path: screen

                  - user: any
                    pass:
                    ips: ["127.0.0.1", "::1"]
                    permissions:
                      - action: api
                      - action: metrics
                      - action: pprof
                """.formatted(publisherPassword, readerPassword).stripTrailing();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlHost(String host) {
        String clean = host.trim();
        if (clean.contains(":") && !clean.startsWith("[")) {
            return "[" + clean + "]";
        }
        return clean;
    }

    record Request(
            MediaMtxSituation situation,
            String obsHost,
            int obsPort,
            String pluginHost,
            int mediaMtxPort,
            String lanHost,
            String publisherPassword,
            String readerPassword,
            boolean remoteReader
    ) {
    }

    record Output(String config, String obsUrl, String pluginUrl) {
    }
}
