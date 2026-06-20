package cz.luigismp.screen;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public final class ScreenPermissions {

    static final String ADMIN = "luigiscreen.admin";
    static final String SEE_ALL = "luigiscreen.see.*";

    private static final Map<String, String> COMMAND_NODES = Map.ofEntries(
            Map.entry("create", "luigiscreen.create"),
            Map.entry("clone", "luigiscreen.clone"),
            Map.entry("list", "luigiscreen.list"),
            Map.entry("start", "luigiscreen.start"),
            Map.entry("stop", "luigiscreen.stop"),
            Map.entry("remove", "luigiscreen.remove"),
            Map.entry("status", "luigiscreen.status"),
            Map.entry("source", "luigiscreen.source"),
            Map.entry("playlist", "luigiscreen.playlist"),
            Map.entry("event", "luigiscreen.event"),
            Map.entry("set", "luigiscreen.set"),
            Map.entry("reload", "luigiscreen.reload"),
            Map.entry("debug", "luigiscreen.debug"),
            Map.entry("menu", "luigiscreen.menu.dashboard"),
            Map.entry("studio", "luigiscreen.menu.dashboard"),
            Map.entry("web", "luigiscreen.web"),
            Map.entry("vote", "luigiscreen.vote"),
            Map.entry("mediamtx", "luigiscreen.mediamtx")
    );

    private ScreenPermissions() {
    }

    static String commandNode(String subcommand) {
        return COMMAND_NODES.get(subcommand);
    }

    static String viewNode(String screenId) {
        return "luigiscreen.see." + ScreenDefinition.normalizeId(screenId);
    }

    static boolean canUse(CommandSender sender, String subcommand) {
        String node = commandNode(subcommand);
        return node == null || sender.hasPermission(ADMIN) || sender.hasPermission(node);
    }

    static boolean canView(Player player, ScreenDefinition definition) {
        return !definition.permissionRequired()
                || player.hasPermission(ADMIN)
                || player.hasPermission(SEE_ALL)
                || player.hasPermission(viewNode(definition.id()));
    }
}
