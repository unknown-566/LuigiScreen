package cz.luigismp.screen;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenPermissionsTest {

    @Test
    void everySubcommandHasItsOwnPermission() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("create", "luigiscreen.create"),
                Map.entry("clone", "luigiscreen.clone"),
                Map.entry("list", "luigiscreen.list"),
                Map.entry("start", "luigiscreen.start"),
                Map.entry("stop", "luigiscreen.stop"),
                Map.entry("remove", "luigiscreen.remove"),
                Map.entry("status", "luigiscreen.status"),
                Map.entry("source", "luigiscreen.source"),
                Map.entry("set", "luigiscreen.set"),
                Map.entry("reload", "luigiscreen.reload"),
                Map.entry("debug", "luigiscreen.debug"),
                Map.entry("mediamtx", "luigiscreen.mediamtx")
        );

        expected.forEach((command, permission) ->
                assertEquals(permission, ScreenPermissions.commandNode(command)));
        assertNull(ScreenPermissions.commandNode("unknown"));
    }

    @Test
    void screenViewPermissionUsesTheNormalizedScreenName() {
        assertEquals("luigiscreen.see.spawn_tv",
                ScreenPermissions.viewNode(" Spawn_TV "));
    }

    @Test
    void pluginYamlExposesGranularPermissionsWithoutBlockingTheRootCommand() {
        var stream = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(stream);
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        assertNull(plugin.getString("commands.luigiscreen.permission"));
        assertEquals("unknown_56", plugin.getStringList("authors").getFirst());
        assertEquals("op", plugin.getString("permissions.luigiscreen.admin.default"));
        assertTrue(plugin.getBoolean(
                "permissions.luigiscreen.admin.children.luigiscreen.create"));
        assertTrue(plugin.getBoolean(
                "permissions.luigiscreen.admin.children.luigiscreen.source"));
        assertTrue(plugin.getBoolean(
                "permissions.luigiscreen.admin.children.luigiscreen.see.*"));
        assertFalse(plugin.getBoolean("permissions.luigiscreen.create.default"));
        assertFalse(plugin.getBoolean("permissions.luigiscreen.see.*.default"));
    }

    @Test
    void protectedScreensAcceptSpecificWildcardAndAdminPermissions() {
        ScreenDefinition publicScreen = definition(false);
        ScreenDefinition protectedScreen = definition(true);

        assertTrue(ScreenPermissions.canView(playerWith(), publicScreen));
        assertFalse(ScreenPermissions.canView(playerWith(), protectedScreen));
        assertTrue(ScreenPermissions.canView(
                playerWith("luigiscreen.see.cinema"), protectedScreen));
        assertTrue(ScreenPermissions.canView(
                playerWith(ScreenPermissions.SEE_ALL), protectedScreen));
        assertTrue(ScreenPermissions.canView(
                playerWith(ScreenPermissions.ADMIN), protectedScreen));
    }

    private static ScreenDefinition definition(boolean permissionRequired) {
        return new ScreenDefinition(
                "cinema",
                ScreenSource.rtmp("rtmp://example/screen"),
                8,
                64,
                "world",
                new org.bukkit.util.BlockVector(0, 80, 0),
                7,
                4,
                org.bukkit.block.BlockFace.NORTH,
                true,
                permissionRequired
        );
    }

    private static Player playerWith(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("hasPermission")) {
                        return granted.contains(arguments[0]);
                    }
                    if (method.getName().equals("toString")) {
                        return "PermissionTestPlayer";
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                }
        );
    }
}
