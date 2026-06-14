package cz.luigismp.screen;

import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ScreenCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "clone", "list", "start", "stop", "remove",
            "status", "set", "reload", "debug", "mediamtx");
    private static final List<String> SET_PROPERTIES =
            List.of("url", "fps", "distance", "enabled");
    private final LuigiScreenPlugin plugin;

    ScreenCommand(LuigiScreenPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "clone" -> cloneScreen(sender, args);
            case "list" -> list(sender);
            case "start" -> start(sender, args);
            case "stop" -> stop(sender, args);
            case "remove" -> remove(sender, args);
            case "status" -> status(sender, args);
            case "set" -> set(sender, args);
            case "debug" -> debug(sender);
            case "mediamtx" -> mediaMtx(sender, args);
            case "reload" -> {
                if (plugin.reloadScreenConfig()) {
                    message(sender, "commands.reload-success");
                } else {
                    message(sender, "commands.reload-failed");
                }
            }
            default -> help(sender);
        }
        return true;
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "commands.create-player-only");
            return;
        }

        boolean legacySizeSyntax = args.length > 1 && isInteger(args[1]);
        String id = legacySizeSyntax || args.length < 2 ? "main" : args[1];
        int widthIndex = legacySizeSyntax ? 1 : 2;
        int width = parseSize(args, widthIndex, 7);
        int height = parseSize(args, widthIndex + 1, 4);
        if (!validateCreate(sender, id, width, height)) {
            return;
        }
        WallTarget target = wallTarget(player);
        if (target == null) {
            message(sender, "commands.create-look-at-wall");
            return;
        }
        if (plugin.createScreen(id, player.getWorld(), target.location(),
                width, height, target.face())) {
            message(sender, "commands.create-success",
                    "screen", ScreenDefinition.normalizeId(id),
                    "width", width, "height", height);
        } else {
            message(sender, "commands.create-failed");
        }
    }

    private void cloneScreen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "commands.create-player-only");
            return;
        }
        if (args.length < 3) {
            message(sender, "commands.clone-usage");
            return;
        }
        String source = ScreenDefinition.normalizeId(args[1]);
        String clone = ScreenDefinition.normalizeId(args[2]);
        if (!plugin.hasScreen(source)) {
            message(sender, "commands.screen-missing", "screen", source);
            return;
        }
        if (!ScreenDefinition.isValidId(clone)) {
            message(sender, "commands.invalid-name");
            return;
        }
        if (plugin.hasScreen(clone)) {
            message(sender, "commands.name-exists", "screen", clone);
            return;
        }
        WallTarget target = wallTarget(player);
        if (target == null) {
            message(sender, "commands.create-look-at-wall");
            return;
        }
        if (plugin.cloneScreen(source, clone, player.getWorld(),
                target.location(), target.face())) {
            message(sender, "commands.clone-success",
                    "source", source, "screen", clone);
        } else {
            message(sender, "commands.clone-failed");
        }
    }

    private void list(CommandSender sender) {
        List<String> ids = plugin.screenIds();
        if (ids.isEmpty()) {
            message(sender, "commands.no-display");
            return;
        }
        message(sender, "commands.list-header",
                "screens", ids.size(), "sources", uniqueSourceCount(ids));
        for (String id : ids) {
            ScreenDefinition definition = plugin.screenDefinition(id);
            message(sender, "commands.list-entry",
                    "screen", id,
                    "enabled", definition.enabled(),
                    "width", definition.width(),
                    "height", definition.height(),
                    "world", definition.world(),
                    "fps", format(definition.fps()),
                    "distance", format(definition.distance()),
                    "url", StreamUrlSanitizer.mask(definition.url()));
        }
    }

    private int uniqueSourceCount(List<String> ids) {
        List<ScreenDefinition> definitions = ids.stream()
                .map(plugin::screenDefinition)
                .toList();
        return (int) ScreenSourcePolicy.uniqueSourceCount(definitions);
    }

    private void start(CommandSender sender, String[] args) {
        String target = target(sender, args, 1, true);
        if (target == null) {
            return;
        }
        if ("all".equals(target)) {
            int changed = plugin.startAllScreens();
            message(sender, "commands.all-started", "count", changed);
        } else if (plugin.startScreen(target)) {
            message(sender, "commands.screen-started", "screen", target);
        } else {
            message(sender, "commands.screen-missing", "screen", target);
        }
    }

    private void stop(CommandSender sender, String[] args) {
        String target = target(sender, args, 1, true);
        if (target == null) {
            return;
        }
        if ("all".equals(target)) {
            int changed = plugin.stopAllScreens();
            message(sender, "commands.all-stopped", "count", changed);
        } else if (plugin.stopScreen(target)) {
            message(sender, "commands.screen-stopped", "screen", target);
        } else if (!plugin.hasScreen(target)) {
            message(sender, "commands.screen-missing", "screen", target);
        } else {
            message(sender, "commands.decoder-stopping");
        }
    }

    private void remove(CommandSender sender, String[] args) {
        String target = target(sender, args, 1, false);
        if (target == null) {
            return;
        }
        if (plugin.removeScreen(target)) {
            message(sender, "commands.screen-removed", "screen", target);
        } else if (!plugin.hasScreen(target)) {
            message(sender, "commands.screen-missing", "screen", target);
        } else {
            message(sender, "commands.display-remove-failed");
        }
    }

    private void status(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.messages().component("prefix").append(plugin.status()));
            return;
        }
        sender.sendMessage(plugin.messages().component("prefix")
                .append(plugin.status(args[1])));
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 4) {
            message(sender, "commands.set-usage");
            return;
        }
        String id = ScreenDefinition.normalizeId(args[1]);
        if (!plugin.hasScreen(id)) {
            message(sender, "commands.screen-missing", "screen", id);
            return;
        }
        String property = args[2].toLowerCase(Locale.ROOT);
        boolean changed;
        switch (property) {
            case "url" -> changed = plugin.setScreenUrl(
                    id, String.join(" ", List.of(args).subList(3, args.length)));
            case "fps" -> {
                Double value = parseDouble(args[3]);
                changed = value != null && plugin.setScreenFps(id, value);
            }
            case "distance" -> {
                Double value = parseDouble(args[3]);
                changed = value != null && plugin.setScreenDistance(id, value);
            }
            case "enabled" -> {
                Boolean value = parseBoolean(args[3]);
                changed = value != null && plugin.setScreenEnabled(id, value);
            }
            default -> {
                message(sender, "commands.set-usage");
                return;
            }
        }
        if (changed) {
            message(sender, "commands.set-success",
                    "screen", id, "property", property);
        } else {
            message(sender, "commands.set-invalid",
                    "property", property);
        }
    }

    private String target(CommandSender sender, String[] args, int index, boolean allowAll) {
        if (args.length > index) {
            String value = ScreenDefinition.normalizeId(args[index]);
            if (allowAll && "all".equals(value)) {
                return value;
            }
            return value;
        }
        List<String> ids = plugin.screenIds();
        if (ids.isEmpty()) {
            message(sender, "commands.no-display");
            return null;
        }
        if (ids.size() == 1) {
            return ids.getFirst();
        }
        message(sender, "commands.target-required");
        return null;
    }

    private boolean validateCreate(CommandSender sender, String id, int width, int height) {
        String normalized = ScreenDefinition.normalizeId(id);
        if (!ScreenDefinition.isValidId(normalized)) {
            message(sender, "commands.invalid-name");
            return false;
        }
        if (plugin.hasScreen(normalized)) {
            message(sender, "commands.name-exists", "screen", normalized);
            return false;
        }
        int maxWidth = plugin.maxScreenWidth();
        int maxHeight = plugin.maxScreenHeight();
        int maxMaps = plugin.maxTotalMaps();
        if (width < 1 || height < 1 || width > maxWidth || height > maxHeight) {
            message(sender, "commands.create-size",
                    "max_width", maxWidth, "max_height", maxHeight);
            return false;
        }
        if ((long) width * height > maxMaps) {
            message(sender, "commands.create-map-limit", "max_maps", maxMaps);
            return false;
        }
        return true;
    }

    private void mediaMtx(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "commands.mediamtx-player-only");
            return;
        }
        plugin.beginMediaMtxSetup(player, args.length < 2 ? "" : args[1]);
    }

    private void debug(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            message(sender, "commands.debug-player-only");
            return;
        }
        message(sender, plugin.toggleDebug(player)
                ? "commands.debug-enabled" : "commands.debug-disabled");
    }

    private void help(CommandSender sender) {
        message(sender, "commands.help-create");
        message(sender, "commands.help-clone");
        message(sender, "commands.help-control");
        message(sender, "commands.help-set");
        message(sender, "commands.help-debug");
        message(sender, "commands.help-mediamtx",
                "argument", "<" + plugin.messages().plain("commands.mediamtx-argument") + ">");
    }

    private void message(CommandSender sender, String key, Object... placeholders) {
        plugin.messages().send(sender, key, placeholders);
    }

    private static WallTarget wallTarget(Player player) {
        RayTraceResult trace = player.rayTraceBlocks(10, FluidCollisionMode.NEVER);
        Block target = trace == null ? null : trace.getHitBlock();
        BlockFace face = trace == null ? null : trace.getHitBlockFace();
        if (target == null || face == null || face.getModY() != 0) {
            return null;
        }
        return new WallTarget(
                target.getLocation().toVector().toBlockVector(),
                face
        );
    }

    private static int parseSize(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> null;
        };
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && subcommand.equals("mediamtx")) {
            return matching(MediaMtxSetupManager.situationNames(), args[1]);
        }
        if (args.length == 2 && List.of(
                "clone", "start", "stop", "remove", "status", "set").contains(subcommand)) {
            List<String> values = new ArrayList<>(plugin.screenIds());
            if (subcommand.equals("start") || subcommand.equals("stop")) {
                values.add("all");
            }
            return matching(values, args[1]);
        }
        if (args.length == 3 && subcommand.equals("set")) {
            return matching(SET_PROPERTIES, args[2]);
        }
        if (args.length == 4 && subcommand.equals("set")
                && args[2].equalsIgnoreCase("enabled")) {
            return matching(List.of("true", "false"), args[3]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> values, String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private record WallTarget(BlockVector location, BlockFace face) {
    }
}
