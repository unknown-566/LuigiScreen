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

import java.util.List;
import java.util.Locale;

final class ScreenCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("create", "start", "stop", "remove", "status", "reload", "debug", "mediamtx");
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
            case "start" -> {
                if (!plugin.hasDisplay()) {
                    message(sender, "commands.no-display");
                } else if (plugin.startStream()) {
                    message(sender, "commands.stream-connecting");
                } else {
                    message(sender, "commands.stream-already-running");
                }
            }
            case "stop" -> {
                if (plugin.stopStream()) {
                    message(sender, "commands.stream-stopped");
                } else {
                    message(sender, "commands.decoder-stopping");
                }
            }
            case "remove" -> {
                if (plugin.removeDisplay(true)) {
                    message(sender, "commands.display-removed");
                } else {
                    message(sender, "commands.display-remove-failed");
                }
            }
            case "status" -> sender.sendMessage(
                    plugin.messages().component("prefix").append(plugin.status()));
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

    private void mediaMtx(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "commands.mediamtx-player-only");
            return;
        }
        if (args.length < 2) {
            plugin.beginMediaMtxSetup(player, "");
            return;
        }
        plugin.beginMediaMtxSetup(player, args[1]);
    }

    private void debug(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            message(sender, "commands.debug-player-only");
            return;
        }

        if (plugin.toggleDebug(player)) {
            message(sender, "commands.debug-enabled");
        } else {
            message(sender, "commands.debug-disabled");
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "commands.create-player-only");
            return;
        }

        int width = parseSize(args, 1, 7);
        int height = parseSize(args, 2, 4);
        int maxWidth = plugin.maxScreenWidth();
        int maxHeight = plugin.maxScreenHeight();
        int maxMaps = plugin.maxTotalMaps();
        if (width < 1 || height < 1 || width > maxWidth || height > maxHeight) {
            message(sender, "commands.create-size",
                    "max_width", maxWidth, "max_height", maxHeight);
            return;
        }
        if ((long) width * height > maxMaps) {
            message(sender, "commands.create-map-limit", "max_maps", maxMaps);
            return;
        }

        RayTraceResult trace = player.rayTraceBlocks(10, FluidCollisionMode.NEVER);
        Block target = trace == null ? null : trace.getHitBlock();
        BlockFace face = trace == null ? null : trace.getHitBlockFace();
        if (target == null || face == null || face.getModY() != 0) {
            message(sender, "commands.create-look-at-wall");
            return;
        }

        BlockVector a = target.getLocation().toVector().toBlockVector();
        BlockVector b = secondCorner(a, face, width, height);
        if (plugin.createDisplay(player.getWorld(), a, b, face)) {
            message(sender, "commands.create-success", "width", width, "height", height);
        } else {
            message(sender, "commands.create-failed");
        }
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

    private static BlockVector secondCorner(BlockVector a, BlockFace face, int width, int height) {
        BlockVector b = a.clone();
        b.setY(a.getBlockY() - height + 1);

        switch (face) {
            case NORTH -> b.setX(a.getBlockX() - width + 1);
            case SOUTH -> b.setX(a.getBlockX() + width - 1);
            case EAST -> b.setZ(a.getBlockZ() - width + 1);
            case WEST -> b.setZ(a.getBlockZ() + width - 1);
            default -> throw new IllegalArgumentException("Screen must be vertical");
        }
        return b;
    }

    private void help(CommandSender sender) {
        message(sender, "commands.help-create");
        message(sender, "commands.help-control");
        message(sender, "commands.help-debug");
        message(sender, "commands.help-mediamtx",
                "argument", "<" + plugin.messages().plain("commands.mediamtx-argument") + ">");
    }

    private void message(CommandSender sender, String key, Object... placeholders) {
        plugin.messages().send(sender, key, placeholders);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(value -> value.startsWith(input)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mediamtx")) {
            String input = args[1].toLowerCase(Locale.ROOT);
            return MediaMtxSetupManager.situationNames().stream()
                    .filter(value -> value.startsWith(input))
                    .toList();
        }
        return List.of();
    }
}
