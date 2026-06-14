package cz.luigismp.screen;

import java.util.List;
import java.util.Locale;

enum MediaMtxSituation {
    SAME_PC("same-pc", "SAME_PC"),
    LAN("lan", "LAN"),
    INTERNET("internet", "INTERNET"),
    VPN("vpn", "VPN"),
    HOSTING("hosting", "HOSTING");

    static final List<String> COMMAND_NAMES =
            List.of("same-pc", "lan", "internet", "vpn", "hosting");

    private final String commandName;
    private final String folderName;

    MediaMtxSituation(String commandName, String folderName) {
        this.commandName = commandName;
        this.folderName = folderName;
    }

    String commandName() {
        return commandName;
    }

    String folderName() {
        return folderName;
    }

    static MediaMtxSituation parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "same-pc", "samepc", "local", "localhost" -> SAME_PC;
            case "lan", "home", "local-network" -> LAN;
            case "internet", "public", "public-ip" -> INTERNET;
            case "vpn", "cgnat", "tailscale", "zerotier" -> VPN;
            case "hosting", "external", "remote" -> HOSTING;
            default -> null;
        };
    }
}
