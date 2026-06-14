package cz.luigismp.screen;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class MediaMtxSetupManager implements Listener {

    private static final int DEFAULT_RTMP_PORT = 55556;
    private static final long SESSION_TIMEOUT_TICKS = 20L * 120;
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final LuigiScreenPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    MediaMtxSetupManager(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
    }

    void begin(Player player, String rawSituation) {
        MediaMtxSituation situation = MediaMtxSituation.parse(rawSituation);
        if (situation == null) {
            sendSituations(player);
            return;
        }

        cancel(player, false);
        Session session = new Session(situation);
        sessions.put(player.getUniqueId(), session);
        scheduleTimeout(player.getUniqueId(), session);

        switch (situation) {
            case SAME_PC -> complete(player, session, "127.0.0.1",
                    DEFAULT_RTMP_PORT, "127.0.0.1", DEFAULT_RTMP_PORT, "");
            case LAN -> startLan(player, session);
            case INTERNET -> startInternet(player, session);
            case VPN -> ask(player, session, Question.VPN_HOST);
            case HOSTING -> ask(player, session, Question.HOSTING_HOST);
        }
    }

    void shutdown() {
        sessions.clear();
    }

    static List<String> situationNames() {
        return MediaMtxSituation.COMMAND_NAMES;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        event.setCancelled(true);
        String answer = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> accept(player, session, answer));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void startLan(Player player, Session session) {
        List<String> addresses = detectLanAddresses();
        if (addresses.size() == 1) {
            complete(player, session, addresses.getFirst(), DEFAULT_RTMP_PORT,
                    "127.0.0.1", DEFAULT_RTMP_PORT, addresses.getFirst());
            return;
        }
        session.detectedLanAddresses = addresses;
        ask(player, session, Question.LAN_HOST);
    }

    private void startInternet(Player player, Session session) {
        List<String> addresses = detectLanAddresses();
        if (addresses.size() == 1) {
            session.lanHost = addresses.getFirst();
            ask(player, session, Question.PUBLIC_HOST);
            return;
        }
        session.detectedLanAddresses = addresses;
        ask(player, session, Question.LAN_HOST);
    }

    private void accept(Player player, Session expectedSession, String answer) {
        Session session = sessions.get(player.getUniqueId());
        if (session != expectedSession) {
            return;
        }
        if (answer.equalsIgnoreCase("cancel")) {
            cancel(player, true);
            return;
        }

        switch (session.question) {
            case LAN_HOST -> {
                String host = validateHost(answer);
                if (host == null) {
                    message(player, "mediamtx.invalid-host");
                    return;
                }
                session.lanHost = host;
                if (session.situation == MediaMtxSituation.LAN) {
                    complete(player, session, host, DEFAULT_RTMP_PORT,
                            "127.0.0.1", DEFAULT_RTMP_PORT, host);
                } else {
                    ask(player, session, Question.PUBLIC_HOST);
                }
            }
            case PUBLIC_HOST -> {
                String host = validateHost(answer);
                if (host == null) {
                    message(player, "mediamtx.invalid-public-host");
                    return;
                }
                session.publicHost = host;
                ask(player, session, Question.PUBLIC_PORT);
            }
            case PUBLIC_PORT -> {
                Integer port = parsePort(answer, DEFAULT_RTMP_PORT);
                if (port == null) {
                    message(player, "mediamtx.invalid-port");
                    return;
                }
                complete(player, session, session.publicHost, port,
                        "127.0.0.1", DEFAULT_RTMP_PORT, session.lanHost);
            }
            case VPN_HOST -> {
                String host = validateHost(answer);
                if (host == null) {
                    message(player, "mediamtx.invalid-vpn-host");
                    return;
                }
                complete(player, session, host, DEFAULT_RTMP_PORT,
                        "127.0.0.1", DEFAULT_RTMP_PORT, "");
            }
            case HOSTING_HOST -> {
                String host = validateHost(answer);
                if (host == null) {
                    message(player, "mediamtx.invalid-hosting-host");
                    return;
                }
                session.publicHost = host;
                ask(player, session, Question.HOSTING_PORT);
            }
            case HOSTING_PORT -> {
                Integer port = parsePort(answer, DEFAULT_RTMP_PORT);
                if (port == null) {
                    message(player, "mediamtx.invalid-port");
                    return;
                }
                complete(player, session, session.publicHost, port,
                        session.publicHost, port, "");
            }
        }
    }

    private void complete(Player player, Session session, String obsHost, int obsPort,
                          String pluginHost, int mediaMtxPort, String lanHost) {
        if (sessions.get(player.getUniqueId()) != session) {
            return;
        }

        String publisherPassword = password();
        boolean remoteReader = session.situation == MediaMtxSituation.HOSTING;
        String readerPassword = remoteReader ? password() : "";
        MediaMtxConfigGenerator.Request request = new MediaMtxConfigGenerator.Request(
                session.situation,
                obsHost,
                obsPort,
                pluginHost,
                mediaMtxPort,
                lanHost,
                publisherPassword,
                readerPassword,
                remoteReader
        );

        try {
            String template = loadTemplate();
            MediaMtxConfigGenerator.Output output =
                    MediaMtxConfigGenerator.generate(template, request);
            Path directory = plugin.getDataFolder().toPath()
                    .resolve("mediamtx")
                    .resolve(session.situation.folderName())
                    .normalize();
            Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
            Path absoluteDirectory = directory.toAbsolutePath().normalize();
            if (!absoluteDirectory.startsWith(dataFolder)) {
                throw new IOException("unsafe setup directory");
            }

            Files.createDirectories(absoluteDirectory);
            Path config = absoluteDirectory.resolve("mediamtx.yml");
            Path setup = absoluteDirectory.resolve("setup.txt");
            backup(config);
            backup(setup);
            writeAtomic(config, output.config());
            writeAtomic(setup, setupText(request, output));

            if (!plugin.applyGeneratedStreamUrl(output.pluginUrl())) {
                throw new IOException("the current RTMP worker did not stop safely");
            }

            sessions.remove(player.getUniqueId(), session);
            message(player, "mediamtx.generated",
                    "situation", plugin.messages().situation(session.situation));
            message(player, "mediamtx.folder", "folder", absoluteDirectory);
            message(player, "mediamtx.obs-server", "url", output.obsUrl());
            message(player, "mediamtx.obs-options");
            if (plugin.isStreamRestartPending()) {
                message(player, "mediamtx.decoder-switching");
            }
            message(player, "mediamtx.private-warning");
        } catch (IOException exception) {
            sessions.remove(player.getUniqueId(), session);
            plugin.getLogger().severe(plugin.messages().plain(
                    "logs.mediamtx-failed", "error", exception.getMessage()));
            message(player, "mediamtx.failed");
        }
    }

    private void ask(Player player, Session session, Question question) {
        session.question = question;
        player.sendMessage(plugin.messages().prefixed(
                question.key(session),
                "addresses", session.detectedLanAddresses,
                "port", DEFAULT_RTMP_PORT
        ));
        message(player, "mediamtx.private-answer");
    }

    private void sendSituations(Player player) {
        message(player, "mediamtx.choose");
        message(player, "mediamtx.option-same-pc");
        message(player, "mediamtx.option-lan");
        message(player, "mediamtx.option-internet");
        message(player, "mediamtx.option-vpn");
        message(player, "mediamtx.option-hosting");
    }

    private void cancel(Player player, boolean notify) {
        Session removed = sessions.remove(player.getUniqueId());
        if (notify && removed != null) {
            message(player, "mediamtx.cancelled");
        }
    }

    private void scheduleTimeout(UUID playerId, Session session) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!sessions.remove(playerId, session)) {
                return;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                message(player, "mediamtx.timeout");
            }
        }, SESSION_TIMEOUT_TICKS);
    }

    private String loadTemplate() throws IOException {
        try (InputStream input = plugin.getResource("mediamtx-template.yml")) {
            if (input == null) {
                throw new IOException("embedded mediamtx template is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void backup(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        String backupName = file.getFileName() + ".backup-"
                + LocalDateTime.now().format(BACKUP_TIME);
        Files.copy(file, file.resolveSibling(backupName),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeAtomic(Path destination, String content) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> detectLanAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> networkAddresses = network.getInetAddresses();
                while (networkAddresses.hasMoreElements()) {
                    InetAddress address = networkAddresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return addresses.stream().distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private static String validateHost(String value) {
        String host = value.trim();
        if (host.isEmpty() || host.length() > 253 || host.contains("://")
                || host.contains("/") || host.contains("?") || host.contains("#")
                || host.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        if (!host.matches("[A-Za-z0-9._:-]+")) {
            return null;
        }
        return host;
    }

    private static Integer parsePort(String value, int defaultPort) {
        if (value.equalsIgnoreCase("default")) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String password() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String setupText(MediaMtxConfigGenerator.Request request,
                             MediaMtxConfigGenerator.Output output) {
        String header = plugin.messages().plain("mediamtx.setup-header",
                "situation", plugin.messages().situation(request.situation()),
                "port", request.mediaMtxPort(),
                "obs_url", output.obsUrl(),
                "plugin_url", output.pluginUrl());
        String situation = switch (request.situation()) {
            case SAME_PC -> plugin.messages().plain("mediamtx.setup-same-pc");
            case LAN -> plugin.messages().plain("mediamtx.setup-lan",
                    "port", request.mediaMtxPort());
            case INTERNET -> plugin.messages().plain("mediamtx.setup-internet",
                    "public_port", request.obsPort(),
                    "lan_host", request.lanHost(),
                    "local_port", request.mediaMtxPort());
            case VPN -> plugin.messages().plain("mediamtx.setup-vpn",
                    "port", request.mediaMtxPort());
            case HOSTING -> plugin.messages().plain("mediamtx.setup-hosting",
                    "port", request.mediaMtxPort());
        };
        return header + System.lineSeparator() + System.lineSeparator()
                + situation + System.lineSeparator() + System.lineSeparator()
                + plugin.messages().plain("mediamtx.setup-security")
                + System.lineSeparator();
    }

    private void message(Player player, String key, Object... placeholders) {
        plugin.messages().send(player, key, placeholders);
    }

    private enum Question {
        LAN_HOST,
        PUBLIC_HOST,
        PUBLIC_PORT,
        VPN_HOST,
        HOSTING_HOST,
        HOSTING_PORT;

        String key(Session session) {
            return switch (this) {
                case LAN_HOST -> session.detectedLanAddresses.isEmpty()
                        ? "mediamtx.prompt-lan"
                        : "mediamtx.prompt-lan-multiple";
                case PUBLIC_HOST -> "mediamtx.prompt-public-host";
                case PUBLIC_PORT -> "mediamtx.prompt-public-port";
                case VPN_HOST -> "mediamtx.prompt-vpn-host";
                case HOSTING_HOST -> "mediamtx.prompt-hosting-host";
                case HOSTING_PORT -> "mediamtx.prompt-hosting-port";
            };
        }
    }

    private static final class Session {
        private final MediaMtxSituation situation;
        private Question question;
        private List<String> detectedLanAddresses = List.of();
        private String lanHost = "";
        private String publicHost = "";

        private Session(MediaMtxSituation situation) {
            this.situation = situation;
        }
    }
}
